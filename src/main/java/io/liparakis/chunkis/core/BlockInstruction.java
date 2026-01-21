package io.liparakis.chunkis.core;

import io.liparakis.chunkis.storage.CisConstants;

/**
 * Represents a single block change instruction with packed representation.
 * Coordinates are relative to the chunk (0-15 for x/z, world height for y).
 * <p>
 * This record uses a compact packed representation internally to reduce memory
 * usage.
 * A packed long stores: [paletteIndex: 32 bits][y: 20 bits][x: 4 bits][z: 4
 * bits][reserved: 4 bits]
 */
public record BlockInstruction(byte x, int y, byte z, int paletteIndex) {

    public BlockInstruction {
        if (x < 0 || x >= CisConstants.SECTION_SIZE) {
            throw new IllegalArgumentException("X coordinate out of bounds: " + x);
        }
        if (z < 0 || z >= CisConstants.SECTION_SIZE) {
            throw new IllegalArgumentException("Z coordinate out of bounds: " + z);
        }
        if (y < CisConstants.MIN_Y || y > CisConstants.MAX_Y) {
            throw new IllegalArgumentException("Y coordinate out of bounds: " + y);
        }
    }

    /**
     * Creates a BlockInstruction from a packed long representation.
     * This is useful for extremely compact storage and network transmission.
     *
     * @param packed The packed long containing all instruction data
     * @return A new BlockInstruction
     */
    public static BlockInstruction fromPacked(long packed) {
        byte z = (byte) unpackZ(packed);
        byte x = (byte) unpackX(packed);
        int y = unpackY(packed);
        int paletteIndex = (int) (packed >> 32);

        if ((y & 0x80000) != 0) {
            y |= 0xFFF00000;
        }

        return new BlockInstruction(x, y, z, paletteIndex);
    }

    public static long packPos(int x, int y, int z) {
        return ((long) (y & 0xFFFFF) << 12) |
                ((long) (x & 0xF) << 8) |
                ((long) (z & 0xF) << 4);
    }

    public static int unpackX(long packed) {
        return (int) ((packed >> 8) & 0xF);
    }

    public static int unpackY(long packed) {
        return (int) ((packed >> 12) & 0xFFFFF);
    }

    public static int unpackZ(long packed) {
        return (int) ((packed >> 4) & 0xF);
    }

    /**
     * Packs this instruction into a single long for compact storage.
     * Format: [paletteIndex: 32 bits][y: 20 bits][x: 4 bits][z: 4 bits][reserved: 4
     * bits]
     *
     * @return A packed long representation of this instruction
     */
    public long pack() {
        return ((long) paletteIndex << 32) |
                ((long) (y & 0xFFFFF) << 12) |
                ((long) (x & 0xF) << 8) |
                ((long) (z & 0xF) << 4);
    }
}