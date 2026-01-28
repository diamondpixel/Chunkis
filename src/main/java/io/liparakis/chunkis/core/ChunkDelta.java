package io.liparakis.chunkis.core;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryWrapper;

import java.util.ArrayList;
import java.util.List;

/**
 * High-performance storage for modified block data within a chunk.
 * Optimized with primitive collections to prevent GC pressure and casting
 * errors.
 */
public class ChunkDelta {
    private static final int NBT_COMPOUND_TYPE = 10;
    private static final String INSTRUCTIONS_KEY = "Instructions";
    private static final String PALETTE_KEY = "Palette";
    private static final String BLOCK_ENTITIES_KEY = "BlockEntities";
    private static final int INITIAL_CAPACITY = 64;

    private long[] packedInstructions = new long[INITIAL_CAPACITY];
    private int instructionCount = 0;

    private final Palette<BlockState> blockPalette = new Palette<>();

    // Primitive map to track position -> index in packedInstructions
    private final Long2IntMap positionMap;

    // Primitive map for Block Entity NBT data
    private Long2ObjectMap<NbtCompound> blockEntities;

    private boolean isDirty = false;

    public ChunkDelta() {
        this.positionMap = new Long2IntOpenHashMap(INITIAL_CAPACITY);
        this.positionMap.defaultReturnValue(-1); // Crucial for performance checks
    }

    public void addBlockChange(int x, int y, int z, BlockState newState) {
        addBlockChange(x, y, z, newState, true);
    }

    public void addBlockChange(int x, int y, int z, BlockState newState, boolean markDirty) {
        int paletteId = blockPalette.getOrAdd(newState);
        long posKey = BlockInstruction.packPos(x, y, z);

        int existingIndex = positionMap.get(posKey);

        if (existingIndex != -1) {
            // Update existing change
            long newInstruction = new BlockInstruction((byte) x, y, (byte) z, paletteId).pack();
            if (packedInstructions[existingIndex] == newInstruction) {
                return;
            }
            packedInstructions[existingIndex] = newInstruction;
        } else {
            // Add new change
            ensureCapacity();
            packedInstructions[instructionCount] = new BlockInstruction((byte) x, y, (byte) z, paletteId).pack();
            positionMap.put(posKey, instructionCount++);
        }

        // Clean up Block Entities if the block is now AIR
        if (newState.isAir() && blockEntities != null) {
            blockEntities.remove(posKey);
        }

        if (markDirty) {
            isDirty = true;
        }
    }

    public void addBlockEntityData(int x, int y, int z, NbtCompound nbt) {
        addBlockEntityData(x, y, z, nbt, true);
    }

    public void addBlockEntityData(int x, int y, int z, NbtCompound nbt, boolean markDirty) {
        if (nbt == null || nbt.isEmpty())
            return;

        long key = BlockInstruction.packPos(x, y, z);
        if (blockEntities == null) {
            blockEntities = new Long2ObjectOpenHashMap<>();
        }

        NbtCompound existing = blockEntities.get(key);
        if (!nbt.equals(existing)) {
            blockEntities.put(key, nbt);
            if (markDirty) {
                this.isDirty = true;
            }
        }
    }

    public NbtCompound getBlockEntityData(long packedPos) {
        return blockEntities == null ? null : blockEntities.get(packedPos);
    }

    /**
     * Returns the fastutil-specific map to ensure CisEncoder can iterate without
     * boxing.
     */
    public Long2ObjectMap<NbtCompound> getBlockEntities() {
        return blockEntities == null ? Long2ObjectMaps.emptyMap() : blockEntities;
    }

    public List<BlockInstruction> getBlockInstructions() {
        List<BlockInstruction> list = new ArrayList<>(instructionCount);
        for (int i = 0; i < instructionCount; i++) {
            list.add(BlockInstruction.fromPacked(packedInstructions[i]));
        }
        return list;
    }

    public Palette<BlockState> getBlockPalette() {
        return blockPalette;
    }

    public boolean isDirty() {
        return isDirty;
    }

    public void markDirty() {
        this.isDirty = true;
    }

    public void markSaved() {
        this.isDirty = false;
    }

    public int size() {
        return instructionCount;
    }

    public boolean isEmpty() {
        return instructionCount == 0 && (blockEntities == null || blockEntities.isEmpty())
                && (entities == null || entities.isEmpty());
    }

    private List<NbtCompound> entities = new ArrayList<>();

    public void setEntities(List<NbtCompound> newEntities) {
        setEntities(newEntities, true);
    }

    public void setEntities(List<NbtCompound> newEntities, boolean markDirty) {
        if (newEntities == null) {
            newEntities = new ArrayList<>();
        }

        if (!markDirty) {
            // restoration or silent update: force update baseline
            this.entities = new ArrayList<>(newEntities);
            return;
        }

        // Fuzzy Dirty Check REVERTED: Using strict equality to debug "vanishing" issue
        // We will re-enable optimization once stability is confirmed.
        if (!this.entities.equals(newEntities)) {
            io.liparakis.chunkis.ChunkisMod.LOGGER.info(
                    "Chunkis Debug: Delta Dirty! Entity count changed or data modified. Old: {}, New: {}",
                    this.entities.size(), newEntities.size());
            this.entities = new ArrayList<>(newEntities); // Defensive copy
            this.isDirty = true;
        }
    }

