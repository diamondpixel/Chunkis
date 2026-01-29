package io.liparakis.chunkis.storage;

/**
 * Central constants for CIS (Chunk Information Storage)
 * system.
 * <p>
 * This class provides:
 * - File format constants (magic numbers, versions)
 * - Geometry constants (section sizes, region sizes)
 */
public final class CisConstants {

    // ==================== File Format Constants ====================

    /**
     * Magic number for CIS files: "CIS4" in ASCII (0x43495334)
     * Used to validate file format and detect corruption.
     */
    public static final int MAGIC = 0x43495334;

    /**
     * Current CIS format version.
     * V7: Fixed dense encoding data loss by forcing full sparse support.
     * Increment when making breaking changes to the serialization format.
     */
    public static final int VERSION = 7;

    // ==================== Section Geometry ====================

    /**
     * Size of one dimension of a chunk section (16 blocks).
     * Minecraft chunk sections are 16x16x16 cubes.
     */
    public static final int SECTION_SIZE = 16;

    /**
     * Minimum Y section index (-4 for world starting at Y=-64).
     */
    public static final int MIN_SECTION_Y = -4;

    /**
     * Maximum Y section index (19 for world ending at Y=319).
     */
    public static final int MAX_SECTION_Y = 19;

    /**
     * Minimum absolute block Y coordinate (-64).
     */
    public static final int MIN_Y = MIN_SECTION_Y * SECTION_SIZE;

    /**
     * Maximum absolute block Y coordinate (319).
     */
    public static final int MAX_Y = (MAX_SECTION_Y + 1) * SECTION_SIZE - 1;

    // ==================== Storage Constants ====================

    /**
     * Default compression level for chunk data.
     * Uses BEST_SPEED (level 1) for fast compression with reasonable ratio.
     */
    public static final int COMPRESSION_LEVEL = 1; // Deflater.BEST_SPEED

    /**
     * Maximum cached region files (64 files).
     * Prevents unbounded memory growth while maintaining good hit rate.
     */
    public static final int MAX_CACHED_REGIONS = 64;

    /**
     * Threshold for sparse-to-dense storage conversion (4097 blocks).
     * Sections with fewer blocks use sparse storage (hash map).
     * Disabled in V7 (threshold > max volume) to prevent dense encoding data loss.
     */
    public static final int SPARSE_DENSE_THRESHOLD = 4097;

    /**
     * Maximum capacity for sparse storage before forcing conversion/resize (4097
     * blocks).
     */
    public static final int MAX_SPARSE_CAPACITY = 4097;

    // ==================== Serialization Bit Widths ====================

    public static final int PALETTE_SIZE_BITS = 8;
    public static final int SECTION_Y_BITS = 6; // ZigZag encoded
    public static final int BLOCK_COUNT_BITS = 13;

    public static final int SECTION_ENCODING_SPARSE = 0;
    public static final int SECTION_ENCODING_DENSE = 1;

    // ==================== Private Constructor ====================

    /**
     * Private constructor prevents instantiation.
     * This is a utility class with only static members.
     */
    private CisConstants() {
        throw new AssertionError("CisConstants is a utility class and should not be instantiated");
    }
}