package io.liparakis.cisplugin.ui;

import io.liparakis.cisplugin.decoder.CisFileData;
import io.liparakis.cisplugin.decoder.RegionFileReader;
import io.liparakis.cisplugin.decoder.SimpleBlockState;

import java.util.*;

/**
 * Data model for the Chunkis Inspector UI.
 * Pre-calculates statistics and structures data for easy UI interaction.
 */
public class CisStats {
    private final CisFileData data;
    private final RegionFileReader.ChunkEntry entry;
    private final Map<Integer, Integer> paletteUsage = new HashMap<>();
    private final List<SectionStats> sections = new ArrayList<>();

    public CisStats(CisFileData data, RegionFileReader.ChunkEntry entry) {
        this.data = data;
        this.entry = entry;

        // Calculate Stats
        int totalBlocks = 0;

        // 1. Calculate Per-Section Voxel Data & Palette Usage
        for (CisFileData.SectionData section : data.getSections()) {
            int y = section.getSectionY();
            int[][][] voxels = new int[16][16][16];
            int sectionBlocks = 0;

            // Fill with air (-1 or 0 depending on palette, assuming 0 is default/air in
            // local palette context?)
            // Actually, CisFileData returns blocks with global palette indices.
            // We'll initialize with 0? No, sparse sections only contain non-air blocks
            // usually.
            // But we need to know what "air" is.
            // We'll assume anything NOT in the block list is Air (0).

            for (CisFileData.BlockEntry block : section.getBlocks()) {
                voxels[block.x()][block.y()][block.z()] = block.paletteIndex();

                paletteUsage.merge(block.paletteIndex(), 1, Integer::sum);
                sectionBlocks++;
            }

            sections.add(new SectionStats(
                    y,
                    section.isSparse(),
                    sectionBlocks,
                    voxels));
            totalBlocks += sectionBlocks;
        }

        // Sort sections by Y (just in case)
        sections.sort(Comparator.comparingInt(SectionStats::y));
    }

    public CisFileData getData() {
        return data;
    }

    public RegionFileReader.ChunkEntry getEntry() {
        return entry;
    }

    public List<SimpleBlockState> getGlobalPalette() {
        return data.getPalette();
    }

    public Map<Integer, Integer> getPaletteUsage() {
        return paletteUsage;
    }

    public List<SectionStats> getSections() {
        return sections;
    }

    public SectionStats getSection(int y) {
        return sections.stream().filter(s -> s.y() == y).findFirst().orElse(null);
    }

    public long getRawVoxelSize() {
        // Approximate: 4KB per section (indices) + Palette overhead
        // Or strictly: 16x16x16 * Sections * 1 byte (if < 256 blocks) or 2 bytes?
        // Let's assume 1.5 bytes avg per block for vanilla packed storage estimate
        return data.getSections().size() * 4096L * 2; // 8KB per section vanilla estimate
    }

    public record SectionStats(int y, boolean sparse, int nonAirCount, int[][][] voxels) {
    }
}
