package io.liparakis.chunkis.storage.codec;

import io.liparakis.chunkis.core.BlockInstruction;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.Palette;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.spi.NbtAdapter;
import io.liparakis.chunkis.storage.BitUtils.BitWriter;
import io.liparakis.chunkis.storage.CisChunk;
import io.liparakis.chunkis.storage.CisConstants;
import io.liparakis.chunkis.storage.CisSection;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class for CIS (Chunk Incremental Storage) encoders containing shared
 * encoding logic.
 *
 * @param <S> The BlockState type
 * @param <N> The NBT type
 */
public abstract class AbstractCisEncoder<S, N> {

    /** Total number of blocks in a chunk section (16x16x16). */
    protected static final int SECTION_VOLUME = 4096;

    protected final BlockStateAdapter<?, S, ?> stateAdapter;
    protected final NbtAdapter<N> nbtAdapter;
    protected final S airState;

    protected AbstractCisEncoder(BlockStateAdapter<?, S, ?> stateAdapter, NbtAdapter<N> nbtAdapter, S airState) {
        this.stateAdapter = stateAdapter;
        this.nbtAdapter = nbtAdapter;
        this.airState = airState;
    }

    /**
     * Writes the global block palette to the output stream.
     */
    protected abstract void writeGlobalPalette(
            DataOutputStream dos,
            EncoderContext<S> ctx,
            List<S> usedStates) throws IOException;

    /**
     * Internal method that orchestrates the complete encoding process.
     */
    protected byte[] encodeInternal(ChunkDelta<S, N> delta) throws IOException {
        EncoderContext<S> ctx = getContext();
        ctx.reset();

        DataOutputStream dos = new DataOutputStream(ctx.mainBuffer);

        // 1. Convert to sparse chunk representation
        CisChunk<S> chunk = fromDelta(delta);

        // 2. Discover all used block states
        List<S> usedStates = collectUsedStates(chunk);

        writeHeader(dos);
        writeGlobalPalette(dos, ctx, usedStates);
        writeSections(dos, ctx, chunk);
        writeBlockEntities(dos, delta);
        writeEntities(dos, delta);

        return ctx.mainBuffer.toByteArray();
    }

    protected abstract EncoderContext<S> getContext();

    /**
     * Converts a ChunkDelta to a CisChunk for efficient spatial access.
     */
    private CisChunk<S> fromDelta(ChunkDelta<S, N> delta) {
        CisChunk<S> chunk = new CisChunk<>();
        Palette<S> palette = delta.getBlockPalette();

        for (BlockInstruction ins : delta.getBlockInstructions()) {
            S state = palette.get(ins.paletteIndex());
            if (state != null) {
                chunk.addBlock(ins.x(), ins.y(), ins.z(), state);
            }
        }
        return chunk;
    }

    /**
     * Collects all unique block states used in the chunk.
     */
    private List<S> collectUsedStates(CisChunk<S> chunk) {
        Object2IntMap<S> uniqueStates = new Object2IntOpenHashMap<>();
        uniqueStates.put(airState, 0); // Always include air

        for (CisSection<S> section : chunk.getSections().values()) {
            if (section.mode == CisSection.MODE_SPARSE) {
                for (int i = 0; i < section.sparseSize; i++) {
                    S state = (S) section.sparseValues[i];
                    uniqueStates.put(state, 0);
                }
            } else if (section.mode == CisSection.MODE_DENSE) {
                for (Object o : section.denseBlocks) {
                    S state = (S) o;
                    if (state != null && !stateAdapter.isAir(state)) {
                        uniqueStates.put(state, 0);
                    }
                }
            }
        }

        return new ArrayList<>(uniqueStates.keySet());
    }

    /**
     * Writes the CIS file header.
     */
    private static void writeHeader(DataOutputStream dos) throws IOException {
        dos.writeInt(CisConstants.MAGIC);
        dos.writeInt(CisConstants.VERSION);
    }

    /**
     * Writes all chunk sections.
     */
    private void writeSections(DataOutputStream dos, EncoderContext<S> ctx, CisChunk<S> chunk)
            throws IOException {
        Int2ObjectMap<CisSection<S>> sections = chunk.getSections();
        int[] sortedSections = chunk.getSortedSectionIndices();

        dos.writeShort(sortedSections.length);
        ctx.bitWriter.reset();

        for (int sectionY : sortedSections) {
            encodeSection(ctx, sectionY, sections.get(sectionY));
        }
        ctx.bitWriter.flush();

        byte[] sectionData = ctx.bitWriter.toByteArray();
        dos.writeInt(sectionData.length);
        dos.write(sectionData);
    }

    /**
     * Writes block entity data.
     */
    private void writeBlockEntities(DataOutputStream dos, ChunkDelta<S, N> delta) throws IOException {
        Long2ObjectMap<N> bes = delta.getBlockEntities();
        dos.writeInt(bes.size());

        for (Long2ObjectMap.Entry<N> entry : bes.long2ObjectEntrySet()) {
            long p = entry.getLongKey();
            int packedPos = (int) BlockInstruction.packPos(
                    BlockInstruction.unpackX(p),
                    BlockInstruction.unpackY(p),
                    BlockInstruction.unpackZ(p));
            dos.writeInt(packedPos);
            nbtAdapter.write(entry.getValue(), dos);
        }
    }

