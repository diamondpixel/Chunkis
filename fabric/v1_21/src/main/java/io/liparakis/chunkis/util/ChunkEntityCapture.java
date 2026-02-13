package io.liparakis.chunkis.util;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.ChunkDelta;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Handles entity capture for chunk persistence in CIS format.
 * <p>
 * This utility captures all entities within a chunk's bounds and serializes
 * them to NBT for storage in the chunk delta. Only entities that meet specific
 * criteria are captured (non-players, non-removed, non-passengers, physically
 * within chunk bounds).
 * <p>
 * <b>Performance Optimizations:</b>
 * <ul>
 * <li>Uses chunk coordinate math instead of {@code entity.getChunkPos()}</li>
 * <li>Filters entities during world query to reduce iteration overhead</li>
 * <li>Pre-sizes collection based on filtered results</li>
 * <li>Uses bit-shift operations for fast coordinate conversion</li>
 * </ul>
 * <p>
 * <b>Thread Safety:</b> All methods must be called on the server thread.
 *
 * @author Liparakis
 * @version 1.0
 */
public final class ChunkEntityCapture {

    /**
     * Reasonable maximum for pre-sizing entity list to avoid over-allocation.
     */
    private static final int MAX_PRESIZED_CAPACITY = 16;

    /**
     * Bit shift amount for converting block coordinates to chunk coordinates.
     * Equivalent to dividing by 16 (chunk size).
     */
    private static final int CHUNK_COORD_SHIFT = 4;

    private ChunkEntityCapture() {
        throw new AssertionError("Utility class - do not instantiate");
    }

    /**
     * Captures all eligible entities in the chunk and stores them in the delta.
     * <p>
     * This method queries the world for entities within the chunk bounds,
     * filters them according to capture rules, serializes them to NBT,
     * and stores the result in the provided delta.
     * <p>
     * <b>Important:</b> Must be called on the server thread.
     *
     * @param chunk the chunk being saved
     * @param delta the delta to store entity data in
     * @param world the server world for entity queries
     * @throws NullPointerException  if any parameter is null
     * @throws IllegalStateException if called from non-server thread
     */
    public static void captureAll(WorldChunk chunk, ChunkDelta<?, NbtCompound> delta, ServerWorld world) {
        Objects.requireNonNull(chunk, "chunk cannot be null");
        Objects.requireNonNull(delta, "delta cannot be null");
        Objects.requireNonNull(world, "world cannot be null");

        validateServerThread(world);

        ChunkPos chunkPos = chunk.getPos();
        logCaptureStart(chunkPos);

        List<Entity> entities = getEntitiesInChunk(world, chunkPos);
        List<NbtCompound> capturedEntities = serializeEntitiesToNbt(entities, world.getRegistryManager());

        delta.setEntities(capturedEntities);
        logCaptureComplete(chunkPos, capturedEntities.size(), delta.isDirty());
    }

    /**
     * Validates that the current thread is the server thread.
     *
     * @param world the server world
     * @throws IllegalStateException if called from non-server thread
     */
    private static void validateServerThread(ServerWorld world) {
        if (world.getServer().getThread() != Thread.currentThread()) {
            throw new IllegalStateException(
                    "ChunkEntityCapture.captureAll must be called on server thread");
        }
    }

    /**
     * Queries all capturable entities within the chunk bounds.
     * <p>
     * Applies filtering during the query for optimal performance,
     * avoiding a second iteration pass.
     * <p>
     * <b>Thread Safety:</b> Must execute on server thread.
     *
     * @param world    the server world
     * @param chunkPos the chunk position
     * @return list of entities that should be captured
     */
    private static List<Entity> getEntitiesInChunk(ServerWorld world, ChunkPos chunkPos) {
        Box chunkBounds = createChunkBounds(world, chunkPos);

        return world.getEntitiesByClass(
                Entity.class,
                chunkBounds,
                entity -> shouldCaptureEntity(entity, chunkPos));
    }

    /**
     * Creates a bounding box encompassing the entire vertical height of a chunk.
     *
     * @param world    the server world (for height limits)
     * @param chunkPos the chunk position
     * @return bounding box for the chunk
     */
    private static Box createChunkBounds(ServerWorld world, ChunkPos chunkPos) {
        return new Box(
                chunkPos.getStartX(),
                world.getBottomY(),
                chunkPos.getStartZ(),
                chunkPos.getEndX() + 1.0,
                world.getBottomY() + world.getHeight(),
                chunkPos.getEndZ() + 1.0);
    }

    /**
     * Serializes a list of entities to NBT compounds.
     * <p>
     * Pre-sizes the result list based on the input size, capped at a
     * reasonable maximum to avoid over-allocation for large entity counts.
     *
     * @param entities the entities to serialize
     * @return list of NBT compounds for successfully serialized entities
     */
    private static List<NbtCompound> serializeEntitiesToNbt(List<Entity> entities,
            net.minecraft.registry.RegistryWrapper.WrapperLookup registryManager) {
        int capacity = Math.min(entities.size(), MAX_PRESIZED_CAPACITY);
        List<NbtCompound> capturedEntities = new ArrayList<>(capacity);

        for (Entity entity : entities) {
            var nbt = serializeEntity(entity, registryManager);
            if (nbt != null) {
                capturedEntities.add(nbt);
            }
        }

        return capturedEntities;
    }

