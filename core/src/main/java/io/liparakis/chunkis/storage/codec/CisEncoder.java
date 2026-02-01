package io.liparakis.chunkis.storage.codec;

import io.liparakis.chunkis.core.BlockInstruction;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.Palette;
import io.liparakis.chunkis.storage.BitUtils.BitWriter;
import io.liparakis.chunkis.storage.CisAdapter;
import io.liparakis.chunkis.storage.CisChunk;
import io.liparakis.chunkis.storage.CisConstants;
import io.liparakis.chunkis.storage.CisSection;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Encoder for Chunkis CIS format (V7) with paletted section storage and dynamic
 * property bit-packing.
 * <p>
 * This encoder converts a {@link ChunkDelta} into the binary .cis format.
 * It uses a {@link ThreadLocal} context to reuse buffers and data structures,
 * significantly reducing garbage collection pressure and improving performance
 * during intensive world saving operations.
 */
public final class CisEncoder {
    private static final int SECTION_VOLUME = 4096;

    /**
     * Thread-local context to minimize allocations during encoding.
     */
    private static final ThreadLocal<EncoderContext> CONTEXT = ThreadLocal.withInitial(EncoderContext::new);

    /**
     * Private constructor to prevent instantiation.
     */
    private CisEncoder() {
    }

    /**
     * Encodes a ChunkDelta into the Chunkis V7 binary format.
     *
     * @param delta   the ChunkDelta to encode
     * @param adapter the block state adapter for property serialization
     * @return the encoded byte array
     * @throws IOException if encoding fails
     */
    public static byte[] encode(ChunkDelta delta, CisAdapter<BlockState> adapter) throws IOException {
        EncoderContext ctx = CONTEXT.get();
        ctx.reset();

        DataOutputStream dos = new DataOutputStream(ctx.mainBuffer);

        // 1. Convert to sparse chunk representation
        CisChunk<BlockState> chunk = fromDelta(delta);

        // 2. Discover all used block states
        List<BlockState> usedStates = collectUsedStates(chunk);

        writeHeader(dos);
        writeGlobalPalette(dos, ctx, usedStates, adapter);
        writeSections(dos, ctx, chunk);
        writeBlockEntities(dos, delta);
        writeEntities(dos, delta);

        return ctx.mainBuffer.toByteArray();
    }

    /**
     * Reconstructs a CisChunk from a compressed delta.
     * Performance is optimal when BlockInstructions are sorted by Y coordinate.
     *
     * @param delta the ChunkDelta containing block instructions and palette
     * @return a new CisChunk populated with the delta's blocks
     */
    private static CisChunk<BlockState> fromDelta(ChunkDelta delta) {
        CisChunk<BlockState> chunk = new CisChunk<>();
        Palette<BlockState> palette = delta.getBlockPalette();

        for (BlockInstruction ins : delta.getBlockInstructions()) {
            BlockState state = palette.get(ins.paletteIndex());
            if (state != null) {
                chunk.addBlock(ins.x(), ins.y(), ins.z(), state);
            }
        }
        return chunk;
    }

    /**
     * Collects all unique block states actually used across all sections of the
     * chunk.
     * Always includes {@link Blocks#AIR} as the first entry (id 0).
     *
     * @param chunk the chunk to scan
     * @return a list of unique block states
     */
    private static List<BlockState> collectUsedStates(CisChunk<BlockState> chunk) {
        Object2IntMap<BlockState> uniqueStates = new Object2IntOpenHashMap<>();
        BlockState air = Blocks.AIR.getDefaultState();
        uniqueStates.put(air, 0); // Always include air

        for (CisSection<BlockState> section : chunk.getSections().values()) {
            if (section.mode == CisSection.MODE_SPARSE) {
                for (int i = 0; i < section.sparseSize; i++) {
                    uniqueStates.put((BlockState) section.sparseValues[i], 0);
                }
            } else if (section.mode == CisSection.MODE_DENSE) {
                for (Object o : section.denseBlocks) {
                    BlockState state = (BlockState) o;
                    if (state != null && !state.isAir()) {
                        uniqueStates.put(state, 0);
                    }
                }
            }
        }

        return new ArrayList<>(uniqueStates.keySet());
    }

