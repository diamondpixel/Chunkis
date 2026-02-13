package io.liparakis.chunkis.util;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.ChunkDelta;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.nbt.NbtFloat;
import net.minecraft.nbt.NbtList;
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
import java.util.Objects;

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
 * @version 2.5
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

        // Use setEntities to ensure the delta is marked as dirty
        delta.setEntities(capturedEntities);

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Entity capture complete for chunk {}: {} entities, dirty: {}",
                    chunkPosition, capturedEntities.size(), delta.isDirty());
        }
    }

    /**
     * Queries all entities within the chunk's bounding box.
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
                world.getBottomY() + world.getHeight(),
                chunkPosition.getEndZ() + 1.0);

        return world.getEntitiesByClass(Entity.class, chunkBounds, entity -> true);
    }

    /**
     * Filters entities and serializes eligible ones to NBT.
     *
     * @param candidateEntities entities within chunk bounding box
     * @param chunkPosition     the chunk's position for coordinate validation
     * @param registryManager   the registry wrapper for serialization
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
     * Serializes an entity to NBT format using NbtWriteView API with fallback.
     *
     * @param entity          the entity to serialize
     * @param registryManager the registry wrapper for serialization
     * @return the NBT compound, or null if serialization fails
     */
    private static NbtCompound serializeEntity(Entity entity,
            net.minecraft.registry.RegistryWrapper.WrapperLookup registryManager) {
        try {
            var writeView = NbtWriteView.create(ErrorReporter.EMPTY, registryManager);
            entity.saveData(writeView);
            NbtCompound nbt = writeView.getNbt();

            if (nbt == null || nbt.isEmpty()) {
                LOGGER.warn("NbtWriteView produced empty NBT for {}, falling back to manual serialization",
                        entity.getType().getTranslationKey());
                return createManualEntityNbt(entity);
            }

            if (!nbt.contains("id")) {
                String id = net.minecraft.registry.Registries.ENTITY_TYPE.getId(entity.getType()).toString();
                nbt.putString("id", id);
            }

            return nbt;
        } catch (Exception error) {
            LOGGER.error("Failed to serialize entity {} at {}, trying manual fallback",
                    entity.getType().getTranslationKey(), entity.getPos(), error);
            return createManualEntityNbt(entity);
        }
    }

    /**
     * Creates minimal entity NBT manually when normal serialization fails.
     */
    private static NbtCompound createManualEntityNbt(Entity entity) {
        try {
            NbtCompound nbt = new NbtCompound();

            nbt.putString("id", net.minecraft.registry.Registries.ENTITY_TYPE
                    .getId(entity.getType())
                    .toString());

            NbtList listPos = new NbtList();
            var pos = entity.getPos();
            listPos.add(NbtDouble.of(pos.x));
            listPos.add(NbtDouble.of(pos.y));
            listPos.add(NbtDouble.of(pos.z));
            nbt.put("Pos", listPos);

            NbtList listMotion = new NbtList();
            var velocity = entity.getVelocity();
            listMotion.add(NbtDouble.of(velocity.x));
            listMotion.add(NbtDouble.of(velocity.y));
            listMotion.add(NbtDouble.of(velocity.z));
            nbt.put("Motion", listMotion);

            NbtList listRotation = new NbtList();
            listRotation.add(NbtFloat.of(entity.getYaw()));
            listRotation.add(NbtFloat.of(entity.getPitch()));
            nbt.put("Rotation", listRotation);

            var uuid = entity.getUuid();
            nbt.putIntArray("UUID", new int[] {
                    (int) (uuid.getMostSignificantBits() >> 32),
                    (int) uuid.getMostSignificantBits(),
                    (int) (uuid.getLeastSignificantBits() >> 32),
                    (int) uuid.getLeastSignificantBits()
            });

            if (entity.hasCustomName()) {
                nbt.putString("CustomName", Objects.requireNonNull(entity.getCustomName()).getString());
            }

            return nbt;
        } catch (Exception e) {
            LOGGER.error("Manual serialization also failed for {}", entity.getType().getTranslationKey(), e);
            return null;
        }
    }

    /**
     * Determines if an entity should be captured for this chunk.
     *
     * @param entity        the entity to evaluate
     * @param chunkPosition the position of the chunk being saved
     * @return true if entity should be captured, false otherwise
     */
    private static boolean shouldCaptureEntity(Entity entity, ChunkPos chunkPosition) {
        if (entity instanceof PlayerEntity) {
            return false;
        }

        if (entity.isRemoved()) {
            return false;
        }

        if (entity.hasVehicle()) {
            return false;
        }

        return isEntityInChunk(entity, chunkPosition);
    }

    /**
     * Verifies an entity is physically within the chunk's coordinate bounds.
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