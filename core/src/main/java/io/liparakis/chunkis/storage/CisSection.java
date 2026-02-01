package io.liparakis.chunkis.storage;

import java.util.Arrays;

/**
 * Memory-efficient storage for 16x16x16 block states with adaptive compression.
 * Automatically switches between sparse and dense storage modes based on
 * occupancy.
 * Optimized for branch prediction and minimal garbage collection pressure.
 *
 * @param <S> the type representing a block state
 */
public final class CisSection<S> {
    private static final int VOLUME = 4096;
    private static final int INITIAL_SPARSE_CAPACITY = 4;
    private static final int COORD_MASK = 0xFFFF;

    /**
     * Section is empty (default state).
     */
    public static final byte MODE_EMPTY = 0;

    /**
     * Section stores blocks in sparse arrays (key-value pairs).
     */
    public static final byte MODE_SPARSE = 1;

    /**
     * Section stores blocks in a flat array representing the full 16x16x16 volume.
     */
    public static final byte MODE_DENSE = 2;

    /**
     * The current storage mode (EMPTY, SPARSE, or DENSE).
     */
    public byte mode = MODE_EMPTY;

    /**
     * Packed coordinate keys (sparse mode).
     */
    public short[] sparseKeys;

    /**
     * Block states associated with keys (sparse mode).
     */
    public Object[] sparseValues;

    /**
     * Number of blocks currently in sparse storage.
     */
    public int sparseSize;

    /**
     * Flat array of block states (dense mode).
     */
    public Object[] denseBlocks;

    /**
     * Number of non-air blocks in dense storage.
     */
    int denseCount;

    /**
     * Creates a new empty section.
     */
    public CisSection() {
    }

    /**
     * Sets a block at the given local coordinates within the section.
     *
     * @param x     local X coordinate (0-15)
     * @param y     local Y coordinate (0-15)
     * @param z     local Z coordinate (0-15)
     * @param state the block state to set, or null for air
     */
    public void setBlock(int x, int y, int z, S state) {
        short key = packCoordinate(x, y, z);
        boolean isAir = isAirOrNull(state);

        switch (mode) {
            case MODE_DENSE:
                setBlockDense(key, state, isAir);
                break;
            case MODE_SPARSE:
                setBlockSparse(key, state, isAir);
                break;
            case MODE_EMPTY:
                if (!isAir) {
                    initializeSparse(key, state);
                }
                break;
        }
    }

    /**
     * Checks if the section is empty (contains no blocks).
     *
     * @return true if empty
     */
    public boolean isEmpty() {
        return mode == MODE_EMPTY;
    }

    /**
     * Packs local coordinates into a single 16-bit short value.
     * Format: YYYY ZZZZ XXXX (4 bits per dimension)
     *
     * @param x local X
     * @param y local Y
     * @param z local Z
     * @return packed coordinate
     */
    private static short packCoordinate(int x, int y, int z) {
        return (short) ((y << 8) | (z << 4) | x);
    }

    /**
     * Checks if a state is null (meaning 'not present' in delta).
     *
     * @param state the state to check
     * @return true if null
     */
    private boolean isAirOrNull(S state) {
        return state == null;
    }

    /**
     * Sets a block in dense mode, checking if the section should convert back to
     * sparse.
     *
     * @param key   the packed coordinate key
     * @param state the block state
     * @param isAir whether the new state is effectively air/null
     */
    private void setBlockDense(short key, S state, boolean isAir) {
        int index = key & COORD_MASK;
        @SuppressWarnings("unchecked")
        S old = (S) denseBlocks[index];
        boolean wasAir = isAirOrNull(old);

        if (isAir) {
            if (!wasAir) {
                denseBlocks[index] = null;
                denseCount--;

                if (denseCount < (CisConstants.SPARSE_DENSE_THRESHOLD >> 1)) {
                    convertToSparse();
                }
            }
        } else {
            denseBlocks[index] = state;
            if (wasAir) {
                denseCount++;
            }
        }
    }

