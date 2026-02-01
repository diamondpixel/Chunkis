package io.liparakis.cisplugin.decoder;

/**
 * Constants for CIS (Chunk Information Storage) format V7.
 * Ported from core module for standalone use.
 */
public final class CisConstants {

    /** Magic number for CIS files: "CIS4" in ASCII (0x43495334) */
    public static final int MAGIC = 0x43495334;

    /** Current CIS format version (V7) */
    public static final int VERSION = 7;

    public static final int SECTION_Y_BITS = 6;
    public static final int BLOCK_COUNT_BITS = 13;

    public static final int SECTION_ENCODING_SPARSE = 0;

    private CisConstants() {
    }
}
