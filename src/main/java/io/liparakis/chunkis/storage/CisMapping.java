package io.liparakis.chunkis.storage;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;

import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Optimized mapping system for block translation.
 * Uses Reference maps for Block objects to avoid String-based hashing in the
 * hot path.
 */
public class CisMapping {
    private static final Gson GSON = new Gson();

    // Map Block references directly to IDs (Identity-based comparison is fastest)
    private final Reference2IntMap<Block> blockToId = new Reference2IntOpenHashMap<>();
    private final Int2ObjectMap<Block> idToBlock = new Int2ObjectOpenHashMap<>();

    // Cache for Direction properties to avoid repeated state manager scanning
    private final Int2ObjectMap<Property<Direction>> directionPropertyCache = new Int2ObjectOpenHashMap<>();

    private final Path mappingFilePath;
    private int nextId = 0;
    private final Object lock = new Object();

    public CisMapping(Path mappingFile) throws IOException {
        this.mappingFilePath = mappingFile;
        blockToId.defaultReturnValue(-1); // Use -1 to detect missing mappings

        if (mappingFile.toFile().exists()) {
            try (FileReader reader = new FileReader(mappingFile.toFile())) {
                Map<String, Integer> map = GSON.fromJson(reader, new TypeToken<Map<String, Integer>>() {
                }.getType());
                if (map != null) {
                    for (Map.Entry<String, Integer> entry : map.entrySet()) {
                        Identifier id = Identifier.tryParse(entry.getKey());
                        if (id != null) {
                            Block block = Registries.BLOCK.get(id);
                            // Ensure we map AIR to 0 explicitly if present, or handle it
                            if (block != Blocks.AIR || entry.getKey().equals("minecraft:air")) {
                                int val = entry.getValue();
                                blockToId.put(block, val);
                                idToBlock.put(val, block);
                                cacheDirectionProperty(block, val);
                                if (val >= nextId)
                                    nextId = val + 1;
                            }
                        }
                    }
                }
            }
        }

        io.liparakis.chunkis.ChunkisMod.LOGGER.info("Chunkis Debug: Loaded global mappings: {} entries",
                blockToId.size());

        // Ensure AIR is always mapped to 0 if not present
        if (blockToId.getInt(Blocks.AIR) == -1) {
            registerBlock(Blocks.AIR, 0);
            if (nextId == 0)
                nextId = 1;
        }
    }

    /**
     * Pre-calculates which property controls 'facing' for a block ID to avoid
     * per-tick loops.
     */
    private void cacheDirectionProperty(Block block, int id) {
        for (Property<?> prop : block.getStateManager().getProperties()) {
            if (prop.getType() == Direction.class) {
                // Prioritize standard facing properties
                if (prop == Properties.FACING || prop == Properties.HORIZONTAL_FACING) {
                    directionPropertyCache.put(id, (Property<Direction>) prop);
                    return;
                }
                // Fallback for custom properties (like 'axis' or 'orientation')
                directionPropertyCache.put(id, (Property<Direction>) prop);
            }
        }
    }

    public int getBlockId(BlockState state) {
        Block block = state.getBlock();
        int id = blockToId.getInt(block);

        if (id != -1) {
            return id;
        }

        // Double-checked locking for new block registration
        synchronized (lock) {
            id = blockToId.getInt(block);
            if (id != -1)
                return id;

            id = nextId++;
            io.liparakis.chunkis.ChunkisMod.LOGGER.info("Chunkis Debug: Registering new block {} with ID {}", block,
                    id);
            registerBlock(block, id);
            save(); // Persist immediately to avoid desync

            return id;
        }
    }

    private void registerBlock(Block block, int id) {
        blockToId.put(block, id);
        idToBlock.put(id, block);
        cacheDirectionProperty(block, id);
    }

    private void save() {
        // Collect map for JSON
        Map<String, Integer> map = new java.util.HashMap<>();
        for (Int2ObjectMap.Entry<Block> entry : idToBlock.int2ObjectEntrySet()) {
            Identifier id = Registries.BLOCK.getId(entry.getValue());
            map.put(id.toString(), entry.getIntKey());
        }

        try (java.io.FileWriter writer = new java.io.FileWriter(mappingFilePath.toFile())) {
            GSON.toJson(map, writer);
            io.liparakis.chunkis.ChunkisMod.LOGGER.info("Chunkis Debug: Saved global_ids.json with {} entries",
                    map.size());
        } catch (Exception e) {
            io.liparakis.chunkis.ChunkisMod.LOGGER.error("Chunkis Debug: Failed to save mappings!", e);
        }
    }

