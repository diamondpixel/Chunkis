package io.liparakis.chunkis.storage;

import net.minecraft.block.BlockState;

import java.util.Arrays;

/**
 * Memory-efficient storage for 16x16x16 block states.
 * Refactored for branch prediction optimization and reduced GC pressure.
 */
public final class CisSection {

    private static final int VOLUME = 4096; // 16^3

    // Storage mode constants instead of Enum to avoid indirect access and extra
    // classes
    private static final byte MODE_EMPTY = 0;
    private static final byte MODE_SPARSE = 1;
    private static final byte MODE_DENSE = 2;

    private byte mode = MODE_EMPTY;

    // Sparse storage: Parallel primitive arrays
    private short[] sparseKeys;
    private BlockState[] sparseValues;
    private int sparseSize;

    // Dense storage: Single array
    private BlockState[] denseBlocks;
    private int denseCount;

    public CisSection() {
    }

    public void setBlock(int x, int y, int z, BlockState state) {
        // Coordinate packing (inline candidate)
        short key = (short) ((y << 8) | (z << 4) | x);

        boolean isNull = (state == null);

        if (mode == MODE_DENSE) {
            setDense(key, state, isNull);
        } else if (mode == MODE_SPARSE) {
            setSparse(key, state, isNull);
        } else if (!isNull) {
            initSparse(key, state);
        }
    }

    public BlockState getBlock(int x, int y, int z) {
        if (mode == MODE_EMPTY)
            return null;

        short key = (short) ((y << 8) | (z << 4) | x);

        if (mode == MODE_DENSE) {
            return denseBlocks[key & 0xFFFF];
        }

        // Linear search for sparse
        final int size = this.sparseSize;
        final short[] keys = this.sparseKeys;
        for (int i = 0; i < size; i++) {
            if (keys[i] == key)
                return sparseValues[i];
        }
        return null;
    }

    private void setDense(short key, BlockState state, boolean isAir) {
        int idx = key & 0xFFFF;
        BlockState old = denseBlocks[idx];
        boolean wasAir = (old == null || old.isAir());

        if (isAir) {
            if (!wasAir) {
                denseBlocks[idx] = null;
                denseCount--;
                if (denseCount < (CisConstants.SPARSE_DENSE_THRESHOLD >> 1))
                    convertToSparse();
            }
        } else {
            denseBlocks[idx] = state;
            if (wasAir)
                denseCount++;
        }
    }

    private void setSparse(short key, BlockState state, boolean isAir) {
        int index = -1;
        for (int i = 0; i < sparseSize; i++) {
            if (sparseKeys[i] == key) {
                index = i;
                break;
            }
        }

        if (isAir) {
            if (index >= 0) {
                int lastIdx = --sparseSize;
                if (index < lastIdx) {
                    sparseKeys[index] = sparseKeys[lastIdx];
                    sparseValues[index] = sparseValues[lastIdx];
                }
                sparseValues[lastIdx] = null;
                if (sparseSize == 0)
                    mode = MODE_EMPTY;
            }
        } else {
            if (index >= 0) {
                sparseValues[index] = state;
            } else if (sparseSize >= CisConstants.MAX_SPARSE_CAPACITY) {
                convertToDense();
                setDense(key, state, false);
            } else {
                if (sparseSize >= sparseKeys.length)
                    growSparse();
                sparseKeys[sparseSize] = key;
                sparseValues[sparseSize] = state;
                ++sparseSize;
            }
        }
    }

    private void initSparse(short key, BlockState state) {
        mode = MODE_SPARSE;
        sparseKeys = new short[4];
        sparseValues = new BlockState[4];
        sparseKeys[0] = key;
        sparseValues[0] = state;
        sparseSize = 1;
    }

    private void growSparse() {
        int newLen = Math.min(sparseKeys.length << 1, CisConstants.MAX_SPARSE_CAPACITY);
        sparseKeys = Arrays.copyOf(sparseKeys, newLen);
        sparseValues = Arrays.copyOf(sparseValues, newLen);
    }

    private void convertToDense() {
        denseBlocks = new BlockState[VOLUME];
        for (int i = 0; i < sparseSize; i++) {
            denseBlocks[sparseKeys[i] & 0xFFFF] = sparseValues[i];
        }
        denseCount = sparseSize;
        sparseKeys = null;
        sparseValues = null;
        mode = MODE_DENSE;
    }

    private void convertToSparse() {
        short[] keys = new short[CisConstants.SPARSE_DENSE_THRESHOLD];
        BlockState[] values = new BlockState[CisConstants.SPARSE_DENSE_THRESHOLD];
        int count = 0;

        for (int i = 0; i < VOLUME; i++) {
            BlockState s = denseBlocks[i];
            if (s != null && !s.isAir()) {
                keys[count] = (short) i;
                values[count] = s;
                count++;
            }
        }
        this.sparseKeys = keys;
        this.sparseValues = values;
        this.sparseSize = count;
        this.denseBlocks = null;
        this.mode = (count == 0) ? MODE_EMPTY : MODE_SPARSE;
    }

    public boolean isEmpty() {
        return mode == MODE_EMPTY;
    }
}