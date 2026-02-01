package io.liparakis.chunkis.core;

import io.liparakis.chunkis.storage.CisConstants;

/**
 * Represents a single block change instruction with packed representation.
 * <p>
 * Coordinates are relative to the chunk (0-15 for x/z, world height for y).
 * This record uses a compact packed representation internally to reduce memory
 * usage and improve cache locality.
 * </p>
 * <p>
 * The packed long format is: {@code [paletteIndex: 32 bits][y: 20 bits][x: 4 bits][z: 4 bits][reserved: 4 bits]}
 * </p>
 * <p>
 * Bit layout details:
 * <ul>
 *   <li>Bits 0-3: Reserved (unused)</li>
 *   <li>Bits 4-7: Z coordinate (4 bits, range 0-15)</li>
 *   <li>Bits 8-11: X coordinate (4 bits, range 0-15)</li>
 *   <li>Bits 12-31: Y coordinate (20 bits, signed, supports Minecraft's full height range)</li>
 *   <li>Bits 32-63: Palette index (32 bits, references a BlockState in the chunk's palette)</li>
 * </ul>
 * </p>
 * <p>
 * This compact representation allows:
 * <ul>
 *   <li>Efficient storage with 8 bytes per instruction instead of object overhead</li>
 *   <li>Fast network transmission</li>
 *   <li>Better CPU cache utilization</li>
 *   <li>Support for large palettes (up to 2^32 unique block states)</li>
 * </ul>
 * </p>
 *
 * @param x the x-coordinate within the chunk (0-15)
 * @param y the y-coordinate in world space (supports negative values for deep worlds)
 * @param z the z-coordinate within the chunk (0-15)
 * @param paletteIndex the index into the chunk's BlockState palette
 * @see ChunkDelta
 * @see Palette
 */
public record BlockInstruction(byte x, int y, byte z, int paletteIndex) {

    /**
     * Compact constructor that validates coordinate bounds.
     *
     * @throws IllegalArgumentException if x is not in range [0, 15]
     * @throws IllegalArgumentException if z is not in range [0, 15]
     * @throws IllegalArgumentException if y is not in range [MIN_Y, MAX_Y]
     */
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
     * <p>
     * This method unpacks all instruction data from a single long value,
     * which is useful for extremely compact storage and network transmission.
     * The Y coordinate is sign-extended to support negative world heights.
     * </p>
     *
     * @param packed the packed long containing all instruction data
     * @return a new BlockInstruction with unpacked coordinates and palette index
     * @see #pack()
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

    /**
     * Packs a 3D position into a long value without the palette index.
     * <p>
     * This is useful for creating position keys for lookups in maps.
     * The format matches the lower 32 bits of the full packed instruction format.
     * </p>
     *
     * @param x the x-coordinate (0-15)
     * @param y the y-coordinate (world height)
     * @param z the z-coordinate (0-15)
     * @return a packed long containing the position data
     * @see #pack()
     */
    public static long packPos(int x, int y, int z) {
        return ((long) (y & 0xFFFFF) << 12) |
                ((long) (x & 0xF) << 8) |
                ((long) (z & 0xF) << 4);
    }

    /**
     * Extracts the X coordinate from a packed position or instruction.
     *
     * @param packed the packed long value
     * @return the x-coordinate (0-15)
     * @see #packPos(int, int, int)
     */
    public static int unpackX(long packed) {
        return (int) ((packed >> 8) & 0xF);
    }

    /**
     * Extracts the Y coordinate from a packed position or instruction.
     * <p>
     * This method properly handles sign extension for negative Y values,
     * supporting Minecraft's full world height range including negative coordinates.
     * </p>
     *
     * @param packed the packed long value
     * @return the y-coordinate (world height, may be negative)
     * @see #packPos(int, int, int)
     */
    public static int unpackY(long packed) {
        int y = (int) ((packed >> 12) & 0xFFFFF);
        if ((y & 0x80000) != 0) { // Sign bit for 20-bit
            y |= 0xFFF00000;
        }
        return y;
    }

    /**
     * Extracts the Z coordinate from a packed position or instruction.
     *
     * @param packed the packed long value
     * @return the z-coordinate (0-15)
     * @see #packPos(int, int, int)
     */
    public static int unpackZ(long packed) {
        return (int) ((packed >> 4) & 0xF);
    }

    /**
     * Packs this instruction into a single long for compact storage.
     * <p>
     * The packed format is: {@code [paletteIndex: 32 bits][y: 20 bits][x: 4 bits][z: 4 bits][reserved: 4 bits]}
     * </p>
     * <p>
     * This allows storing complete block change instructions in just 8 bytes,
     * significantly reducing memory usage compared to object-based storage.
     * The packed representation is also ideal for network transmission and
     * disk serialization.
     * </p>
     *
     * @return a packed long representation of this instruction
     * @see #fromPacked(long)
     */
    public long pack() {
        return ((long) paletteIndex << 32) |
                ((long) (y & 0xFFFFF) << 12) |
                ((long) (x & 0xF) << 8) |
                ((long) (z & 0xF) << 4);
    }
}