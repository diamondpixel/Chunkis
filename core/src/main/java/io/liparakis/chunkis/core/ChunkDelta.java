// ============================================================================
// FILE: ChunkDelta.java (OPTIMIZED)
// ============================================================================

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
import java.util.Collections;
import java.util.List;

/**
 * High-performance storage for modified block data within a chunk.
 * <p>
 * This class maintains a compact representation of block changes, block
 * entities,
 * and entities within a Minecraft chunk. It uses primitive collections from
 * fastutil
 * to minimize garbage collection pressure and avoid boxing/unboxing overhead.
 * </p>
 * <p>
 * The storage mechanism uses:
 * <ul>
 * <li>A palette-based system to deduplicate BlockState references</li>
 * <li>Packed long values to store position and palette index efficiently</li>
 * <li>A position map for O(1) lookups and updates</li>
 * <li>Lazy initialization for block entities to save memory when unused</li>
 * </ul>
 * </p>
 * <p>
 * Thread safety: This class is not thread-safe and should be accessed from a
 * single thread.
 * </p>
 *
 * @see BlockInstruction
 * @see Palette
 */
public final class ChunkDelta {
    private static final int NBT_COMPOUND_TYPE = 10;
    private static final int NBT_LIST_TYPE = 9;
    private static final String INSTRUCTIONS_KEY = "Instructions";
    private static final String PALETTE_KEY = "Palette";
    private static final String BLOCK_ENTITIES_KEY = "BlockEntities";
    private static final String ENTITIES_KEY = "GlobalEntities";
    private static final int INITIAL_CAPACITY = 64;

    /** Packed block instructions stored as long values for memory efficiency */
    private long[] packedInstructions;

    /** Current number of block instructions */
    private int instructionCount;

    /** Palette mapping BlockStates to compact integer IDs */
    private final Palette<BlockState> blockPalette;

    /** Map from packed position to instruction array index for O(1) lookups */
    private final Long2IntMap positionMap;

    /** Lazily-initialized map of block entity data, keyed by packed position */
    private Long2ObjectMap<NbtCompound> blockEntities;

    /** List of global entity data */
    private List<NbtCompound> entities;

    /** Tracks whether this delta has unsaved changes */
    private boolean isDirty;

    /**
     * Constructs a new empty ChunkDelta with default initial capacity.
     */
    public ChunkDelta() {
        this.packedInstructions = new long[INITIAL_CAPACITY];
        this.instructionCount = 0;
        this.blockPalette = new Palette<>();
        this.positionMap = new Long2IntOpenHashMap(INITIAL_CAPACITY);
        this.positionMap.defaultReturnValue(-1);
        this.entities = new ArrayList<>();
        this.isDirty = false;
    }

    // ==================== Block Changes ====================

    /**
     * Adds or updates a block change at the specified position and marks this delta
     * as dirty.
     *
     * @param x        the x-coordinate within the chunk (0-15)
     * @param y        the y-coordinate (world height)
     * @param z        the z-coordinate within the chunk (0-15)
     * @param newState the new BlockState to set
     */
    public void addBlockChange(int x, int y, int z, BlockState newState) {
        addBlockChange(x, y, z, newState, true);
    }

    /**
     * Adds or updates a block change at the specified position.
     * <p>
     * If a block change already exists at this position, it will be updated.
     * If the new state is air, any associated block entity data will be removed.
     * </p>
     *
     * @param x         the x-coordinate within the chunk (0-15)
     * @param y         the y-coordinate (world height)
     * @param z         the z-coordinate within the chunk (0-15)
     * @param newState  the new BlockState to set, or null to ignore
     * @param markDirty whether to mark this delta as dirty after the change
     */
    public void addBlockChange(int x, int y, int z, BlockState newState, boolean markDirty) {
        if (newState == null) {
            return;
        }

        final int paletteId = blockPalette.getOrAdd(newState);
        final long posKey = BlockInstruction.packPos(x, y, z);
        final int existingIndex = positionMap.get(posKey);

        if (existingIndex != -1) {
            updateExistingInstruction(x, y, z, paletteId, existingIndex, markDirty);
        } else {
            addNewInstruction(x, y, z, paletteId, posKey, markDirty);
        }

        cleanupBlockEntityIfAir(newState, posKey);
    }

