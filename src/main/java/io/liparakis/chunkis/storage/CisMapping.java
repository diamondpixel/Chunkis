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

    public CisMapping(Path mappingFile) throws IOException {
        blockToId.defaultReturnValue(-1); // Use -1 to detect missing mappings

        if (!mappingFile.toFile().exists())
            return;

        try (FileReader reader = new FileReader(mappingFile.toFile())) {
            Map<String, Integer> map = GSON.fromJson(reader, new TypeToken<Map<String, Integer>>() {
            }.getType());
            if (map != null) {
                for (Map.Entry<String, Integer> entry : map.entrySet()) {
                    Identifier id = Identifier.tryParse(entry.getKey());
                    if (id != null) {
                        Block block = Registries.BLOCK.get(id);
                        if (block != Blocks.AIR || entry.getKey().equals("minecraft:air")) {
                            blockToId.put(block, (int) entry.getValue());
                            idToBlock.put((int) entry.getValue(), block);
                            cacheDirectionProperty(block, entry.getValue());
                        }
                    }
                }
            }
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
        int id = blockToId.getInt(state.getBlock());
        if (id == -1) {
            // Fallback: If not found, log strictly once (to avoid spam) and return AIR or
            // 0.
            // For now, assuming 0 is always AIR or safe.
            // Ideally we find the ID for Blocks.AIR.
            return blockToId.getInt(Blocks.AIR);
        }
        return id;
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

        // 1. Facing (3 bits)
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
        // Half (Doors, Stairs) / Bed Part
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

        // Open / Hinge / Short value properties
        if (state.contains(Properties.OPEN)) {
            if (state.get(Properties.OPEN))
                data |= (1 << 4);
        } else if (state.contains(Properties.DOOR_HINGE)) {
            if (state.get(Properties.DOOR_HINGE) == net.minecraft.block.enums.DoorHinge.RIGHT)
                data |= (1 << 4);
        }

        // Powered / Occupied
        if (state.contains(Properties.POWERED)) {
            if (state.get(Properties.POWERED))
                data |= (1 << 5);
        } else if (state.contains(Properties.OCCUPIED)) {
            if (state.get(Properties.OCCUPIED))
                data |= (1 << 5);
        }

        // Chest Type (2 bits: 0=Single, 1=Left, 2=Right)
        if (state.contains(Properties.CHEST_TYPE)) {
            int typeId = state.get(Properties.CHEST_TYPE).ordinal(); // Single=0, Left=1, Right=2
            data |= ((typeId & 3) << 6);
        }

        return data;
    }

    public BlockState getBlockState(int id, byte data) {
        Block block = idToBlock.get(id);
        if (block == null)
            return Blocks.AIR.getDefaultState();

        BlockState state = block.getDefaultState();

        // 1. Unpack Facing
        int facingId = data & 7;
        if (facingId >= 0 && facingId < 6) {
            Direction dir = Direction.byId(facingId);
            Property<Direction> prop = directionPropertyCache.get(id);
            if (prop != null && prop.getValues().contains(dir)) {
                state = state.with(prop, dir);
            }
        }

        // 2. Unpack Extra Properties using specific known properties
        // Only apply if the block actually has the property to avoid crashes
        try {
            // Bit 3: Half/Part
            if (((data >> 3) & 1) == 1) {
                if (state.contains(Properties.DOUBLE_BLOCK_HALF))
                    state = state.with(Properties.DOUBLE_BLOCK_HALF, net.minecraft.block.enums.DoubleBlockHalf.UPPER);
                else if (state.contains(Properties.BED_PART))
                    state = state.with(Properties.BED_PART, net.minecraft.block.enums.BedPart.HEAD);
                else if (state.contains(Properties.BLOCK_HALF))
                    state = state.with(Properties.BLOCK_HALF, net.minecraft.block.enums.BlockHalf.TOP);
            }

            // Bit 4: Open/Hinge
            if (((data >> 4) & 1) == 1) {
                if (state.contains(Properties.OPEN))
                    state = state.with(Properties.OPEN, true);
                else if (state.contains(Properties.DOOR_HINGE))
                    state = state.with(Properties.DOOR_HINGE, net.minecraft.block.enums.DoorHinge.RIGHT);
            }

            // Bit 5: Powered/Occupied
            if (((data >> 5) & 1) == 1) {
                if (state.contains(Properties.POWERED))
                    state = state.with(Properties.POWERED, true);
                else if (state.contains(Properties.OCCUPIED))
                    state = state.with(Properties.OCCUPIED, true);
            }

            // Bit 6-7: Chest Type
            if (state.contains(Properties.CHEST_TYPE)) {
                int typeId = (data >> 6) & 3;
                net.minecraft.block.enums.ChestType type = net.minecraft.block.enums.ChestType.values()[typeId % 3];
                state = state.with(Properties.CHEST_TYPE, type);
            }

        } catch (Exception e) {
            // Ignore invalid property values that might come from old/corrupt data
        }

        return state;
    }
}