    /**
     * Serializes an entity to NBT format with error handling.
     *
     * @param entity the entity to serialize
     * @return the NBT compound, or null if serialization fails
     */
    private static NbtCompound serializeEntity(Entity entity,
            net.minecraft.registry.RegistryWrapper.WrapperLookup registryManager) {
        try {
            var nbt = new NbtCompound();
            if (entity.saveNbt(nbt)) {
                // Ensure ID is present
                if (!nbt.contains("id")) {
                    String id = net.minecraft.registry.Registries.ENTITY_TYPE.getId(entity.getType()).toString();
                    nbt.putString("id", id);
                }
                return nbt;
            }
            return null;
        } catch (Exception error) {
            Chunkis.LOGGER.error("Failed to serialize entity {} at {}",
                    entity.getType().getTranslationKey(), entity.getPos(), error);
            return null;
        }
    }

    /**
     * Determines if an entity should be captured for this chunk.
     * <p>
     * <b>Filtering Rules</b> (applied in order of performance):
     * <ol>
     * <li><b>Players:</b> Skipped - managed separately by Minecraft</li>
     * <li><b>Removed entities:</b> Skipped - already destroyed</li>
     * <li><b>Passengers:</b> Skipped - saved with their vehicle</li>
     * <li><b>Physical location:</b> Must be within chunk bounds</li>
     * </ol>
     * <p>
     * The chunk coordinate calculation uses bit-shift operations for optimal
     * performance: {@code (int)(x / 16) == (int)x >> 4}. This is more reliable
     * than {@code entity.getChunkPos()} which may be stale during chunk operations.
     *
     * @param entity   the entity to check
     * @param chunkPos the position of the chunk being saved
     * @return {@code true} if the entity should be captured
     */
    private static boolean shouldCaptureEntity(Entity entity, ChunkPos chunkPos) {
        if (isPlayer(entity)) {
            return false;
        }

        if (isRemoved(entity)) {
            return false;
        }

        if (isPassenger(entity)) {
            return false;
        }

        return isPhysicallyInChunk(entity, chunkPos);
    }

    /**
     * Checks if an entity is a player.
     * <p>
     * Players are managed separately by Minecraft's save system.
     *
     * @param entity the entity to check
     * @return {@code true} if the entity is a player
     */
    private static boolean isPlayer(Entity entity) {
        return entity instanceof PlayerEntity;
    }

    /**
     * Checks if an entity has been removed from the world.
     *
     * @param entity the entity to check
     * @return {@code true} if the entity is removed
     */
    private static boolean isRemoved(Entity entity) {
        return entity.isRemoved();
    }

    /**
     * Checks if an entity is riding another entity.
     * <p>
     * Passengers are saved with their vehicle to prevent duplication
     * when vehicle and passenger cross chunk boundaries.
     *
     * @param entity the entity to check
     * @return {@code true} if the entity is a passenger
     */
    private static boolean isPassenger(Entity entity) {
        return entity.hasVehicle();
    }

    /**
     * Verifies an entity's physical position is within the chunk bounds.
     * <p>
     * Uses bit-shift operations for fast floor division:
     * {@code floor(x / 16) == floor(x) >> 4}
     * <p>
     * This is more accurate than {@code entity.getChunkPos()} which may
     * be stale during chunk save operations.
     *
     * @param entity   the entity to check
     * @param chunkPos the chunk position
     * @return {@code true} if the entity is physically in this chunk
     */
    private static boolean isPhysicallyInChunk(Entity entity, ChunkPos chunkPos) {
        int entityChunkX = MathHelper.floor(entity.getX()) >> CHUNK_COORD_SHIFT;
        int entityChunkZ = MathHelper.floor(entity.getZ()) >> CHUNK_COORD_SHIFT;

        return entityChunkX == chunkPos.x && entityChunkZ == chunkPos.z;
    }

    /**
     * Logs the start of entity capture at debug level.
     *
     * @param chunkPos the chunk position being captured
     */
    private static void logCaptureStart(ChunkPos chunkPos) {
        if (Chunkis.LOGGER.isDebugEnabled()) {
            Chunkis.LOGGER.debug("Starting entity capture for chunk {}", chunkPos);
        }
    }

    /**
     * Logs the completion of entity capture at debug level.
     *
     * @param chunkPos    the chunk position
     * @param entityCount the number of entities captured
     * @param isDirty     whether the delta was marked dirty
     */
    private static void logCaptureComplete(ChunkPos chunkPos, int entityCount, boolean isDirty) {
        if (Chunkis.LOGGER.isDebugEnabled()) {
            Chunkis.LOGGER.debug(
                    "Entity capture complete for chunk {}: {} entities captured, dirty: {}",
                    chunkPos,
                    entityCount,
                    isDirty);
        }
    }
}