    /**
     * Sets a block in sparse mode, updating values or adding/removing entries.
     *
     * @param key   the packed coordinate key
     * @param state the block state
     * @param isAir whether the new state is effectively air/null
     */
    private void setBlockSparse(short key, S state, boolean isAir) {
        int index = findSparseIndex(key);

        if (isAir) {
            if (index >= 0) {
                removeSparseEntry(index);
            }
        } else {
            if (index >= 0) {
                sparseValues[index] = state;
            } else {
                addSparseEntry(key, state);
            }
        }
    }

    /**
     * Finds the index of a key in sparse storage.
     *
     * @return the index, or -1 if not found
     */
    private int findSparseIndex(short key) {
        for (int i = 0; i < sparseSize; i++) {
            if (sparseKeys[i] == key) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Removes an entry from sparse storage by index.
     * Uses efficient swap-and-pop strategy to maintain array density.
     *
     * @param index the index in the sparse arrays to remove
     */
    private void removeSparseEntry(int index) {
        int lastIndex = --sparseSize;

        if (index < lastIndex) {
            sparseKeys[index] = sparseKeys[lastIndex];
            sparseValues[index] = sparseValues[lastIndex];
        }

        sparseValues[lastIndex] = null;

        if (sparseSize == 0) {
            mode = MODE_EMPTY;
        }
    }

    /**
     * Adds an entry to sparse storage, growing or converting to dense if necessary.
     *
     * @param key   the packed coordinate key
     * @param state the block state
     */
    private void addSparseEntry(short key, S state) {
        if (sparseSize >= CisConstants.MAX_SPARSE_CAPACITY) {
            convertToDense();
            setBlockDense(key, state, false);
        } else {
            if (sparseSize >= sparseKeys.length) {
                growSparseArrays();
            }

            sparseKeys[sparseSize] = key;
            sparseValues[sparseSize] = state;
            sparseSize++;
        }
    }

    /**
     * Initializes the section in sparse mode with the first added block.
     *
     * @param key   the packed coordinate key
     * @param state the block state
     */
    private void initializeSparse(short key, S state) {
        mode = MODE_SPARSE;
        sparseKeys = new short[INITIAL_SPARSE_CAPACITY];
        sparseValues = new Object[INITIAL_SPARSE_CAPACITY];
        sparseKeys[0] = key;
        sparseValues[0] = state;
        sparseSize = 1;
    }

    /**
     * Grows the sparse storage arrays up to the maximum sparse capacity.
     */
    private void growSparseArrays() {
        int newCapacity = Math.min(sparseKeys.length << 1, CisConstants.MAX_SPARSE_CAPACITY);
        sparseKeys = Arrays.copyOf(sparseKeys, newCapacity);
        sparseValues = Arrays.copyOf(sparseValues, newCapacity);
    }

    /**
     * Converts the section from sparse to dense storage mode.
     * Occurs when the number of modified blocks exceeds the sparse threshold.
     */
    private void convertToDense() {
        denseBlocks = new Object[VOLUME];

        for (int i = 0; i < sparseSize; i++) {
            denseBlocks[sparseKeys[i] & COORD_MASK] = sparseValues[i];
        }

        denseCount = sparseSize;
        sparseKeys = null;
        sparseValues = null;
        mode = MODE_DENSE;
    }

    /**
     * Converts the section from dense to sparse storage mode.
     * Occurs when the number of modified blocks falls significantly below the
     * threshold.
     */
    private void convertToSparse() {
        short[] keys = new short[CisConstants.SPARSE_DENSE_THRESHOLD];
        Object[] values = new Object[CisConstants.SPARSE_DENSE_THRESHOLD];
        int count = 0;

        for (int i = 0; i < VOLUME; i++) {
            Object state = denseBlocks[i];
            if (state != null) {
                keys[count] = (short) i;
                values[count] = state;
                count++;
            }
        }

        this.sparseKeys = keys;
        this.sparseValues = values;
        this.sparseSize = count;
        this.denseBlocks = null;
        this.mode = (count == 0) ? MODE_EMPTY : MODE_SPARSE;
    }
}