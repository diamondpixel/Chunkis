package io.liparakis.chunkis.core;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

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
 *
 * @param <S> The BlockState type
 * @param <N> The NBT (or data tag) type for entities
 * @see BlockInstruction
 * @see Palette
 */
public final class ChunkDelta<S, N> {
    private static final int INITIAL_CAPACITY = 64;

    /**
     * Packed block instructions stored as long values for memory efficiency
     */
    private long[] packedInstructions;

    /**
     * Current number of block instructions
     */
    private int instructionCount;

    /**
     * Palette mapping BlockStates to compact integer IDs
     */
    private final Palette<S> blockPalette;

    /**
     * Map from packed position to instruction array index for O(1) lookups
     */
    private final Long2IntMap positionMap;

    /**
     * Lazily-initialized map of block entity data, keyed by packed position
     */
    private Long2ObjectMap<N> blockEntities;

    /**
     * List of global entity data
     */
    private List<N> entities;

    /**
     * Tracks whether this delta has unsaved changes
     */
    private boolean isDirty;

    /**
     * Tracks whether this delta was loaded from an older CIS version
     * and should be re-saved in the current version.
     */
    private boolean needsMigration;

    private final Predicate<S> isEmptyState;

    /**
     * Constructs a new empty ChunkDelta with default initial capacity.
     *
     * @param isEmptyState Predicate to check if a state is considered "empty" (e.g.
     *                     air)
     */
    public ChunkDelta(Predicate<S> isEmptyState) {
        this.packedInstructions = new long[INITIAL_CAPACITY];
        this.instructionCount = 0;
        this.blockPalette = new Palette<>();
        this.positionMap = new Long2IntOpenHashMap(INITIAL_CAPACITY);
        this.positionMap.defaultReturnValue(-1);
        this.entities = new ArrayList<>();
        this.isDirty = false;
        this.needsMigration = false;
        this.isEmptyState = isEmptyState;
    }

    /**
     * Constructs a new empty ChunkDelta with no empty-state check.
     * Used when the predicate is not available (e.g., during decoding).
     */
    public ChunkDelta() {
        this(s -> false); // Default: nothing is considered empty
    }

    // ==================== Block Changes ====================

    /**
     * Adds or updates a block change at the specified position and marks this delta
     * as dirty.
     */
    public void addBlockChange(int x, int y, int z, S newState) {
        addBlockChange(x, y, z, newState, true);
    }

    /**
     * Checks if a block change exists at the specified position.
     */
    public boolean hasBlockChange(int x, int y, int z) {
        final long posKey = BlockInstruction.packPos(x, y, z);
        return positionMap.containsKey(posKey);
    }