    /**
     * Updates an existing block instruction at the given index.
     *
     * @param x         the x-coordinate within the chunk
     * @param y         the y-coordinate
     * @param z         the z-coordinate within the chunk
     * @param paletteId the palette index for the new BlockState
     * @param index     the array index of the existing instruction
     * @param markDirty whether to mark this delta as dirty
     */
    private void updateExistingInstruction(int x, int y, int z, int paletteId, int index, boolean markDirty) {
        final long newInstruction = new BlockInstruction((byte) x, y, (byte) z, paletteId).pack();

        if (packedInstructions[index] == newInstruction) {
            return;
        }

        packedInstructions[index] = newInstruction;

        if (markDirty) {
            this.isDirty = true;
        }
    }

    /**
     * Adds a new block instruction to the internal array.
     *
     * @param x         the x-coordinate within the chunk
     * @param y         the y-coordinate
     * @param z         the z-coordinate within the chunk
     * @param paletteId the palette index for the BlockState
     * @param posKey    the packed position key
     * @param markDirty whether to mark this delta as dirty
     */
    private void addNewInstruction(int x, int y, int z, int paletteId, long posKey, boolean markDirty) {
        ensureCapacity();
        packedInstructions[instructionCount] = new BlockInstruction((byte) x, y, (byte) z, paletteId).pack();
        positionMap.put(posKey, instructionCount++);

        if (markDirty) {
            this.isDirty = true;
        }
    }

    /**
     * Removes block entity data if the block state is air.
     *
     * @param state  the BlockState to check
     * @param posKey the packed position key
     */
    private void cleanupBlockEntityIfAir(BlockState state, long posKey) {
        if (state.isAir() && blockEntities != null) {
            blockEntities.remove(posKey);
        }
    }

    /**
     * Removes a block change instruction at the specified position.
     * <p>
     * This is an O(1) operation that uses swap-with-last to maintain a dense array.
     * Also removes any associated block entity data.
     * </p>
     *
     * @param x the x-coordinate within the chunk (0-15)
     * @param y the y-coordinate (world height)
     * @param z the z-coordinate within the chunk (0-15)
     */
    public void removeBlockChange(int x, int y, int z) {
        final long posKey = BlockInstruction.packPos(x, y, z);
        final int index = positionMap.get(posKey);

        if (index == -1) {
            return;
        }

        removeFromPositionMap(posKey);
        removeBlockEntityData(posKey);
        swapWithLastAndShrink(index);

        this.isDirty = true;
    }

    /**
     * Removes the position from the position map.
     *
     * @param posKey the packed position key to remove
     */
    private void removeFromPositionMap(long posKey) {
        positionMap.remove(posKey);
    }

    /**
     * Removes block entity data at the specified position.
     *
     * @param posKey the packed position key
     */
    private void removeBlockEntityData(long posKey) {
        if (blockEntities != null) {
            blockEntities.remove(posKey);
        }
    }

    /**
     * Swaps the instruction at the given index with the last instruction and
     * decrements the count.
     * <p>
     * This maintains array density while achieving O(1) removal.
     * </p>
     *
     * @param index the index of the instruction to remove
     */
    private void swapWithLastAndShrink(int index) {
        instructionCount--;

        if (index == instructionCount) {
            return;
        }

        final long lastInstruction = packedInstructions[instructionCount];
        packedInstructions[index] = lastInstruction;

        final BlockInstruction lastIns = BlockInstruction.fromPacked(lastInstruction);
        final long lastPosKey = BlockInstruction.packPos(lastIns.x(), lastIns.y(), lastIns.z());
        positionMap.put(lastPosKey, index);
    }