    public List<NbtCompound> getEntitiesList() {
        return entities;
    }

    private void ensureCapacity() {
        if (instructionCount == packedInstructions.length) {
            long[] newArray = new long[packedInstructions.length << 1];
            System.arraycopy(packedInstructions, 0, newArray, 0, instructionCount);
            packedInstructions = newArray;
        }
    }

    // ==================== NBT Serialization ====================

    private static final String ENTITIES_KEY = "GlobalEntities";

    public void writeNbt(NbtCompound tag) {
        // 1. Instructions
        NbtList instructionList = new NbtList();
        for (int i = 0; i < instructionCount; i++) {
            BlockInstruction ins = BlockInstruction.fromPacked(packedInstructions[i]);
            NbtCompound node = new NbtCompound();
            node.putByte("x", ins.x());
            node.putInt("y", ins.y());
            node.putByte("z", ins.z());
            node.putInt("p", ins.paletteIndex());
            instructionList.add(node);
        }
        tag.put(INSTRUCTIONS_KEY, instructionList);

        // 2. Palette
        NbtList paletteList = new NbtList();
        for (BlockState state : blockPalette.getAll()) {
            paletteList.add(NbtHelper.fromBlockState(state == null ? Blocks.AIR.getDefaultState() : state));
        }
        tag.put(PALETTE_KEY, paletteList);

        // 3. Block Entities
        if (blockEntities != null && !blockEntities.isEmpty()) {
            NbtList beList = new NbtList();
            // High-performance entry iteration
            for (Long2ObjectMap.Entry<NbtCompound> entry : blockEntities.long2ObjectEntrySet()) {
                long pos = entry.getLongKey();
                NbtCompound entryTag = new NbtCompound();
                entryTag.putByte("x", (byte) BlockInstruction.unpackX(pos));
                entryTag.putInt("y", BlockInstruction.unpackY(pos));
                entryTag.putByte("z", (byte) BlockInstruction.unpackZ(pos));
                entryTag.put("nbt", entry.getValue());
                beList.add(entryTag);
            }
            tag.put(BLOCK_ENTITIES_KEY, beList);
        }

        // 4. Global Entities (Mobs, Items)
        if (!entities.isEmpty()) {
            NbtList entityList = new NbtList();
            for (NbtCompound entityNbt : entities) {
                entityList.add(entityNbt);
            }
            tag.put(ENTITIES_KEY, entityList);
        }
    }

    public void readNbt(NbtCompound tag) {
        readPalette(tag);

        NbtList instructionList = tag.getList(INSTRUCTIONS_KEY, NBT_COMPOUND_TYPE);
        int count = instructionList.size();

        if (count > packedInstructions.length) {
            packedInstructions = new long[count];
        }

        positionMap.clear();
        instructionCount = 0;

        for (int i = 0; i < count; i++) {
            NbtCompound note = instructionList.getCompound(i);
            int x = note.getByte("x") & 0xF;
            int y = note.getInt("y");
            int z = note.getByte("z") & 0xF;
            int paletteIndex = note.getInt("p");

            if (blockPalette.get(paletteIndex) != null) {
                long posKey = BlockInstruction.packPos(x, y, z);
                packedInstructions[instructionCount] = new BlockInstruction((byte) x, y, (byte) z, paletteIndex).pack();
                positionMap.put(posKey, instructionCount++);
            }
        }

        if (tag.contains(BLOCK_ENTITIES_KEY, 9)) {
            NbtList beList = tag.getList(BLOCK_ENTITIES_KEY, NBT_COMPOUND_TYPE);
            if (!beList.isEmpty()) {
                if (blockEntities == null)
                    blockEntities = new Long2ObjectOpenHashMap<>(beList.size());
                else
                    blockEntities.clear();

                for (int i = 0; i < beList.size(); i++) {
                    NbtCompound entry = beList.getCompound(i);
                    int x = entry.getByte("x") & 0xF;
                    int y = entry.getInt("y");
                    int z = entry.getByte("z") & 0xF;
                    blockEntities.put(BlockInstruction.packPos(x, y, z), entry.getCompound("nbt"));
                }
            }
        }

        if (tag.contains(ENTITIES_KEY, 9)) {
            NbtList entityList = tag.getList(ENTITIES_KEY, NBT_COMPOUND_TYPE);
            this.entities.clear();
            for (int i = 0; i < entityList.size(); i++) {
                this.entities.add(entityList.getCompound(i));
            }
        } else {
            this.entities.clear();
        }
    }

    private void readPalette(NbtCompound tag) {
        NbtList paletteList = tag.getList(PALETTE_KEY, NBT_COMPOUND_TYPE);
        RegistryWrapper<net.minecraft.block.Block> registryLookup = Registries.BLOCK.getReadOnlyWrapper();

        for (int i = 0; i < paletteList.size(); i++) {
            blockPalette.getOrAdd(NbtHelper.toBlockState(registryLookup, paletteList.getCompound(i)));
        }
    }
}