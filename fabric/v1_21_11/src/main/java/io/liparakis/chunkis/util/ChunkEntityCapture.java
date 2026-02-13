package io.liparakis.chunkis.util;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.ChunkDelta;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles entity capture during chunk persistence.
 *
 * <p>
 * Captures all non-player entities within a chunk's boundaries and serializes
 * them to NBT format for delta storage. This preserves entities across chunk
 * unloads and world reloads.
 *
 * <p>
 * <b>Entity Filtering Rules:</b>
 * <ol>
 * <li>Players excluded (managed by Minecraft's player data system)</li>
 * <li>Removed/dead entities excluded</li>
 * <li>Passengers excluded (saved with their vehicle)</li>
 * <li>Entities physically outside chunk bounds excluded (prevents
 * duplication)</li>
 * </ol>
 *
 * <p>
 * <b>Performance Optimizations:</b>
 * <ul>
 * <li>Direct chunk coordinate math (faster than entity.getChunkPos())</li>
 * <li>Bounding box queries on server thread (thread-safe batch operation)</li>
 * <li>Pre-sized ArrayList allocation based on candidate count</li>
 * <li>Early exit filtering reduces serialization overhead</li>
 * </ul>
 *
 * <p>
 * <b>Thread Safety:</b> All methods must execute on the server thread.
 * Entity queries and NBT serialization are not thread-safe.
 *
 * @author Liparakis
 * @version 2.0
 */
public final class ChunkEntityCapture {

    private static final Logger LOGGER = Chunkis.LOGGER;

    /**
     * Initial capacity for entity list to avoid excessive allocations.
     * Most chunks have fewer than 16 entities.
     */
    private static final int INITIAL_ENTITY_LIST_CAPACITY = 16;

    /**
     * Bit shift for converting world coordinates to chunk coordinates.
     * Equivalent to dividing by 16 (chunk width).
     */
    private static final int CHUNK_COORDINATE_SHIFT = 4;

    private ChunkEntityCapture() {
        throw new AssertionError("Utility class cannot be instantiated");
    }

    /**
     * Captures all eligible entities within a chunk and stores them in the delta.
     *
     * <p>
     * Queries all entities within the chunk's bounding box, filters using
     * {@link #shouldCaptureEntity}, serializes each to NBT, and stores in the
     * delta for persistence.
     *
     * <p>
     * <b>Entity Types Captured:</b>
     * <ul>
     * <li>Mobs (zombies, creepers, villagers, etc.)</li>
     * <li>Animals (cows, pigs, horses, etc.)</li>
     * <li>Items (dropped items, experience orbs)</li>
     * <li>Vehicles (boats, minecarts with passengers)</li>
     * <li>Projectiles (arrows, fireballs)</li>
     * </ul>
     *
     * <p>
     * <b>Thread Safety:</b> Must be called on server thread as it queries
     * world entity lists and performs NBT serialization.
     *
     * @param chunk the chunk being saved
     * @param delta the delta to store serialized entity data
     * @param world the server world for entity queries
     */
    public static void captureAll(WorldChunk chunk, ChunkDelta<?, NbtCompound> delta, ServerWorld world) {
        var chunkPosition = chunk.getPos();
        var candidateEntities = queryEntitiesInChunkBounds(chunk, world);

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Capturing entities for chunk {}, candidates: {}",
                    chunkPosition, candidateEntities.size());
        }

        var capturedEntities = filterAndSerializeEntities(candidateEntities, chunkPosition, world.getRegistryManager());
        delta.setEntities(capturedEntities);

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Entity capture complete for chunk {}: {} entities, dirty: {}",
                    chunkPosition, capturedEntities.size(), delta.isDirty());
        }
    }

    /**
     * Queries all entities within the chunk's bounding box.
     *
     * <p>
     * Creates a bounding box spanning the entire chunk from world bottom to
     * top, then queries all entities within that box. The query is broad and
     * returns candidates that require further filtering.
     *
     * <p>
     * <b>Thread Safety:</b> Must execute on server thread as world entity
     * queries are not thread-safe.
     *
     * @param chunk the chunk to query entities for
     * @param world the server world containing the entities
     * @return list of all entities within chunk bounds (unfiltered)
     */
    private static List<Entity> queryEntitiesInChunkBounds(WorldChunk chunk, ServerWorld world) {
        var chunkPosition = chunk.getPos();

        var chunkBounds = new Box(
                chunkPosition.getStartX(),
                world.getBottomY(),
                chunkPosition.getStartZ(),
                chunkPosition.getEndX() + 1.0,
                world.getHeight(),
                chunkPosition.getEndZ() + 1.0);

        return world.getEntitiesByClass(Entity.class, chunkBounds, entity -> true);
    }

    /**
     * Filters entities and serializes eligible ones to NBT.
     *
     * <p>
     * Applies filtering rules to exclude players, removed entities, passengers,
     * and out-of-bounds entities. Serializes remaining entities using Minecraft's
     * NBT write system.
     *
     * @param candidateEntities entities within chunk bounding box
     * @param chunkPosition     the chunk's position for coordinate validation
     * @return list of serialized NBT compounds for valid entities
     */
    private static List<NbtCompound> filterAndSerializeEntities(
            List<Entity> candidateEntities,
            ChunkPos chunkPosition,
            net.minecraft.registry.RegistryWrapper.WrapperLookup registryManager) {

        var capturedEntities = new ArrayList<NbtCompound>(
                Math.min(candidateEntities.size(), INITIAL_ENTITY_LIST_CAPACITY));

        for (var entity : candidateEntities) {
            if (shouldCaptureEntity(entity, chunkPosition)) {
                var nbt = serializeEntity(entity, registryManager);
                if (nbt != null && !nbt.isEmpty()) {
                    capturedEntities.add(nbt);
                }
            }
        }

        return capturedEntities;
    }

    /**
     * Serializes an entity to NBT format.
     *
     * <p>
     * Uses Minecraft's NBT write view system for proper serialization with
     * registry support. The resulting NBT contains all entity state including
     * position, rotation, custom name, effects, inventory, and type-specific data.
     *
     * @param entity the entity to serialize
     * @return the NBT compound, or null if serialization fails
     */
    private static NbtCompound serializeEntity(Entity entity,
            net.minecraft.registry.RegistryWrapper.WrapperLookup registryManager) {
        try {
            var writeView = NbtWriteView.create(ErrorReporter.EMPTY, registryManager);
            entity.saveData(writeView);
            NbtCompound nbt = writeView.getNbt();

            if (nbt != null && !nbt.contains("id")) {
                String id = net.minecraft.registry.Registries.ENTITY_TYPE.getId(entity.getType()).toString();
                nbt.putString("id", id);
            }

            return nbt;
        } catch (Exception error) {
            LOGGER.error("Failed to serialize entity {} at {}",
                    entity.getType().getTranslationKey(), entity.getEntityPos(), error);
            return null;
        }
    }

    /**
     * Determines if an entity should be captured for this chunk.
     *
     * <p>
     * Applies strict filtering rules to prevent data duplication and ensure
     * only appropriate entities are saved with the chunk:
     * <ol>
     * <li><b>Players:</b> Excluded (managed by player.dat files)</li>
     * <li><b>Removed entities:</b> Excluded (dead or despawned)</li>
     * <li><b>Passengers:</b> Excluded (saved with vehicle to prevent
     * duplication)</li>
     * <li><b>Coordinate check:</b> Must be physically within chunk bounds</li>
     * </ol>
     *
     * <p>
     * The coordinate check uses floor division and bit shifting for optimal
     * performance, which is more reliable than entity.getChunkPos() that may
     * return stale cached values.
     *
     * @param entity        the entity to evaluate
     * @param chunkPosition the position of the chunk being saved
     * @return true if entity should be captured, false otherwise
     */
    private static boolean shouldCaptureEntity(Entity entity, ChunkPos chunkPosition) {
        // Filter: Players managed separately
        if (entity instanceof PlayerEntity) {
            return false;
        }

        // Filter: Removed or dead entities
        if (entity.isRemoved()) {
            return false;
        }

        // Filter: Passengers saved with vehicle (prevents duplication)
        if (entity.hasVehicle()) {
            return false;
        }

        // Filter: Exact chunk coordinate validation
        return isEntityInChunk(entity, chunkPosition);
    }

    /**
     * Verifies an entity is physically within the chunk's coordinate bounds.
     *
     * <p>
     * Uses direct coordinate math with floor division (bit shift) to convert
     * entity world coordinates to chunk coordinates. This is more reliable than
     * Entity.getChunkPos() which may cache stale values.
     *
     * <p>
     * <b>Coordinate Conversion:</b>
     * 
     * <pre>
     * chunkX = floor(worldX / 16) = floor(worldX) >> 4
     * chunkZ = floor(worldZ / 16) = floor(worldZ) >> 4
     * </pre>
     *
     * @param entity        the entity to check
     * @param chunkPosition the chunk position to validate against
     * @return true if entity is within chunk bounds
     */
    private static boolean isEntityInChunk(Entity entity, ChunkPos chunkPosition) {
        int entityChunkX = MathHelper.floor(entity.getX()) >> CHUNK_COORDINATE_SHIFT;
        int entityChunkZ = MathHelper.floor(entity.getZ()) >> CHUNK_COORDINATE_SHIFT;

        return entityChunkX == chunkPosition.x && entityChunkZ == chunkPosition.z;
    }
}