    /**
     * Ensures the packed instructions array has sufficient capacity.
     * Doubles the array size when full.
     */
    private void ensureCapacity() {
        if (instructionCount < packedInstructions.length) {
            return;
        }

        final long[] newArray = new long[packedInstructions.length << 1];
        System.arraycopy(packedInstructions, 0, newArray, 0, instructionCount);
        packedInstructions = newArray;
    }

    // ==================== Block Entities ====================

    /**
     * Adds or updates block entity data at the specified position and marks this
     * delta as dirty.
     *
     * @param x   the x-coordinate within the chunk (0-15)
     * @param y   the y-coordinate (world height)
     * @param z   the z-coordinate within the chunk (0-15)
     * @param nbt the NBT data for the block entity
     */
    public void addBlockEntityData(int x, int y, int z, NbtCompound nbt) {
        addBlockEntityData(x, y, z, nbt, true);
    }

    /**
     * Adds or updates block entity data at the specified position.
     * <p>
     * Block entities are lazily initialized to save memory when not used.
     * If the NBT data is identical to existing data, no change is made.
     * </p>
     *
     * @param x         the x-coordinate within the chunk (0-15)
     * @param y         the y-coordinate (world height)
     * @param z         the z-coordinate within the chunk (0-15)
     * @param nbt       the NBT data for the block entity, or null/empty to ignore
     * @param markDirty whether to mark this delta as dirty after the change
     */
    public void addBlockEntityData(int x, int y, int z, NbtCompound nbt, boolean markDirty) {
        if (nbt == null || nbt.isEmpty()) {
            return;
        }

        final long key = BlockInstruction.packPos(x, y, z);

        if (blockEntities == null) {
            blockEntities = new Long2ObjectOpenHashMap<>();
        }

        final NbtCompound existing = blockEntities.get(key);
        if (nbt.equals(existing)) {
            return;
        }

        blockEntities.put(key, nbt);

        if (markDirty) {
            this.isDirty = true;
        }
    }

    /**
     * Returns a map of all block entity data.
     *
     * @return an immutable empty map if no block entities exist, otherwise the
     *         internal map
     */
    public Long2ObjectMap<NbtCompound> getBlockEntities() {
        return blockEntities == null ? Long2ObjectMaps.emptyMap() : blockEntities;
    }

    // ==================== Entities ====================

    /**
     * Sets the list of global entities and marks this delta as dirty if the list
     * differs.
     *
     * @param newEntities the new list of entity NBT data
     */
    public void setEntities(List<NbtCompound> newEntities) {
        setEntities(newEntities, true);
    }

    /**
     * Sets the list of global entities.
     * <p>
     * If markDirty is true and the new list differs from the current list,
     * the delta will be marked as dirty and a debug message will be logged.
     * </p>
     *
     * @param newEntities the new list of entity NBT data, or null for empty
     * @param markDirty   whether to mark this delta as dirty if the list changes
     */
    public void setEntities(List<NbtCompound> newEntities, boolean markDirty) {
        final List<NbtCompound> safeEntities = newEntities == null ? Collections.emptyList() : newEntities;

        if (!markDirty) {
            this.entities = new ArrayList<>(safeEntities);
            return;
        }

        if (this.entities.equals(safeEntities)) {
            return;
        }

        this.entities = new ArrayList<>(safeEntities);
        this.isDirty = true;
    }

    /**
     * Returns the list of global entities.
     *
     * @return the internal list of entity NBT data
     */
    public List<NbtCompound> getEntitiesList() {
        return entities;
    }

    // ==================== Queries ====================

    /**
     * Returns all block instructions as a list.
     * <p>
     * The returned list is a new ArrayList containing unpacked BlockInstruction
     * objects.
     * </p>
     *
     * @return a list of all block instructions
     */
    public List<BlockInstruction> getBlockInstructions() {
        final List<BlockInstruction> list = new ArrayList<>(instructionCount);
        for (int i = 0; i < instructionCount; i++) {
            list.add(BlockInstruction.fromPacked(packedInstructions[i]));
        }
        return list;
    }

