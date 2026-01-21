package io.liparakis.chunkis.storage;

import io.liparakis.chunkis.core.BlockInstruction;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.Palette;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.block.BlockState;

import java.util.Arrays;

/**
 * A sparse, memory-efficient representation of a Chunk used for delta operations.
 * Refactored to use primitive collections and caching for high-performance tick operations.
 */
public class CisChunk {
    private final Int2ObjectMap<CisSection> sections = new Int2ObjectOpenHashMap<>();

    // --- Hot Path Cache ---
    // Caching the last accessed section significantly speeds up sequential writes
    // (e.g., applying a delta where blocks are sorted or grouped by chunk section).
    private int lastSectionY = Integer.MIN_VALUE;
    private CisSection lastSection = null;

    /**
     * Adds a block to the chunk.
     * Optimized to minimize map lookups via spatial locality caching.
     *
     * @param x     Local chunk X (0-15)
     * @param y     World Y
     * @param z     Local chunk Z (0-15)
     * @param state The BlockState to set
     */
    public void addBlock(int x, int y, int z, BlockState state) {
        int sectionY = y >> 4;

        CisSection section;

        // Fast path: Check if we are writing to the same section as the last operation.
        // This is very common during loop-based population or delta application.
        if (sectionY == lastSectionY && lastSection != null) {
            section = lastSection;
        } else {
            // Slow path: Map lookup or creation
            section = sections.get(sectionY);
            if (section == null) {
                section = new CisSection();
                sections.put(sectionY, section);
            }

            // Update cache
            lastSectionY = sectionY;
            lastSection = section;
        }

        // Apply coordinate masking (0-15) here ensures safety regardless of input
        section.setBlock(x & 15, y & 15, z & 15, state);
    }

    /**
     * Reconstructs a CisChunk from a compressed delta.
     */
    public static CisChunk fromDelta(ChunkDelta delta) {
        CisChunk chunk = new CisChunk();
        Palette<BlockState> palette = delta.getBlockPalette();

        // Optimization note: If BlockInstructions are sorted by Y in the source,
        // the cache in addBlock() will hit almost 100% of the time.
        for (BlockInstruction ins : delta.getBlockInstructions()) {
            BlockState state = palette.get(ins.paletteIndex());
            // Null check handles palette holes or air skipping depending on impl
            if (state != null) {
                chunk.addBlock(ins.x(), ins.y(), ins.z(), state);
            }
        }
        return chunk;
    }

    /**
     * Returns the raw primitive map of sections.
     * Note: Unlike TreeMap, this map is NOT sorted.
     */
    public Int2ObjectMap<CisSection> getSections() {
        return sections;
    }

    /**
     * Returns section indices sorted from bottom to top.
     * Useful for serialization where order is required.
     */
    public int[] getSortedSectionIndices() {
        int[] keys = sections.keySet().toIntArray();
        Arrays.sort(keys);
        return keys;
    }
}