    /**
     * Adds or updates a block change at the specified position.
     */
    public void addBlockChange(int x, int y, int z, S newState, boolean markDirty) {
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
     * Updates an existing instruction in the packed array.
     *
     * @param x         Local X coordinate.
     * @param y         Local Y coordinate.
     * @param z         Local Z coordinate.
     * @param paletteId The palette index for the block state.
     * @param index     The index in the {@code packedInstructions} array.
     * @param markDirty Whether to mark the delta as dirty.
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
     * Adds a new instruction to the packed array.
     *
     * @param x         Local X coordinate.
     * @param y         Local Y coordinate.
     * @param z         Local Z coordinate.
     * @param paletteId The palette index for the block state.
     * @param posKey    The packed position key.
     * @param markDirty Whether to mark the delta as dirty.
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
     * Removes block entity data if the new state is considered "empty" (e.g., air).
     *
     * @param state  The new block state.
     * @param posKey The packed position key.
     */
    private void cleanupBlockEntityIfAir(S state, long posKey) {
        if (isEmptyState.test(state) && blockEntities != null) {
            blockEntities.remove(posKey);
        }
    }

    /**
     * Removes a block change instruction at the specified position.
     */
    public synchronized void removeBlockChange(int x, int y, int z) {
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
     * Removes the position key from the position map.
     *
     * @param posKey The packed position key.
     */
    private void removeFromPositionMap(long posKey) {
        positionMap.remove(posKey);
    }

    /**
     * Removes associated block entity data for the given position.
     *
     * @param posKey The packed position key.
     */
    private void removeBlockEntityData(long posKey) {
        if (blockEntities != null) {
            blockEntities.remove(posKey);
        }
    }

    /**
     * Removes an instruction by swapping it with the last element and shrinking the
     * array.
     * <p>
     * This avoids costly array copies (O(1) removal).
     *
     * @param index The index of the instruction to remove.
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
     * Ensures the instructions array has enough capacity for a new element.
     * Doubles capacity if needed.
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

    public void addBlockEntityData(int x, int y, int z, N nbt) {
        addBlockEntityData(x, y, z, nbt, true);
    }

    public void addBlockEntityData(int x, int y, int z, N nbt, boolean markDirty) {
        if (nbt == null) {
            return;
        }

        final long key = BlockInstruction.packPos(x, y, z);

        if (blockEntities == null) {
            blockEntities = new Long2ObjectOpenHashMap<>();
        }

        final N existing = blockEntities.get(key);
        if (nbt.equals(existing)) {
            return;
        }

        blockEntities.put(key, nbt);

        if (markDirty) {
            this.isDirty = true;
        }
    }

    public Long2ObjectMap<N> getBlockEntities() {
        return blockEntities == null ? Long2ObjectMaps.emptyMap() : blockEntities;
    }

    // ==================== Entities ====================

    public void setEntities(List<N> newEntities) {
        setEntities(newEntities, true);
    }

    public void setEntities(List<N> newEntities, boolean markDirty) {
        final List<N> safeEntities = newEntities == null ? Collections.emptyList() : newEntities;

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

    public List<N> getEntitiesList() {
        return entities;
    }

    // ==================== Queries ====================

    public synchronized List<BlockInstruction> getBlockInstructions() {
        final List<BlockInstruction> list = new ArrayList<>(instructionCount);
        for (int i = 0; i < instructionCount; i++) {
            list.add(BlockInstruction.fromPacked(packedInstructions[i]));
        }
        return list;
    }

    public Palette<S> getBlockPalette() {
        return blockPalette;
    }

    public boolean isEmpty() {
        return instructionCount == 0
                && (blockEntities == null || blockEntities.isEmpty())
                && entities.isEmpty();
    }

    // ==================== Dirty Flag ====================

    public boolean isDirty() {
        return isDirty;
    }

    public void markDirty() {
        this.isDirty = true;
    }

    public void markSaved() {
        this.isDirty = false;
        this.needsMigration = false;
    }

    public boolean needsMigration() {
        return needsMigration;
    }

    public void setNeedsMigration(boolean needsMigration) {
        this.needsMigration = needsMigration;
    }

    // ==================== Visitor ====================

    /**
     * Interface for visiting delta contents.
     */
    public interface DeltaVisitor<S, N> {
        void visitBlock(int x, int y, int z, S state);

        void visitBlockEntity(int x, int y, int z, N nbt);

        void visitEntity(N nbt);
    }

    public void accept(DeltaVisitor<S, N> visitor) {
        // 1. Visit block changes
        for (int i = 0; i < instructionCount; i++) {
            long packed = packedInstructions[i];
            int paletteIndex = (int) (packed >> 32);
            S state = blockPalette.get(paletteIndex);

            if (state == null) {
                continue;
            }

            int x = BlockInstruction.unpackX(packed);
            int y = BlockInstruction.unpackY(packed);
            int z = BlockInstruction.unpackZ(packed);

            visitor.visitBlock(x, y, z, state);
        }

        // 2. Visit block entities
        if (blockEntities != null && !blockEntities.isEmpty()) {
            for (Long2ObjectMap.Entry<N> entry : blockEntities.long2ObjectEntrySet()) {
                long pos = entry.getLongKey();
                int x = BlockInstruction.unpackX(pos);
                int y = BlockInstruction.unpackY(pos);
                int z = BlockInstruction.unpackZ(pos);

                visitor.visitBlockEntity(x, y, z, entry.getValue());
            }
        }

        // 3. Visit global entities
        if (!entities.isEmpty()) {
            for (N nbt : entities) {
                visitor.visitEntity(nbt);
            }
        }
    }
}