    /**
     * Returns the block palette used for deduplicating BlockStates.
     *
     * @return the block palette
     */
    public Palette<BlockState> getBlockPalette() {
        return blockPalette;
    }

    /**
     * Checks whether this delta has any data.
     *
     * @return true if there are no block instructions, block entities, or entities
     */
    public boolean isEmpty() {
        return instructionCount == 0
                && (blockEntities == null || blockEntities.isEmpty())
                && entities.isEmpty();
    }

    // ==================== Dirty Flag ====================

    /**
     * Checks whether this delta has unsaved changes.
     *
     * @return true if the delta has been modified since the last save
     */
    public boolean isDirty() {
        return isDirty;
    }

    /**
     * Marks this delta as having unsaved changes.
     */
    public void markDirty() {
        this.isDirty = true;
    }

    /**
     * Marks this delta as saved, clearing the dirty flag.
     */
    public void markSaved() {
        this.isDirty = false;
    }

    // ==================== NBT Serialization ====================

    /**
     * Writes all delta data to the provided NBT compound.
     * <p>
     * This serializes:
     * <ul>
     * <li>Block instructions</li>
     * <li>Block palette</li>
     * <li>Block entities (if any)</li>
     * <li>Global entities (if any)</li>
     * </ul>
     * </p>
     *
     * @param tag the NBT compound to write to
     */
    public void writeNbt(NbtCompound tag) {
        writeInstructions(tag);
        writePalette(tag);
        writeBlockEntities(tag);
        writeEntities(tag);
    }

    /**
     * Writes block instructions to NBT.
     *
     * @param tag the NBT compound to write to
     */
    private void writeInstructions(NbtCompound tag) {
        final NbtList instructionList = new NbtList();

        for (int i = 0; i < instructionCount; i++) {
            final BlockInstruction ins = BlockInstruction.fromPacked(packedInstructions[i]);
            final NbtCompound node = new NbtCompound();
            node.putByte("x", ins.x());
            node.putInt("y", ins.y());
            node.putByte("z", ins.z());
            node.putInt("p", ins.paletteIndex());
            instructionList.add(node);
        }

        tag.put(INSTRUCTIONS_KEY, instructionList);
    }

    /**
     * Writes the block palette to NBT.
     *
     * @param tag the NBT compound to write to
     */
    private void writePalette(NbtCompound tag) {
        final NbtList paletteList = new NbtList();

        for (BlockState state : blockPalette.getAll()) {
            final BlockState safeState = state == null ? Blocks.AIR.getDefaultState() : state;
            paletteList.add(NbtHelper.fromBlockState(safeState));
        }

        tag.put(PALETTE_KEY, paletteList);
    }

    /**
     * Writes block entity data to NBT.
     *
     * @param tag the NBT compound to write to
     */
    private void writeBlockEntities(NbtCompound tag) {
        if (blockEntities == null || blockEntities.isEmpty()) {
            return;
        }

        final NbtList beList = new NbtList();

        for (Long2ObjectMap.Entry<NbtCompound> entry : blockEntities.long2ObjectEntrySet()) {
            final long pos = entry.getLongKey();
            final NbtCompound entryTag = new NbtCompound();

            entryTag.putByte("x", (byte) BlockInstruction.unpackX(pos));
            entryTag.putInt("y", BlockInstruction.unpackY(pos));
            entryTag.putByte("z", (byte) BlockInstruction.unpackZ(pos));
            entryTag.put("nbt", entry.getValue());

            beList.add(entryTag);
        }

        tag.put(BLOCK_ENTITIES_KEY, beList);
    }

    /**
     * Writes global entities to NBT.
     *
     * @param tag the NBT compound to write to
     */
    private void writeEntities(NbtCompound tag) {
        if (entities.isEmpty()) {
            return;
        }

        final NbtList entityList = new NbtList();
        entityList.addAll(entities);
        tag.put(ENTITIES_KEY, entityList);
    }

