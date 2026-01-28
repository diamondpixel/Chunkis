package io.liparakis.chunkis.utils;

import io.liparakis.chunkis.core.ChunkDelta;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Handles block entity (chest, furnace, etc.) capture for chunk saving.
 * <p>
 * Performance notes:
 * - Iterates block entities only once per save
 * - Uses bitwise AND for local coordinate conversion (faster than modulo)
 * - Skips removed block entities early
 * - Reuses registry manager from chunk world
 */
public final class ChunkBlockEntityCapture {

    /**
     * Captures all block entities in the chunk and stores them in the delta.
     * <p>
     * This captures:
     * - Inventory contents (chests, furnaces, hoppers)
     * - Custom NBT data (signs, command blocks, spawners)
     * - Both player-placed and world-generated block entities
     *
     * @param chunk The chunk being saved
     * @param delta The delta to store block entity data
     */
    public void captureAll(WorldChunk chunk, ChunkDelta delta) {
        var blockEntities = chunk.getBlockEntities();
        if (blockEntities.isEmpty()) {
            return; // Early exit for chunks with no block entities
        }

        var registryManager = chunk.getWorld().getRegistryManager();

        for (BlockEntity blockEntity : blockEntities.values()) {
            // Skip removed/invalid block entities
            if (blockEntity.isRemoved()) {
                continue;
            }

            // Serialize block entity with registry info
            NbtCompound nbt = blockEntity.createNbtWithId(registryManager);
            if (nbt.isEmpty()) {
                continue; // Skip empty NBT
            }

            // Convert world coordinates to local chunk coordinates
            BlockPos worldPos = blockEntity.getPos();
            int localX = worldPos.getX() & 15; // Equivalent to % 16, but faster
            int localY = worldPos.getY();
            int localZ = worldPos.getZ() & 15;

            delta.addBlockEntityData(localX, localY, localZ, nbt);
        }
    }
}