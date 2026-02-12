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
 * - Uses chunk coordinate math instead of entity.getChunkPos()
 * - Batches entity queries on server thread
 * - Filters entities during world query to reduce iteration overhead
 * - Skips redundant checks early
 *
 * @author Liparakis
 * @version 1.0
 */
public final class ChunkEntityCapture {

    private ChunkEntityCapture() {
        // Utility class
    }

    /**
     * Captures all entities in the chunk and stores them in the delta.
     * Must be called on server thread.
     *
     * @param chunk The chunk being saved
     * @param delta The delta to store entity data
     * @param world The server world for entity queries
     */
    public static void captureAll(WorldChunk chunk, ChunkDelta delta, ServerWorld world) {
        ChunkPos chunkPos = chunk.getPos();

        if (Chunkis.LOGGER.isDebugEnabled()) {
            Chunkis.LOGGER.debug("Starting entity capture for chunk {}", chunkPos);
        }

        // Get and filter entities in a single pass
        List<Entity> entities = getEntitiesInChunk(world, chunkPos);

        // Pre-size list based on filtered results, capped at reasonable maximum
        List<NbtCompound> capturedEntities = new ArrayList<>(Math.min(entities.size(), 16));

        for (Entity entity : entities) {
            NbtCompound nbt = new NbtCompound();
            if (entity.saveNbt(nbt)) {
                capturedEntities.add(nbt);
            }
        }

        delta.setEntities(capturedEntities);

        if (Chunkis.LOGGER.isDebugEnabled()) {
            Chunkis.LOGGER.debug("Entity capture complete for chunk {}: {} entities captured, dirty: {}",
                    chunkPos, capturedEntities.size(), delta.isDirty());
        }
    }

    /**
     * Queries all capturable entities within the chunk bounds.
     * Applies filtering during the query for better performance.
     * Must execute on server thread.
     */
    private static List<Entity> getEntitiesInChunk(ServerWorld world, ChunkPos chunkPos) {
        Box chunkBox = new Box(
                chunkPos.getStartX(),
                world.getBottomY(),
                chunkPos.getStartZ(),
                chunkPos.getEndX() + 1.0,
                world.getBottomY() + world.getHeight(),
                chunkPos.getEndZ() + 1.0);

        // Filter during query to avoid second iteration
        return world.getEntitiesByClass(Entity.class, chunkBox,
                entity -> shouldCaptureEntity(entity, chunkPos));
    }

    /**
     * Determines if an entity should be captured for this chunk.
     * <p>
     * Filtering rules (applied in order of cheapest checks first):
     * 1. Skip players (managed separately)
     * 2. Skip removed entities
     * 3. Skip passengers (saved with their vehicle)
     * 4. Skip entities not physically in this chunk (prevent duplication)
     *
     * @param entity   Entity to check
     * @param chunkPos Position of the chunk being saved
     * @return true if entity should be captured
     */
    private static boolean shouldCaptureEntity(Entity entity, ChunkPos chunkPos) {
        // Rule 1: Players are managed separately by Minecraft
        // Cheapest check - instance check
        if (entity instanceof PlayerEntity) {
            return false;
        }

        // Rule 2: Don't save removed/dead entities
        // Fast state check
        if (entity.isRemoved()) {
            return false;
        }

        // Rule 3: Passengers are saved with their vehicle
        // This prevents duplication when vehicle+passenger cross chunk boundaries
        if (entity.hasVehicle()) {
            return false;
        }

        // Rule 4: Exact chunk coordinate check
        // Use bit shift for fast floor division: (int)(x / 16) == (int)x >> 4
        // This is more reliable than entity.getChunkPos() which may be stale
        int entityChunkX = MathHelper.floor(entity.getX()) >> 4;
        int entityChunkZ = MathHelper.floor(entity.getZ()) >> 4;

        return entityChunkX == chunkPos.x && entityChunkZ == chunkPos.z;
    }
}