    /**
     * Reads all delta data from the provided NBT compound.
     * <p>
     * This deserializes:
     * <ul>
     * <li>Block palette (must be read first)</li>
     * <li>Block instructions</li>
     * <li>Block entities</li>
     * <li>Global entities</li>
     * </ul>
     * </p>
     *
     * @param tag the NBT compound to read from
     */
    public void readNbt(NbtCompound tag) {
        readPalette(tag);
        readInstructions(tag);
        readBlockEntities(tag);
        readEntities(tag);
    }

    /**
     * Reads the block palette from NBT.
     *
     * @param tag the NBT compound to read from
     */
    private void readPalette(NbtCompound tag) {
        final NbtList paletteList = tag.getList(PALETTE_KEY, NBT_COMPOUND_TYPE);
        final RegistryWrapper<net.minecraft.block.Block> registryLookup = Registries.BLOCK.getReadOnlyWrapper();

        for (int i = 0; i < paletteList.size(); i++) {
            blockPalette.getOrAdd(NbtHelper.toBlockState(registryLookup, paletteList.getCompound(i)));
        }
    }

    /**
     * Reads block instructions from NBT.
     * <p>
     * Clears existing instructions before reading.
     * Invalid instructions (referencing non-existent palette entries) are skipped.
     * </p>
     *
     * @param tag the NBT compound to read from
     */
    private void readInstructions(NbtCompound tag) {
        final NbtList instructionList = tag.getList(INSTRUCTIONS_KEY, NBT_COMPOUND_TYPE);
        final int count = instructionList.size();

        if (count > packedInstructions.length) {
            packedInstructions = new long[count];
        }

        positionMap.clear();
        instructionCount = 0;

        for (int i = 0; i < count; i++) {
            final NbtCompound note = instructionList.getCompound(i);
            final int x = note.getByte("x") & 0xF;
            final int y = note.getInt("y");
            final int z = note.getByte("z") & 0xF;
            final int paletteIndex = note.getInt("p");

            if (blockPalette.get(paletteIndex) == null) {
                continue;
            }

            final long posKey = BlockInstruction.packPos(x, y, z);
            packedInstructions[instructionCount] = new BlockInstruction((byte) x, y, (byte) z, paletteIndex).pack();
            positionMap.put(posKey, instructionCount++);
        }
    }

    /**
     * Reads block entity data from NBT.
     * <p>
     * Clears existing block entities before reading.
     * </p>
     *
     * @param tag the NBT compound to read from
     */
    private void readBlockEntities(NbtCompound tag) {
        if (!tag.contains(BLOCK_ENTITIES_KEY, NBT_LIST_TYPE)) {
            return;
        }

        final NbtList beList = tag.getList(BLOCK_ENTITIES_KEY, NBT_COMPOUND_TYPE);
        if (beList.isEmpty()) {
            return;
        }

        if (blockEntities == null) {
            blockEntities = new Long2ObjectOpenHashMap<>(beList.size());
        } else {
            blockEntities.clear();
        }

        for (int i = 0; i < beList.size(); i++) {
            final NbtCompound entry = beList.getCompound(i);
            final int x = entry.getByte("x") & 0xF;
            final int y = entry.getInt("y");
            final int z = entry.getByte("z") & 0xF;
            blockEntities.put(BlockInstruction.packPos(x, y, z), entry.getCompound("nbt"));
        }
    }

    /**
     * Reads global entities from NBT.
     * <p>
     * Clears existing entities before reading.
     * </p>
     *
     * @param tag the NBT compound to read from
     */
    private void readEntities(NbtCompound tag) {
        this.entities.clear();

        if (!tag.contains(ENTITIES_KEY, NBT_LIST_TYPE)) {
            return;
        }

        final NbtList entityList = tag.getList(ENTITIES_KEY, NBT_COMPOUND_TYPE);
        for (int i = 0; i < entityList.size(); i++) {
            this.entities.add(entityList.getCompound(i));
        }
    }
}