package io.liparakis.cisplugin.decoder;

import java.util.List;

/**
 * Result object containing decoded CIS file data.
 */
public final class CisFileData {
    private final int version;
    private final List<SimpleBlockState> palette;
    private final List<SectionData> sections;
    private final int blockEntityCount;
    private final int entityCount;

    public CisFileData(int version, List<SimpleBlockState> palette, List<SectionData> sections,
            int blockEntityCount, int entityCount) {
        this.version = version;
        this.palette = palette;
        this.sections = sections;
        this.blockEntityCount = blockEntityCount;
        this.entityCount = entityCount;
    }

    public int getVersion() {
        return version;
    }

    public List<SimpleBlockState> getPalette() {
        return palette;
    }

    public List<SectionData> getSections() {
        return sections;
    }

    public int getBlockEntityCount() {
        return blockEntityCount;
    }

    public int getEntityCount() {
        return entityCount;
    }

    /**
     * Data for a single chunk section.
     */
    public static final class SectionData {
        private final int sectionY;
        private final boolean sparse;
        private final List<BlockEntry> blocks;

        public SectionData(int sectionY, boolean sparse, int blockCount, List<BlockEntry> blocks) {
            this.sectionY = sectionY;
            this.sparse = sparse;
            this.blocks = blocks;
        }

        public int getSectionY() {
            return sectionY;
        }

        public boolean isSparse() {
            return sparse;
        }

        public List<BlockEntry> getBlocks() {
            return blocks;
        }
    }

    /**
     * A single block entry with position and state.
     */
    public record BlockEntry(int x, int y, int z, int paletteIndex) {
    }
}
