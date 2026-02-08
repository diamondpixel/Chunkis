package io.liparakis.chunkis.util;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ProtoChunk;

/**
 * Immutable snapshot of vanilla/generated block states in a chunk.
 * <p>
 * This class captures the original world-generated state of a chunk before any
 * player modifications occur. It provides O(1) random access to the original
 * block states, which is essential for features like chunk restoration, undo
 * systems, or comparing current state against original state.
 * </p>
 * <p>
 * The snapshot is created once from a {@link ProtoChunk} during chunk
 * generation
 * and remains immutable throughout its lifetime. This ensures:
 * <ul>
 * <li>Thread-safe read access without synchronization</li>
 * <li>Consistent reference point for the chunk's original state</li>
 * <li>No risk of the snapshot being corrupted by subsequent modifications</li>
 * </ul>
 * </p>
 * <p>
 * Memory optimization:
 * <ul>
 * <li>Empty sections (all air) are stored as {@code null} to save memory</li>
 * <li>Each non-empty section uses a flat array of 4096 BlockStates
 * (16×16×16)</li>
 * <li>Block states are stored using bit-packed indexing for cache
 * efficiency</li>
 * </ul>
 * </p>
 * <p>
 * Performance characteristics:
 * <ul>
 * <li>Construction: O(n) where n is the number of blocks in the chunk</li>
 * <li>Lookup: O(1) constant time access to any block</li>
 * <li>Memory: ~256KB per full chunk (16 sections × 4096 states × 4 bytes)</li>
 * </ul>
 * </p>
 *
 * @see ProtoChunk
 * @see ChunkDelta
 */
public final class VanillaChunkSnapshot {

    /**
     * Array of section snapshots, indexed by section Y coordinate.
     * Each section contains 4096 block states (16×16×16) or null if empty.
     * Uses bit-packed indexing: {@code index = (y << 8) | (z << 4) | x}
     */
    private final BlockState[][] sectionStates;

    /**
     * The Y coordinate of the bottom-most section in this chunk.
     * Used to convert world Y coordinates to section array indices.
     */
    private final int minSectionY;

    /**
     * Creates an immutable snapshot of the vanilla block states from a ProtoChunk.
     * <p>
     * This constructor performs a deep copy of all block states from the proto
     * chunk,
     * capturing the world-generated state before any player modifications. Empty
     * sections (containing only air) are not stored to conserve memory.
     * </p>
     * <p>
     * The indexing scheme within each section uses bit-packing for optimal cache
     * performance: {@code index = (y << 8) | (z << 4) | x}, where coordinates are
     * local to the section (0-15).
     * </p>
     *
     * @param protoChunk the proto chunk to snapshot (must not be null)
     * @throws NullPointerException if protoChunk is null
     */
    public VanillaChunkSnapshot(ProtoChunk protoChunk) {
        ChunkSection[] sections = protoChunk.getSectionArray();
        this.minSectionY = protoChunk.getBottomSectionCoord();
        this.sectionStates = new BlockState[sections.length][];

        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty())
                continue;

            BlockState[] states = new BlockState[4096];
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int idx = (y << 8) | (z << 4) | x;
                        states[idx] = section.getBlockState(x, y, z);
                    }
                }
            }
            sectionStates[i] = states;
        }
    }

    /**
     * Retrieves the original vanilla block state at the specified position.
     * <p>
     * This method provides O(1) constant-time access to the block state that was
     * generated during world generation, before any player modifications. If the
     * requested position is outside the chunk bounds or in an empty section,
     * air is returned.
     * </p>
     * <p>
     * The lookup process:
     * <ol>
     * <li>Convert world Y to section index using
     * {@code (worldY >> 4) - minSectionY}</li>
     * <li>Check if section exists and is non-empty</li>
     * <li>Calculate within-section index using bit-packing</li>
     * <li>Return the cached block state</li>
     * </ol>
     * </p>
     *
     * @param localX the x-coordinate within the chunk (0-15)
     * @param worldY the absolute world y-coordinate (any valid world height)
     * @param localZ the z-coordinate within the chunk (0-15)
     * @return the original vanilla BlockState at this position, or air if the
     *         position is out of bounds or the section is empty
     */
    public BlockState getVanillaState(int localX, int worldY, int localZ) {
        int sectionIndex = (worldY >> 4) - minSectionY;
        if (sectionIndex < 0 || sectionIndex >= sectionStates.length) {
            return Blocks.AIR.getDefaultState();
        }

        BlockState[] section = sectionStates[sectionIndex];
        if (section == null) {
            return Blocks.AIR.getDefaultState();
        }

        int index = ((worldY & 15) << 8) | (localZ << 4) | localX;
        return section[index];
    }
}