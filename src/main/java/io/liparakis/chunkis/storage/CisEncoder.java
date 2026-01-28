package io.liparakis.chunkis.storage;

import io.liparakis.chunkis.core.BlockInstruction;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.storage.BitUtils.BitWriter;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * V5 Encoder for Chunkis format.
 * Uses Paletted Section storage with dynamic property bit-packing.
 */
public final class CisEncoder {

    // Thread-local reusable context to eliminate GC pressure
    private static final ThreadLocal<EncoderContext> CONTEXT = ThreadLocal.withInitial(EncoderContext::new);

    private CisEncoder() {
    }

    public static byte[] encode(ChunkDelta delta, CisMapping mapping) throws IOException {
        EncoderContext ctx = CONTEXT.get();
        ctx.reset();

        DataOutputStream dos = new DataOutputStream(ctx.mainBuffer);

        // 1. Header
        dos.writeInt(CisConstants.MAGIC);
        dos.writeInt(CisConstants.VERSION);

        // 2. Build Global Palette from ChunkDelta
        // CRITICAL: Ensure AIR is in the palette. If the delta only contains "Stone",
        // implicit air (nulls in sparse sections) will be mapped to global ID 0.
        // If ID 0 is Stone, all Air becomes Stone. We must ensure ID for AIR exists.
        List<BlockState> globalPalette = new ArrayList<>(delta.getBlockPalette().getAll());
        BlockState airState = net.minecraft.block.Blocks.AIR.getDefaultState();
        if (!globalPalette.contains(airState)) {
            globalPalette.add(0, airState);
        }

        ctx.globalIdMap.defaultReturnValue(-1);

        // Write palette size
        dos.writeInt(globalPalette.size());

        // Write each palette entry: blockId (short) + property data (variable bits)
        // We write block ID to main stream, properties to bit stream
        ctx.bitWriter.reset();

        for (int i = 0; i < globalPalette.size(); i++) {
            BlockState state = globalPalette.get(i);
            int blockId = mapping.getBlockId(state);

            // Write block ID to byte stream
            dos.writeShort(blockId);

            // Write property bits
            mapping.writeStateProperties(ctx.bitWriter, state);

            ctx.globalIdMap.put(state, i);
        }

        // Flush property bits and write to main stream
        byte[] palettePropertyData = ctx.bitWriter.toByteArray();
        dos.writeInt(palettePropertyData.length);
        dos.write(palettePropertyData);

        // 3. Sections - Build CisChunk from delta
        CisChunk chunk = CisChunk.fromDelta(delta);
        Int2ObjectMap<CisSection> sections = chunk.getSections();
        int[] sortedSections = chunk.getSortedSectionIndices();

        // Write section count
        dos.writeShort(sortedSections.length);

        // Encode each section
        ctx.bitWriter.reset();
        for (int sectionY : sortedSections) {
            encodeSection(ctx, sectionY, sections.get(sectionY));
        }
        ctx.bitWriter.flush();

        // Write section data
        byte[] sectionData = ctx.bitWriter.toByteArray();
        dos.writeInt(sectionData.length);
        dos.write(sectionData);

        // 4. Block Entities
        Long2ObjectMap<NbtCompound> bes = (Long2ObjectMap<NbtCompound>) delta.getBlockEntities();
        dos.writeInt(bes.size());

        for (Long2ObjectMap.Entry<NbtCompound> entry : bes.long2ObjectEntrySet()) {
            long p = entry.getLongKey();
            NbtCompound nbt = entry.getValue();

            dos.writeByte(BlockInstruction.unpackX(p));
            dos.writeInt(BlockInstruction.unpackY(p));
            dos.writeByte(BlockInstruction.unpackZ(p));
            NbtIo.writeCompound(nbt, dos);
        }

        // 5. Entities
        List<NbtCompound> entities = delta.getEntitiesList();
        int entityCount = (entities != null) ? entities.size() : 0;
        dos.writeInt(entityCount);

        if (entityCount > 0 && entities != null) {
            for (NbtCompound entity : entities) {
                NbtIo.writeCompound(entity, dos);
            }
        }

        return ctx.mainBuffer.toByteArray();
    }

