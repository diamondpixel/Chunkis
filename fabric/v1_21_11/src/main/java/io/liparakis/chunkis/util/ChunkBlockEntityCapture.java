package io.liparakis.chunkis.util;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.storage.CisConstants;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Identifier;
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
            // 1.21.6: Use createNbt to serialize block entity data
            NbtCompound nbt = blockEntity.createNbt(registryManager);

            if (!nbt.contains("id")) {
                Identifier id = BlockEntityType.getId(blockEntity.getType());
                if (id != null) nbt.putString("id", id.toString());
            }

            if (nbt.isEmpty()) {
                continue; // Skip empty NBT
            }

            // Convert world coordinates to local chunk coordinates
            BlockPos worldPos = blockEntity.getPos();
            int localX = worldPos.getX() & CisConstants.COORD_MASK;
            int localY = worldPos.getY();
            int localZ = worldPos.getZ() & CisConstants.COORD_MASK;

            delta.addBlockEntityData(localX, localY, localZ, nbt);
        }
    }
}
