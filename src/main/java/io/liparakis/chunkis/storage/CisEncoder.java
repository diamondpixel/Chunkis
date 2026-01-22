package io.liparakis.chunkis.storage;

import io.liparakis.chunkis.core.BlockInstruction;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.storage.BitUtils.BitWriter;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * High-performance encoder for the CIS format.
 * Refactored to eliminate heavy object allocations (HashSet/HashMap) in hot
 * loops.
 */
public final class CisEncoder {

    // Thread-local or reusable buffers to eliminate GC pressure during heavy save
    // operations
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

        // 2. Global Palette
        List<BlockState> states = delta.getBlockPalette().getAll();
        ctx.globalIdMap.defaultReturnValue(-1);

        dos.writeInt(states.size());
        for (int i = 0; i < states.size(); i++) {
            BlockState state = states.get(i);
            int mappingId = mapping.getBlockId(state);

            dos.writeShort(mappingId);
            dos.writeByte(mapping.getPackedStateData(state));
            ctx.globalIdMap.put(state, i);
        }

        // 3. Instructions (Streamed to separate BitWriter)
        CisChunk chunk = CisChunk.fromDelta(delta);
        ctx.bitWriter.reset();

        int[] sortedSections = chunk.getSortedSectionIndices();
        ctx.bitWriter.write(sortedSections.length, CisConstants.SECTION_COUNT_BITS);

        for (int sectionY : sortedSections) {
            encodeSection(ctx, sectionY, chunk.getSections().get(sectionY));
        }
        ctx.bitWriter.flush();

        // Write BitWriter data to Main Stream
        byte[] instructionData = ctx.bitWriter.toByteArray();
        dos.writeInt(instructionData.length);
        dos.write(instructionData);

        // 4. Block Entities
        // Ensure we cast fastutil map type
        Long2ObjectMap<NbtCompound> bes = (Long2ObjectMap<NbtCompound>) delta.getBlockEntities();
        dos.writeInt(bes.size());

        // Use the fastutil-specific fast iterator to avoid boxing Long keys
        for (Long2ObjectMap.Entry<NbtCompound> entry : bes.long2ObjectEntrySet()) {
            long p = entry.getLongKey(); // Returns primitive long
            NbtCompound nbt = entry.getValue();

            dos.writeByte(BlockInstruction.unpackX(p));
            dos.writeInt(BlockInstruction.unpackY(p));
            dos.writeByte(BlockInstruction.unpackZ(p));
            NbtIo.writeCompound(nbt, dos);
        }

        return ctx.mainBuffer.toByteArray();
    }

    private static void encodeSection(EncoderContext ctx, int sectionY, CisSection section) {
        ctx.bitWriter.writeZigZag(sectionY, CisConstants.SECTION_Y_BITS);

        for (int my = 0; my < 4; my++) {
            for (int mz = 0; mz < 4; mz++) {
                for (int mx = 0; mx < 4; mx++) {
                    encodeMicroCube(ctx, section, mx, my, mz);
                }
            }
        }
    }

    private static void encodeMicroCube(EncoderContext ctx, CisSection section, int mx, int my, int mz) {
        ctx.clearMicroCube();

        int startX = mx << 2;
        int startY = my << 2;
        int startZ = mz << 2;

        // 1. Collect blocks and build local palette using primitive maps
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    BlockState state = section.getBlock(startX + x, startY + y, startZ + z);
                    if (state != null) {
                        int gid = ctx.globalIdMap.getInt(state);
                        if (gid == -1)
                            continue; // Skip blocks not in the global palette (Implicit Air)

                        if (!ctx.localPaletteSet.containsKey(gid)) {
                            ctx.localPaletteSet.put(gid, 0); // Use as a Set
                            ctx.sortedPaletteBuffer[ctx.paletteSize++] = gid;
                        }
                        ctx.addBlockRecord(startX + x, startY + y, startZ + z, gid);
                    }
                }
            }
        }

        if (ctx.paletteSize == 0) {
            ctx.bitWriter.write(0, CisConstants.PALETTE_SIZE_BITS);
            return;
        }

        // 2. Write Local Palette
        ctx.bitWriter.write(ctx.paletteSize, CisConstants.PALETTE_SIZE_BITS);
        Arrays.sort(ctx.sortedPaletteBuffer, 0, ctx.paletteSize);

        ctx.localIndexMap.clear();
        for (int i = 0; i < ctx.paletteSize; i++) {
            int gid = ctx.sortedPaletteBuffer[i];
            ctx.bitWriter.write(gid, CisConstants.GLOBAL_ID_BITS);
            ctx.localIndexMap.put(gid, i);
        }

        // 3. Write Instructions
        ctx.bitWriter.write(ctx.blockCount, CisConstants.BLOCK_COUNT_BITS);
        int bitsPerIndex = (ctx.paletteSize > 1) ? (32 - Integer.numberOfLeadingZeros(ctx.paletteSize - 1)) : 0;

        int lastLy = 0, lastLxz = 0, lastLid = 0;

        for (int i = 0; i < ctx.blockCount; i++) {
            int ly = ctx.blockY[i];
            int lxz = (ctx.blockZ[i] << 4) | ctx.blockX[i];
            int lid = ctx.localIndexMap.get(ctx.blockGid[i]);

            int dy = ly - lastLy;
            boolean newY = (i == 0) || (dy != 0);
            boolean newXZ = (i == 0) || (lxz != lastLxz);
            boolean newID = (i == 0) || (lid != lastLid);

            ctx.bitWriter.writeBool(newY);
            if (newY)
                ctx.bitWriter.writeZigZag(dy, CisConstants.Y_DELTA_BITS);

            ctx.bitWriter.writeBool(newXZ);
            if (newXZ)
                ctx.bitWriter.write(lxz, 8);

            if (ctx.paletteSize > 1) {
                ctx.bitWriter.writeBool(newID);
                if (newID)
                    ctx.bitWriter.write(lid, bitsPerIndex);
            }

            lastLy = ly;
            lastLxz = lxz;
            lastLid = lid;
        }
    }

    /**
     * Inner class to hold reusable data structures, preventing per-tick
     * allocations.
     */
    private static class EncoderContext {
        final ByteArrayOutputStream mainBuffer = new ByteArrayOutputStream(8192);
        final BitWriter bitWriter = new BitWriter(4096);
        final Object2IntMap<BlockState> globalIdMap = new Object2IntOpenHashMap<>();

        // MicroCube Workspace
        final Int2IntMap localPaletteSet = new Int2IntOpenHashMap();
        final Int2IntMap localIndexMap = new Int2IntOpenHashMap();
        final int[] sortedPaletteBuffer = new int[64];
        int paletteSize = 0;

        // Block records (Parallel arrays for zero-allocation storage of 4x4x4
        // microcube)
        final int[] blockX = new int[64], blockY = new int[64], blockZ = new int[64], blockGid = new int[64];
        int blockCount = 0;

        void reset() {
            mainBuffer.reset();
            globalIdMap.clear();
        }

        void clearMicroCube() {
            localPaletteSet.clear();
            localIndexMap.clear();
            paletteSize = 0;
            blockCount = 0;
        }

        void addBlockRecord(int x, int y, int z, int gid) {
            blockX[blockCount] = x;
            blockY[blockCount] = y;
            blockZ[blockCount] = z;
            blockGid[blockCount] = gid;
            blockCount++;
        }
    }
}