    /**
     * Writes the CIS file header (magic and version).
     *
     * @param dos the output stream
     * @throws IOException if an error occurs during writing
     */
    private static void writeHeader(DataOutputStream dos) throws IOException {
        dos.writeInt(CisConstants.MAGIC);
        dos.writeInt(CisConstants.VERSION);
    }

    /**
     * Writes the global palette, including block IDs and their encoded properties.
     *
     * @param dos           the output stream
     * @param ctx           the encoder context
     * @param globalPalette the list of unique states to write
     * @param adapter       the block state adapter
     * @throws IOException if an error occurs during writing
     */
    private static void writeGlobalPalette(DataOutputStream dos, EncoderContext ctx, List<BlockState> globalPalette,
            CisAdapter<BlockState> adapter) throws IOException {
        ctx.globalIdMap.defaultReturnValue(-1);
        dos.writeInt(globalPalette.size());
        ctx.bitWriter.reset();

        for (int i = 0; i < globalPalette.size(); i++) {
            BlockState state = globalPalette.get(i);
            dos.writeShort(adapter.getBlockId(state));
            adapter.writeStateProperties(ctx.bitWriter, state);
            ctx.globalIdMap.put(state, i);
        }

        byte[] palettePropertyData = ctx.bitWriter.toByteArray();
        dos.writeInt(palettePropertyData.length);
        dos.write(palettePropertyData);
    }