    /**
     * Packs block state properties into a byte.
     * Bits 0-2: Facing (0-5) or 7 (None)
     * Bit 3: Half (Upper/Lower) or BedPart (Head/Foot)
     * Bit 4: Open (Boolean) or Hinge (Left/Right)
     * Bit 5: Powered (Boolean) or Occupied (Boolean)
     * Bit 6-7: Chest Type (Single/Left/Right)
     */
    public byte getPackedStateData(BlockState state) {
        byte data = 0;

        // 1. Connection Properties (Fences, Panes, Vines, Walls)
        // These blocks typically don't have FACING, so we use the lower bits
        // differently.
        boolean hasConnections = false;
        if (state.contains(Properties.NORTH) && state.contains(Properties.WEST)) {
            // Check for multiple connection props to avoid false positives (e.g. obscure
            // blocks)
            // But actually, just checking one is usually safe if we prioritize it for known
            // types.
            // Let's stick to checking existence of NORTH as the primary indicator for
            // Fences/Walls/Panes.
            hasConnections = true;
        } else if (state.contains(Properties.NORTH) || state.contains(Properties.SOUTH)
                || state.contains(Properties.EAST) || state.contains(Properties.WEST)) {
            // Fallback for blocks that might have subsets (e.g. Vines?)
            hasConnections = true;
        }

        if (hasConnections) {
            if (state.contains(Properties.NORTH) && state.get(Properties.NORTH))
                data |= 1;
            if (state.contains(Properties.SOUTH) && state.get(Properties.SOUTH))
                data |= (1 << 1);
            if (state.contains(Properties.EAST) && state.get(Properties.EAST))
                data |= (1 << 2);
            if (state.contains(Properties.WEST) && state.get(Properties.WEST))
                data |= (1 << 3);

            // Connectables (Fence, Wall, Pane) typically use WATERLOGGED.
            if (state.contains(Properties.WATERLOGGED) && state.get(Properties.WATERLOGGED))
                data |= (1 << 4);

            // Walls have "UP" property
            if (state.contains(Properties.UP) && state.get(Properties.UP))
                data |= (1 << 5);

            return data; // Early exit for connectables
        }

        // Standard Facing (Bits 0-2)
        int directionId = 7;
        if (state.contains(Properties.HORIZONTAL_FACING)) {
            directionId = state.get(Properties.HORIZONTAL_FACING).getId();
        } else if (state.contains(Properties.FACING)) {
            directionId = state.get(Properties.FACING).getId();
        } else {
            // Fallback search
            for (Map.Entry<Property<?>, Comparable<?>> entry : state.getEntries().entrySet()) {
                if (entry.getValue() instanceof Direction dir) {
                    directionId = dir.getId();
                    break;
                }
            }
        }
        data |= (directionId & 7);

        // 2. Common Properties (Packed into bits 3-7)

        /*
         * Packing Layouts (Fits in 1 Byte):
         * 
         * A) Connectables (Fences, Walls, Panes, Vines):
         * Bits 0-3: N/S/E/W Connections
         * Bit 4: Waterlogged
         * Bit 5: Up (Walls center post)
         * Bits 6-7: Unused / Available
         * 
         * B) Chests:
         * Bits 0-2: Facing (N/S/E/W)
         * Bit 3: Waterlogged (Hijacked 'Half' slot)
         * Bits 4-5: Chest Type (0=Single, 1=Left, 2=Right)
         * Bits 6-7: Unused / Available
         * 
         * C) Complex Blocks (Trapdoors, Stairs, Doors, etc):
         * Bits 0-2: Facing (4 directions) or Axis
         * Bit 3: Half (Top/Bottom) -> Combines with Facing for 8 positions
         * Bit 4: Open / Hinge
         * Bit 5: Powered / Occupied
         * Bit 6: Waterlogged (If supported)
         * Bit 7: Unused / Available
         *
         * D) Simple Blocks (Dirt, Stone):
         * Data = 0 (No intrinsic state packed)
         *
         * E) Block Entities (Chests, Furnaces, etc):
         * Inventory/Config data is stored in the separate NBT stream.
         * The packed byte only stores visual state (like Facing).
         */

        // Bit 3: Half/Part - OR Waterlogged for Chests
        boolean isChest = state.contains(Properties.CHEST_TYPE);

        if (isChest) {
            // Chests use Bis 4-5 for Type, Bit 0-2 for Facing.
            // Bit 3 for Waterlogged.
            if (state.contains(Properties.WATERLOGGED) && state.get(Properties.WATERLOGGED))
                data |= (1 << 3);
        } else {
            // Normal blocks handle Half/Part in Bit 3
            if (state.contains(Properties.DOUBLE_BLOCK_HALF)) {
                if (state.get(Properties.DOUBLE_BLOCK_HALF) == net.minecraft.block.enums.DoubleBlockHalf.UPPER)
                    data |= (1 << 3);
            } else if (state.contains(Properties.BED_PART)) {
                if (state.get(Properties.BED_PART) == net.minecraft.block.enums.BedPart.HEAD)
                    data |= (1 << 3);
            } else if (state.contains(Properties.BLOCK_HALF)) {
                if (state.get(Properties.BLOCK_HALF) == net.minecraft.block.enums.BlockHalf.TOP)
                    data |= (1 << 3);
            }
        }

        // Bit 4: Open/Hinge
        if (state.contains(Properties.OPEN)) {
            if (state.get(Properties.OPEN))
                data |= (1 << 4);
        } else if (state.contains(Properties.DOOR_HINGE)) {
            if (state.get(Properties.DOOR_HINGE) == net.minecraft.block.enums.DoorHinge.RIGHT)
                data |= (1 << 4);
        }

        // Bit 5: Powered / Occupied
        if (state.contains(Properties.POWERED)) {
            if (state.get(Properties.POWERED))
                data |= (1 << 5);
        } else if (state.contains(Properties.OCCUPIED)) {
            if (state.get(Properties.OCCUPIED))
                data |= (1 << 5);
        }

        // Bit 4-5: Chest Type (Dynamic reuse of bits)
        if (isChest) {
            int typeId = state.get(Properties.CHEST_TYPE).ordinal(); // Single=0, Left=1, Right=2
            data |= ((typeId & 3) << 4);
        } else {
            // Bit 6: Waterlogged (Non-Chest)
            if (state.contains(Properties.WATERLOGGED) && state.get(Properties.WATERLOGGED))
                data |= (1 << 6);
        }

        return data;
    }

