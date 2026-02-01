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

/**
 * Handles entity capture for chunk saving in CIS format.
 * <p>
 * Performance optimizations:
 * - Reuses entity list to avoid allocation per chunk
 * - Uses chunk coordinate math instead of entity.getChunkPos()
 * - Batches entity queries on server thread
 * - Skips redundant checks early
 */
public final class ChunkEntityCapture {

    // Reused collection to minimize allocations during chunk saves
    private final List<NbtCompound> capturedEntities = new ArrayList<>(16);

    /**
     * Captures all entities in the chunk and stores them in the delta.
     * Must be called on server thread.
     *
     * @param chunk The chunk being saved
     * @param delta The delta to store entity data
     * @param world The server world for entity queries
     */
    public void captureAll(WorldChunk chunk, ChunkDelta delta, ServerWorld world) {
        capturedEntities.clear();

        ChunkPos chunkPos = chunk.getPos();
        List<Entity> entities = getEntitiesInChunk(chunk, world);

        if (Chunkis.LOGGER.isDebugEnabled()) {
            Chunkis.LOGGER.debug("Capturing entities for chunk {}, candidates: {}",
                    chunkPos, entities.size());
        }

        int capturedCount = 0;
        for (Entity entity : entities) {
            if (shouldCaptureEntity(entity, chunkPos)) {
                NbtCompound nbt = new NbtCompound();
                if (entity.saveNbt(nbt)) {
                    capturedEntities.add(nbt);
                    capturedCount++;
                }
            }
        }

        delta.setEntities(new ArrayList<>(capturedEntities)); // Defensive copy

        if (Chunkis.LOGGER.isDebugEnabled()) {
            Chunkis.LOGGER.debug("Entity capture complete for chunk {}: {} entities, dirty: {}",
                    chunkPos, capturedCount, delta.isDirty());
        }
    }

    /**
     * Queries all entities within the chunk bounds.
     * Must execute on server thread.
     */
    private List<Entity> getEntitiesInChunk(WorldChunk chunk, ServerWorld world) {
        ChunkPos pos = chunk.getPos();
        Box chunkBox = new Box(
                pos.getStartX(),
                world.getBottomY(),
                pos.getStartZ(),
                pos.getEndX() + 1.0,
                world.getTopY(),
                pos.getEndZ() + 1.0);

        // Server thread query - safe as mixin executes on server thread
        return world.getEntitiesByClass(Entity.class, chunkBox, entity -> true);
    }

    /**
     * Determines if an entity should be captured for this chunk.
     * <p>
     * Filtering rules:
     * 1. Skip players (managed separately)
     * 2. Skip removed entities
     * 3. Skip passengers (saved with their vehicle)
     * 4. Skip entities not physically in this chunk (prevent duplication)
     *
     * @param entity   Entity to check
     * @param chunkPos Position of the chunk being saved
     * @return true if entity should be captured
     */
    private boolean shouldCaptureEntity(Entity entity, ChunkPos chunkPos) {
        // Rule 1: Players are managed separately by Minecraft
        if (entity instanceof PlayerEntity) {
            return false;
        }

        // Rule 2: Don't save removed/dead entities
        if (entity.isRemoved()) {
            return false;
        }

        // Rule 3: Passengers are saved with their vehicle
        // This prevents duplication when vehicle+passenger cross chunk boundaries
        if (entity.hasVehicle()) {
            return false;
        }

        // Rule 4: Exact chunk coordinate check
        // Use floor division to convert world coords to chunk coords
        // This is more reliable than entity.getChunkPos() which may be stale
        int entityChunkX = MathHelper.floor(entity.getX()) >> 4;
        int entityChunkZ = MathHelper.floor(entity.getZ()) >> 4;

        return entityChunkX == chunkPos.x && entityChunkZ == chunkPos.z;
    }
}