    /**
     * Writes all sections in the chunk to the output stream.
     *
     * @param dos   the output stream
     * @param ctx   the encoder context
     * @param chunk the chunk containing the sections
     * @throws IOException if an error occurs during writing
     */
    private static void writeSections(DataOutputStream dos, EncoderContext ctx, CisChunk<BlockState> chunk)
            throws IOException {
        Int2ObjectMap<CisSection<BlockState>> sections = chunk.getSections();
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
     * Writes block entity data (NBT) for the chunk.
     *
     * @param dos   the output stream
     * @param delta the source delta
     * @throws IOException if an error occurs during writing
     */
    private static void writeBlockEntities(DataOutputStream dos, ChunkDelta delta) throws IOException {
        Long2ObjectMap<NbtCompound> bes = delta.getBlockEntities();
        dos.writeInt(bes.size());

        for (Long2ObjectMap.Entry<NbtCompound> entry : bes.long2ObjectEntrySet()) {
            long p = entry.getLongKey();
            int packedPos = (int) BlockInstruction.packPos(
                    BlockInstruction.unpackX(p),
                    BlockInstruction.unpackY(p),
                    BlockInstruction.unpackZ(p));
            dos.writeInt(packedPos);
            NbtIo.writeCompound(entry.getValue(), dos);
        }
    }

    /**
     * Writes global entity data (NBT) for the chunk.
     *
     * @param dos   the output stream
     * @param delta the source delta
     * @throws IOException if an error occurs during writing
     */
    private static void writeEntities(DataOutputStream dos, ChunkDelta delta) throws IOException {
        List<NbtCompound> entities = delta.getEntitiesList();
        int entityCount = (entities != null) ? entities.size() : 0;
        dos.writeInt(entityCount);

        if (entityCount > 0) {
            for (NbtCompound entity : entities) {
                NbtIo.writeCompound(entity, dos);
            }
        }
    }

    /**
     * Encodes a single chunk section using zigzag encoding for height and
     * automatically choosing between sparse and dense modes.
     *
     * @param ctx      the encoder context
     * @param sectionY the section's Y index
     * @param section  the section data
     */
    private static void encodeSection(EncoderContext ctx, int sectionY, CisSection<BlockState> section) {
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
     * Encodes a section in sparse mode (position-state pairs).
     *
     * @param ctx     the encoder context
     * @param section the section data
     */
    private static void encodeSparseSection(EncoderContext ctx, CisSection<BlockState> section) {
        ctx.bitWriter.write(CisConstants.SECTION_ENCODING_SPARSE, 1);
        ctx.bitWriter.write(section.sparseSize, CisConstants.BLOCK_COUNT_BITS);

        if (section.sparseSize > 0) {
            int globalBits = calculateBitsNeeded(ctx.globalIdMap.size());

            for (int i = 0; i < section.sparseSize; i++) {
                ctx.bitWriter.write(section.sparseKeys[i] & 0xFFFF, 12);

                int globalIdx = ctx.globalIdMap.getInt(section.sparseValues[i]);
                ctx.bitWriter.write(globalIdx != -1 ? globalIdx : 0, globalBits);
            }
        }
    }

    /**
     * Encodes a section in dense mode (local palette + bit-packed indices).
     *
     * @param ctx     the encoder context
     * @param section the section data
     */
    private static void encodeDenseSection(EncoderContext ctx, CisSection<BlockState> section) {
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

    /**
     * Scans the section to build a local palette of unique block states.
     *
     * @param ctx    the encoder context
     * @param states the array of block states in the section (as Object[])
     */
    private static void buildLocalPalette(EncoderContext ctx, Object[] states) {
        for (int i = 0; i < SECTION_VOLUME; i++) {
            BlockState state = (BlockState) states[i];
            if (state != null && !state.isAir() && !ctx.fastLocalPaletteIndex.containsKey(state)) {
                int globalIdx = ctx.globalIdMap.getInt(state);
                if (globalIdx != -1) {
                    ctx.fastLocalPaletteIndex.put(state, ctx.localPalette.size());
                    ctx.localPalette.add(globalIdx);
                }
            }
        }
    }

    /**
     * Ensures that the air block state is present in the local palette.
     *
     * @param ctx the encoder context
     * @return the local index of the air state
     */
    private static int ensureAirInPalette(EncoderContext ctx) {
        BlockState airState = Blocks.AIR.getDefaultState();

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
     * Writes bit-packed local palette indices for all blocks in the section.
     *
     * @param ctx           the encoder context
     * @param states        the block states to write (as Object[])
     * @param localAirIndex the palette index to use for air or null states
     * @param bitsPerBlock  the number of bits to use per block index
     */
    private static void writeBlockData(EncoderContext ctx, Object[] states, int localAirIndex, int bitsPerBlock) {
        for (int i = 0; i < SECTION_VOLUME; i++) {
            BlockState state = (BlockState) states[i];
            int localIdx = (state == null || state.isAir())
                    ? localAirIndex
                    : ctx.fastLocalPaletteIndex.getInt(state);

            ctx.bitWriter.write(localIdx, bitsPerBlock);
        }
    }

    /**
     * Calculates the number of bits needed to represent a value up to
     * {@code maxValue}.
     *
     * @param maxValue the maximum value to represent
     * @return the number of bits required
     */
    private static int calculateBitsNeeded(int maxValue) {
        return Math.max(1, 32 - Integer.numberOfLeadingZeros(Math.max(1, maxValue - 1)));
    }

    /**
     * Reusable encoder context to minimize allocations and garbage collection
     * pressure.
     */
    private static final class EncoderContext {
        final ByteArrayOutputStream mainBuffer = new ByteArrayOutputStream(16384);
        final BitWriter bitWriter = new BitWriter(8192);
        final Object2IntMap<BlockState> globalIdMap = new Object2IntOpenHashMap<>();
        final List<Integer> localPalette = new ArrayList<>(64);
        final Reference2IntMap<BlockState> fastLocalPaletteIndex = new Reference2IntOpenHashMap<>();

        /**
         * Resets the context for a new encoding operation.
         */
        void reset() {
            mainBuffer.reset();
            globalIdMap.clear();
        }
    }
}