    public BlockState getBlockState(int id, byte data) {
        Block block = idToBlock.get(id);
        if (block == null)
            return Blocks.AIR.getDefaultState();

        BlockState state = block.getDefaultState();

        // Detect if this is a Connectable block type based on properties
        boolean isConnectable = state.contains(Properties.NORTH) || state.contains(Properties.SOUTH)
                || state.contains(Properties.EAST) || state.contains(Properties.WEST);

        if (isConnectable) {
            // Unpack Connections (Bits 0-3)
            try {
                if (state.contains(Properties.NORTH))
                    state = state.with(Properties.NORTH, (data & 1) == 1);
                if (state.contains(Properties.SOUTH))
                    state = state.with(Properties.SOUTH, ((data >> 1) & 1) == 1);
                if (state.contains(Properties.EAST))
                    state = state.with(Properties.EAST, ((data >> 2) & 1) == 1);
                if (state.contains(Properties.WEST))
                    state = state.with(Properties.WEST, ((data >> 3) & 1) == 1);

                // Bit 4: Waterlogged (Connectables)
                if (((data >> 4) & 1) == 1 && state.contains(Properties.WATERLOGGED))
                    state = state.with(Properties.WATERLOGGED, true);

                // Bit 5: Up (Walls)
                if (((data >> 5) & 1) == 1 && state.contains(Properties.UP))
                    state = state.with(Properties.UP, true);
            } catch (Exception e) {
            }

            return state;
        }

        // Standard Block Unpacking

        // 1. Unpack Facing (Bits 0-2)
        int facingId = data & 7;
        if (facingId >= 0 && facingId < 6) {
            Direction dir = Direction.byId(facingId);
            Property<Direction> prop = directionPropertyCache.get(id);
            if (prop != null && prop.getValues().contains(dir)) {
                state = state.with(prop, dir);
            }
        }

        boolean isChest = state.contains(Properties.CHEST_TYPE);

        // 2. Unpack Extra Properties
        try {
            // Bit 3: Half/Part OR Waterlogged(Chest)
            if (((data >> 3) & 1) == 1) {
                if (isChest) {
                    if (state.contains(Properties.WATERLOGGED))
                        state = state.with(Properties.WATERLOGGED, true);
                } else {
                    if (state.contains(Properties.DOUBLE_BLOCK_HALF))
                        state = state.with(Properties.DOUBLE_BLOCK_HALF,
                                net.minecraft.block.enums.DoubleBlockHalf.UPPER);
                    else if (state.contains(Properties.BED_PART))
                        state = state.with(Properties.BED_PART, net.minecraft.block.enums.BedPart.HEAD);
                    else if (state.contains(Properties.BLOCK_HALF))
                        state = state.with(Properties.BLOCK_HALF, net.minecraft.block.enums.BlockHalf.TOP);
                }
            }

            // Bit 4: Open/Hinge
            if (((data >> 4) & 1) == 1 && !isChest) {
                if (state.contains(Properties.OPEN))
                    state = state.with(Properties.OPEN, true);
                else if (state.contains(Properties.DOOR_HINGE))
                    state = state.with(Properties.DOOR_HINGE, net.minecraft.block.enums.DoorHinge.RIGHT);
            }

            // Bit 5: Powered/Occupied
            if (((data >> 5) & 1) == 1 && !isChest) {
                if (state.contains(Properties.POWERED))
                    state = state.with(Properties.POWERED, true);
                else if (state.contains(Properties.OCCUPIED))
                    state = state.with(Properties.OCCUPIED, true);
            }

            // Bit 4-5: Chest Type
            if (isChest) {
                int typeId = (data >> 4) & 3;
                net.minecraft.block.enums.ChestType type = net.minecraft.block.enums.ChestType.values()[typeId % 3];
                state = state.with(Properties.CHEST_TYPE, type);
            } else {
                // Bit 6: Waterlogged (Non-Chest)
                if (((data >> 6) & 1) == 1 && state.contains(Properties.WATERLOGGED)) {
                    state = state.with(Properties.WATERLOGGED, true);
                }
            }

        } catch (Exception e) {
            // Ignore invalid property values
        }

        return state;
    }
}