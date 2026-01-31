package io.liparakis.chunkis.storage;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.util.Arrays;

/**
 * A sparse, memory-efficient representation of a chunk used for delta
 * operations.
 * Uses primitive collections and spatial locality caching for high-performance
 * operations.
 *
 * @param <S> the type representing a block state
 */
public final class CisChunk<S> {
    /**
     * Bit-shift value for world Y to section Y conversion.
     */
    private static final int SECTION_SHIFT = 4;

    /**
     * Bit-mask for extracting local coordinates from world coordinates.
     */
    private static final int COORD_MASK = 15;

    /**
     * Map of section Y-indices to their storage objects.
     */
    private final Int2ObjectMap<CisSection<S>> sections;

    /**
     * The Y-index of the last accessed section (for caching).
     */
    private int lastSectionY;

    /**
     * The last accessed section instance (for caching).
     */
    private CisSection<S> lastSection;

    /**
     * Creates a new empty CisChunk.
     */
    public CisChunk() {
        this.sections = new Int2ObjectOpenHashMap<>();
        this.lastSectionY = Integer.MIN_VALUE;
    }

    /**
     * Adds a block to the chunk at the specified coordinates.
     * Optimized with spatial locality caching to minimize map lookups during
     * sequential writes.
     *
     * @param x     the local chunk X coordinate (0-15)
     * @param y     the world Y coordinate
     * @param z     the local chunk Z coordinate (0-15)
     * @param state the block state to set
     */
    public void addBlock(int x, int y, int z, S state) {
        int sectionY = y >> SECTION_SHIFT;

        CisSection<S> section;
        if (sectionY == lastSectionY && lastSection != null) {
            section = lastSection;
        } else {
            section = sections.get(sectionY);
            if (section == null) {
                section = new CisSection<>();
                this.sections.put(sectionY, section);
            }
            lastSectionY = sectionY;
            lastSection = section;
        }

        section.setBlock(x & COORD_MASK, y & COORD_MASK, z & COORD_MASK, state);
    }

    /**
     * Returns the raw map of section Y indices to CisSection objects.
     * Note: The returned map is not sorted by key.
     *
     * @return the internal sections map
     */
    public Int2ObjectMap<CisSection<S>> getSections() {
        return sections;
    }

    /**
     * Returns section Y indices sorted from bottom to top.
     * Useful for serialization where deterministic order is required.
     *
     * @return a sorted array of section indices
     */
    public int[] getSortedSectionIndices() {
        int[] keys = sections.keySet().toIntArray();
        Arrays.sort(keys);
        return keys;
    }
}