    /**
     * Encodes a 16x16x16 section using paletted storage.
     * Format:
     * - Section Y (ZigZag encoded, 8 bits)
     * - Local Palette Size (8 bits)
     * - Local Palette Entries (each is a global palette index, using
     * ceil(log2(globalSize)) bits)
     * - Block Data (4096 entries, each using ceil(log2(localPaletteSize)) bits)
     */
    private static void encodeSection(EncoderContext ctx, int sectionY, CisSection section) {
        // Write section Y (ZigZag encoded)
        ctx.bitWriter.writeZigZag(sectionY, 8);

        // Build local palette (unique states in this section)
        ctx.localPalette.clear();
        ctx.localPaletteIndex.clear();
        ctx.localPaletteIndex.defaultReturnValue(-1);

        // Scan section for unique states
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = section.getBlock(x, y, z);
                    if (state != null && !state.isAir()) {
                        int globalIdx = ctx.globalIdMap.getInt(state);
                        if (globalIdx == -1)
                            continue; // Safety

                        if (!ctx.localPaletteIndex.containsKey(globalIdx)) {
                            ctx.localPaletteIndex.put(globalIdx, ctx.localPalette.size());
                            ctx.localPalette.add(globalIdx);
                        }
                    }
                }
            }
        }

        int localSize = ctx.localPalette.size();

        // Handle empty sections (all air)
        if (localSize == 0) {
            ctx.bitWriter.write(0, 8); // Palette size = 0
            return;
        }

        // Add AIR as index 0 if not present (for sparse sections)
        int airGlobalIdx = ctx.globalIdMap.getInt(net.minecraft.block.Blocks.AIR.getDefaultState());
        if (airGlobalIdx == -1)
            airGlobalIdx = 0; // AIR should always be 0

        boolean hasAir = ctx.localPaletteIndex.containsKey(airGlobalIdx);
        if (!hasAir) {
            // Insert air at index 0, shift others
            for (int globalIdx : ctx.localPalette) {
                ctx.localPaletteIndex.put(globalIdx, ctx.localPaletteIndex.get(globalIdx) + 1);
            }
            ctx.localPalette.add(0, airGlobalIdx);
            ctx.localPaletteIndex.put(airGlobalIdx, 0);
            localSize++;
        }

        // Write local palette size
        ctx.bitWriter.write(localSize, 8);

        // Write local palette entries (global indices)
        int globalBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(Math.max(1, ctx.globalIdMap.size() - 1)));
        for (int globalIdx : ctx.localPalette) {
            ctx.bitWriter.write(globalIdx, globalBits);
        }

        // Calculate bits per block
        int bitsPerBlock = (localSize > 1) ? Math.max(1, 32 - Integer.numberOfLeadingZeros(localSize - 1)) : 0;

        // Write block data (4096 entries)
        if (bitsPerBlock > 0) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlock(x, y, z);
                        int localIdx;

                        if (state == null || state.isAir()) {
                            localIdx = ctx.localPaletteIndex.get(airGlobalIdx);
                        } else {
                            int globalIdx = ctx.globalIdMap.getInt(state);
                            localIdx = ctx.localPaletteIndex.get(globalIdx);
                            if (localIdx == -1)
                                localIdx = 0; // Fallback to air
                        }

                        ctx.bitWriter.write(localIdx, bitsPerBlock);
                    }
                }
            }
        }
        // If bitsPerBlock == 0, entire section is single block type (no data needed)
    }

    /**
     * Reusable encoder context to prevent per-call allocations.
     */
    private static class EncoderContext {
        final ByteArrayOutputStream mainBuffer = new ByteArrayOutputStream(16384);
        final BitWriter bitWriter = new BitWriter(8192);
        final Object2IntMap<BlockState> globalIdMap = new Object2IntOpenHashMap<>();

        // Section-level workspace
        final List<Integer> localPalette = new ArrayList<>(64);
        final it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap localPaletteIndex = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();

        void reset() {
            mainBuffer.reset();
            globalIdMap.clear();
        }
    }
}