    /**
     * Writes entity data.
     */
    private void writeEntities(DataOutputStream dos, ChunkDelta<S, N> delta) throws IOException {
        List<N> entities = delta.getEntitiesList();
        int entityCount = (entities != null) ? entities.size() : 0;
        dos.writeInt(entityCount);

        if (entityCount > 0) {
            for (N entity : entities) {
                nbtAdapter.write(entity, dos);
            }
        }
    }

    /**
     * Encodes a single section.
     */
    private void encodeSection(EncoderContext<S> ctx, int sectionY, CisSection<S> section) {
        ctx.bitWriter.writeZigZag(sectionY, CisConstants.SECTION_Y_BITS);

        if (section.mode == CisSection.MODE_EMPTY) {
            ctx.bitWriter.write(CisConstants.SECTION_ENCODING_SPARSE, 1);
            ctx.bitWriter.write(0, CisConstants.BLOCK_COUNT_BITS);
            return;
        }

        if (section.mode == CisSection.MODE_SPARSE) {
            encodeSparseSection(ctx, section);
        } else {
            encodeDenseSection(ctx, section);
        }
    }

    /**
     * Encodes a sparse section.
     */
    private void encodeSparseSection(EncoderContext<S> ctx, CisSection<S> section) {
        ctx.bitWriter.write(CisConstants.SECTION_ENCODING_SPARSE, 1);
        ctx.bitWriter.write(section.sparseSize, CisConstants.BLOCK_COUNT_BITS);

        if (section.sparseSize > 0) {
            int globalBits = calculateBitsNeeded(ctx.globalIdMap.size());

            for (int i = 0; i < section.sparseSize; i++) {
                ctx.bitWriter.write(section.sparseKeys[i] & 0xFFFF, 12);

                S state = (S) section.sparseValues[i];
                int globalIdx = ctx.globalIdMap.getInt(state);
                ctx.bitWriter.write(globalIdx != -1 ? globalIdx : 0, globalBits);
            }
        }
    }

    /**
     * Encodes a dense section.
     */
    private void encodeDenseSection(EncoderContext<S> ctx, CisSection<S> section) {
        ctx.bitWriter.write(CisConstants.SECTION_ENCODING_DENSE, 1);

        ctx.fastLocalPaletteIndex.clear();
        ctx.fastLocalPaletteIndex.defaultReturnValue(-1);
        ctx.localPalette.clear();

        buildLocalPalette(ctx, section.denseBlocks);

        int localAirIndex = ensureAirInPalette(ctx);
        int localSize = ctx.localPalette.size();

        ctx.bitWriter.write(localSize, CisConstants.PALETTE_SIZE_BITS);

        int globalBits = calculateBitsNeeded(ctx.globalIdMap.size());
        for (int globalIdx : ctx.localPalette) {
            ctx.bitWriter.write(globalIdx, globalBits);
        }

        int bitsPerBlock = calculateBitsNeeded(localSize);
        if (bitsPerBlock > 0) {
            writeBlockData(ctx, section.denseBlocks, localAirIndex, bitsPerBlock);
        }
    }

    protected void buildLocalPalette(EncoderContext<S> ctx, Object[] states) {
        for (int i = 0; i < SECTION_VOLUME; i++) {
            S state = (S) states[i];
            if (state != null && !stateAdapter.isAir(state) && !ctx.fastLocalPaletteIndex.containsKey(state)) {
                int globalIdx = ctx.globalIdMap.getInt(state);
                if (globalIdx != -1) {
                    ctx.fastLocalPaletteIndex.put(state, ctx.localPalette.size());
                    ctx.localPalette.add(globalIdx);
                }
            }
        }
    }

    protected int ensureAirInPalette(EncoderContext<S> ctx) {
        if (ctx.fastLocalPaletteIndex.containsKey(airState)) {
            return ctx.fastLocalPaletteIndex.getInt(airState);
        }

        int airGlobalIdx = ctx.globalIdMap.getInt(airState);
        if (airGlobalIdx == -1) {
            airGlobalIdx = 0;
        }

        int localAirIndex = ctx.localPalette.size();
        ctx.localPalette.add(airGlobalIdx);
        ctx.fastLocalPaletteIndex.put(airState, localAirIndex);
        return localAirIndex;
    }

    /**
     * write the block data for a dense section.
     */
    private void writeBlockData(EncoderContext<S> ctx, Object[] states, int localAirIndex, int bitsPerBlock) {
        for (int i = 0; i < SECTION_VOLUME; i++) {
            S state = (S) states[i];
            int localIdx = (state == null || stateAdapter.isAir(state))
                    ? localAirIndex
                    : ctx.fastLocalPaletteIndex.getInt(state);

            ctx.bitWriter.write(localIdx, bitsPerBlock);
        }
    }

    protected static int calculateBitsNeeded(int maxValue) {
        return AbstractCisDecoder.calculateBitsNeeded(maxValue);
    }

    public static class EncoderContext<S> {
        public final ByteArrayOutputStream mainBuffer = new ByteArrayOutputStream(16384);
        public final BitWriter bitWriter = new BitWriter(8192);
        public final Object2IntMap<S> globalIdMap = new Object2IntOpenHashMap<>();
        public final List<Integer> localPalette = new ArrayList<>(64);
        public final Reference2IntMap<S> fastLocalPaletteIndex = new Reference2IntOpenHashMap<>();

        public void reset() {
            mainBuffer.reset();
            globalIdMap.clear();
        }
    }
}
