package io.liparakis.chunkis.util;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.storage.CisConstants;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;

/**
 * Handles block entity capture during chunk persistence.
 *
 * <p>
 * Captures all block entities (chests, furnaces, signs, spawners, etc.) from
 * a chunk and serializes them into delta storage. This preserves both vanilla
 * worldgen block entities and player-placed modifications.
 *
 * <p>
 * <b>Performance Optimizations:</b>
 * <ul>
 * <li>Single-pass iteration over block entities (O(n))</li>
 * <li>Bitwise AND for local coordinate conversion (faster than modulo)</li>
 * <li>Early exit for removed/invalid block entities</li>
 * <li>Cached registry manager (no repeated lookups)</li>
 * </ul>
 *
 * <p>
 * <b>Thread Safety:</b> Must be called on server thread as it accesses
 * chunk world state and block entity data.
 *
 * @author Liparakis
 * @version 2.0
 */
public final class ChunkBlockEntityCapture {

    private static final Logger LOGGER = Chunkis.LOGGER;

    private ChunkBlockEntityCapture() {
        throw new AssertionError("Utility class cannot be instantiated");
    }

    /**
     * Captures all block entities from a chunk into its delta.
     *
     * <p>
     * Serializes each block entity to NBT format, preserving:
     * <ul>
     * <li>Inventory contents (chests, furnaces, hoppers, shulker boxes)</li>
     * <li>Custom data (signs, command blocks, spawners, lecterns)</li>
     * <li>State information (brewing stands, beacons, comparators)</li>
     * </ul>
     *
     * <p>
     * Skips removed or invalid block entities. Ensures all NBT compounds
     * contain the required "id" field for proper deserialization during
     * restoration.
     *
     * @param chunk the chunk to capture block entities from
     * @param delta the delta to store serialized block entity data
     */
    public static void captureAll(WorldChunk chunk, ChunkDelta<?, NbtCompound> delta) {
        var blockEntities = chunk.getBlockEntities();

        if (blockEntities.isEmpty()) {
            return; // No block entities to capture
        }

        var registryManager = chunk.getWorld().getRegistryManager();

        for (var blockEntity : blockEntities.values()) {
            captureBlockEntity(blockEntity, registryManager, delta);
        }
    }

    /**
     * Captures a single block entity and adds it to the delta.
     *
     * <p>
     * Validates the block entity, serializes it to NBT, ensures proper ID field,
     * and stores it with local chunk coordinates.
     *
     * @param blockEntity     the block entity to capture
     * @param registryManager the registry wrapper for NBT serialization
     * @param delta           the delta to store the serialized data
     */
    private static void captureBlockEntity(
            BlockEntity blockEntity,
            RegistryWrapper.WrapperLookup registryManager,
            ChunkDelta<?, NbtCompound> delta) {

        if (blockEntity.isRemoved()) {
            return; // Skip removed/invalid block entities
        }

        var nbt = serializeBlockEntity(blockEntity, registryManager);

        if (nbt == null || nbt.isEmpty()) {
            return; // Nothing to save
        }

        storeBlockEntityInDelta(blockEntity.getPos(), nbt, delta);
    }

    /**
     * Serializes a block entity to NBT format with proper ID field.
     *
     * <p>
     * Uses the chunk world's registry manager for proper serialization.
     * Ensures the NBT contains the required "id" field by adding it if missing.
     * This ID is critical for Minecraft's deserialization system.
     *
     * @param blockEntity     the block entity to serialize
     * @param registryManager the registry wrapper for serialization
     * @return the NBT compound, or null if type ID is missing
     */
    private static NbtCompound serializeBlockEntity(
            BlockEntity blockEntity,
            RegistryWrapper.WrapperLookup registryManager) {

        var nbt = blockEntity.createNbt(registryManager);

        if (!nbt.contains("id")) {
            var typeId = BlockEntityType.getId(blockEntity.getType());

            if (typeId == null) {
                LOGGER.warn("Block entity at {} has unregistered type: {}",
                        blockEntity.getPos(), blockEntity.getType());
                return null;
            }

            nbt.putString("id", typeId.toString());
        }

        return nbt;
    }

    /**
     * Stores a block entity's NBT data in the delta using local chunk coordinates.
     *
     * <p>
     * Converts world coordinates to local chunk coordinates (0-15 for X/Z)
     * using bitwise AND for optimal performance.
     *
     * @param worldPosition the absolute block position
     * @param nbt           the serialized block entity NBT
     * @param delta         the delta to store the data in
     */
    private static void storeBlockEntityInDelta(
            BlockPos worldPosition,
            NbtCompound nbt,
            ChunkDelta<?, NbtCompound> delta) {

        int localX = worldPosition.getX() & CisConstants.COORD_MASK;
        int localY = worldPosition.getY();
        int localZ = worldPosition.getZ() & CisConstants.COORD_MASK;

        delta.addBlockEntityData(localX, localY, localZ, nbt);
    }
}