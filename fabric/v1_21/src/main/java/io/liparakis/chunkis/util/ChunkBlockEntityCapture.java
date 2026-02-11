package io.liparakis.chunkis.util;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.storage.CisConstants;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Map;

/**
 * Handles block entity (chest, furnace, etc.) capture for chunk saving.
 * <p>
 * Performance notes:
 * - Iterates block entities only once per save
 * - Uses bitwise AND for local coordinate conversion (faster than modulo)
 * - Skips removed block entities early
 * - Caches registry manager to avoid repeated world access
 *
 * @author Liparakis
 * @version 1.0
 */
public final class ChunkBlockEntityCapture {

    private ChunkBlockEntityCapture() {
        // Utility class
    }

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
    public static void captureAll(WorldChunk chunk, ChunkDelta delta) {
        Map<BlockPos, BlockEntity> blockEntities = chunk.getBlockEntities();
        if (blockEntities.isEmpty()) {
            return; // Early exit for chunks with no block entities
        }

        RegistryWrapper.WrapperLookup registryManager = chunk.getWorld().getRegistryManager();

        for (BlockEntity blockEntity : blockEntities.values()) {
            // Skip removed/invalid block entities early
            if (blockEntity.isRemoved()) {
                continue;
            }

            // Get position before serialization for fail-fast on empty NBT
            BlockPos worldPos = blockEntity.getPos();

            // Serialize block entity with registry info
            NbtCompound nbt = blockEntity.createNbtWithId(registryManager);
            if (nbt.isEmpty()) {
                continue; // Skip null or empty NBT
            }

            // Convert world coordinates to local chunk coordinates using bitwise AND
            // Equivalent to: x % 16, z % 16 (but faster for positive coords)
            int localX = worldPos.getX() & CisConstants.COORD_MASK;
            int localY = worldPos.getY();
            int localZ = worldPos.getZ() & CisConstants.COORD_MASK;

            delta.addBlockEntityData(localX, localY, localZ, nbt);
        }
    }
}