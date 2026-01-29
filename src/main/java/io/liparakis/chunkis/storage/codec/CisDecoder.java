package io.liparakis.chunkis.storage.codec;

import io.liparakis.chunkis.core.BlockInstruction;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.Palette;
import io.liparakis.chunkis.storage.BitUtils.BitReader;
import io.liparakis.chunkis.storage.CisConstants;
import io.liparakis.chunkis.storage.CisMapping;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Decoder for Chunkis CIS format (V7) with support for paletted sections and
 * dynamic
 * property packing.
 * <p>
 * This decoder is responsible for reading the binary .cis format and
 * reconstructing
 * a {@link ChunkDelta}. It uses an optimized, zero-copy reading approach with
 * reusable internal buffers to minimize object allocation and maximize
 * performance.
 */
public final class CisDecoder {
    private static final int HEADER_SIZE = 8;
    private static final int BITS_PER_NIBBLE = 4;
    private static final int SECTION_VOLUME = 4096;
    private static final int SECTION_SIZE = 16;

    /**
     * Bit reader for property data.
     */
    private final BitReader propertyReader;

    /**
     * Bit reader for section data.
     */
    private final BitReader sectionReader;

    /**
     * Reusable buffer for local palette indices during dense section decoding.
     */
    private final int[] localPaletteBuffer;

    /**
     * The global palette of block states decoded from the file.
     */
    private List<BlockState> globalPalette;

    /**
     * Creates a new CisDecoder instance with reusable internal buffers.
     */
    public CisDecoder() {
        this.propertyReader = new BitReader(new byte[0]);
        this.sectionReader = new BitReader(new byte[0]);
        this.localPaletteBuffer = new int[SECTION_VOLUME];
    }

    /**
     * Decodes a byte array into a ChunkDelta using the provided block state
     * mapping.
     *
     * @param data    the encoded CIS format data
     * @param mapping the block state mapping for property deserialization
     * @return the decoded ChunkDelta
     * @throws IOException if the data is invalid or corrupted
     */
    public ChunkDelta decode(byte[] data, CisMapping mapping) throws IOException {
        if (data.length < HEADER_SIZE) {
            throw new IOException(String.format(
                    "CIS file too short: got %d bytes, need at least %d",
                    data.length, HEADER_SIZE));
        }

        int offset = validateHeader(data);

        ChunkDelta delta = new ChunkDelta();
        Palette<BlockState> palette = delta.getBlockPalette();

        offset = decodeGlobalPalette(data, offset, mapping, palette);
        offset = decodeSections(data, offset, delta);
        decodeBlockEntitiesAndEntities(data, offset, delta);

        delta.markSaved();
        return delta;
    }

    /**
     * Validates the file header (magic and version) and returns the offset after
     * the header.
     *
     * @param data the encoded data
     * @return the offset after the header
     * @throws IOException if the header is invalid or the version is unsupported
     */
    private int validateHeader(byte[] data) throws IOException {
        int magic = readIntBE(data, 0);
        if (magic != CisConstants.MAGIC) {
            throw new IOException("Invalid CIS4 Magic");
        }

        int version = readIntBE(data, 4);
        if (version != CisConstants.VERSION) {
            throw new IOException("Unsupported CIS version: " + version + " (Expected " + CisConstants.VERSION + ")");
        }

        return HEADER_SIZE;
    }

    /**
     * Decodes the global palette from the data stream.
     * <p>
     * Reconstructs the block states using the provided {@link CisMapping} and the
     * encoded
     * property bit stream.
     *
     * @param data    the encoded data
     * @param offset  the current reading offset
     * @param mapping the block state mapping
     * @param palette the target palette to populate
     * @return the updated offset after reading the palette
     * @throws IOException if the palette data is corrupted or truncated
     */
    private int decodeGlobalPalette(byte[] data, int offset, CisMapping mapping, Palette<BlockState> palette)
            throws IOException {
        if (offset + 4 > data.length) {
            throw new IOException("Truncated data: cannot read global palette size");
        }

        int globalPaletteSize = readIntBE(data, offset);
        offset += 4;

        // Sanity check: palette size should be reasonable (max ~2000 unique blocks in
        // vanilla + mods)
        if (globalPaletteSize < 0 || globalPaletteSize > 10000) {
            throw new IOException(String.format(
                    "Invalid palette size: %d (expected 0-10000). Data is likely corrupted or from incompatible version.",
                    globalPaletteSize));
        }

        int requiredBytes = globalPaletteSize * 2 + 4;
        if (offset + requiredBytes > data.length) {
            throw new IOException(String.format(
                    "Truncated data: expected %d bytes for palette, but only %d bytes remaining (palette size: %d). " +
                            "Data may be corrupted or from an incompatible version.",
                    requiredBytes, data.length - offset, globalPaletteSize));
        }

        int[] blockIds = new int[globalPaletteSize];
        for (int i = 0; i < globalPaletteSize; i++) {
            blockIds[i] = readShortBE(data, offset) & 0xFFFF;
            offset += 2;
        }

        int propLength = readIntBE(data, offset);
        offset += 4;

        if (propLength < 0 || propLength > data.length) {
            throw new IOException(String.format(
                    "Invalid property length: %d (data length: %d). Data is corrupted.",
                    propLength, data.length));
        }

        if (offset + propLength > data.length) {
            throw new IOException(String.format(
                    "Truncated data: expected %d bytes for properties, but only %d bytes remaining",
                    propLength, data.length - offset));
        }

        propertyReader.setData(data, offset, propLength);
        offset += propLength;

        globalPalette = new ArrayList<>(globalPaletteSize);
        for (int i = 0; i < globalPaletteSize; i++) {
            BlockState state = mapping.readStateProperties(propertyReader, blockIds[i]);
            globalPalette.add(state);
            palette.getOrAdd(state);
        }

        return offset;
    }

    /**
     * Decodes all chunk sections from the data stream.
     *
     * @param data   the encoded data
     * @param offset the current reading offset
     * @param delta  the target ChunkDelta
     * @return the offset after reading all section data
     * @throws IOException if the section data is corrupted or truncated
     */
    private int decodeSections(byte[] data, int offset, ChunkDelta delta) throws IOException {
        if (offset + 6 > data.length) {
            throw new IOException("Truncated data: cannot read section header");
        }

        int sectionCount = readShortBE(data, offset) & 0xFFFF;
        offset += 2;

        int sectionDataLength = readIntBE(data, offset);
        offset += 4;

        if (offset + sectionDataLength > data.length) {
            throw new IOException(String.format(
                    "Truncated data: expected %d bytes for sections, but only %d bytes remaining",
                    sectionDataLength, data.length - offset));
        }

        sectionReader.setData(data, offset, sectionDataLength);

        for (int i = 0; i < sectionCount; i++) {
            decodeSection(sectionReader, delta);
        }

        return offset + sectionDataLength;
    }

    /**
     * Decodes block entities and global entities using standard NBT reading.
     *
     * @param data   the encoded data
     * @param offset the current reading offset
     * @param delta  the target ChunkDelta
     */
    private void decodeBlockEntitiesAndEntities(byte[] data, int offset, ChunkDelta delta) {
        if (offset >= data.length)
            return;

        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data, offset, data.length - offset))) {
            decodeBlockEntities(dis, delta);
            decodeGlobalEntities(dis, delta);
        } catch (IOException e) {
            // ByteArrayInputStream should not throw
        }
    }

    /**
     * Decodes block entities from the data stream using standard NBT reading.
     *
     * @param dis   the data input stream
     * @param delta the target ChunkDelta
     * @throws IOException if an error occurs during NBT reading
     */
    private void decodeBlockEntities(DataInputStream dis, ChunkDelta delta) throws IOException {
        int beCount = dis.readInt();
        for (int i = 0; i < beCount; i++) {
            int packedPos = dis.readInt();
            byte x = (byte) BlockInstruction.unpackX(packedPos);
            int y = BlockInstruction.unpackY(packedPos);
            byte z = (byte) BlockInstruction.unpackZ(packedPos);

            NbtCompound nbt = NbtIo.readCompound(dis);
            delta.addBlockEntityData(x, y, z, nbt);
        }
    }

    /**
     * Decodes global entities from the data stream until then end of the stream or
     * an error occurs.
     *
     * @param dis   the data input stream
     * @param delta the target ChunkDelta
     */
    private void decodeGlobalEntities(DataInputStream dis, ChunkDelta delta) {
        try {
            int entityCount = dis.readInt();
            List<NbtCompound> entities = new ArrayList<>(entityCount);
            for (int i = 0; i < entityCount; i++) {
                entities.add(NbtIo.readCompound(dis));
            }
            delta.setEntities(entities, false);
        } catch (EOFException e) {
            // End of stream is acceptable
        } catch (IOException e) {
            // Ignore
        }
    }

    /**
     * Decodes a single chunk section, automatically choosing between sparse and
     * dense modes.
     *
     * @param reader the bit reader for the section stream
     * @param delta  the target ChunkDelta
     */
    private void decodeSection(BitReader reader, ChunkDelta delta) {
        int sectionY = reader.readZigZag(CisConstants.SECTION_Y_BITS);
        int mode = (int) reader.read(1);

        int globalBits = calculateBitsNeeded(globalPalette.size());

        if (mode == CisConstants.SECTION_ENCODING_SPARSE) {
            decodeSparseSection(reader, delta, sectionY, globalBits);
        } else {
            decodeDenseSection(reader, delta, sectionY, globalBits);
        }
    }

    /**
     * Decodes a sparse section containing only modified blocks.
     *
     * @param reader     the bit reader
     * @param delta      the target ChunkDelta
     * @param sectionY   the Y-index of the section
     * @param globalBits the number of bits used for global palette indices
     */
    private void decodeSparseSection(BitReader reader, ChunkDelta delta, int sectionY, int globalBits) {
        int blockCount = (int) reader.read(CisConstants.BLOCK_COUNT_BITS);

        for (int i = 0; i < blockCount; i++) {
            int packedPos = (int) reader.read(12);
            int globalIdx = (int) reader.read(globalBits);

            int y = (packedPos >> 8) & 0xF;
            int z = (packedPos >> BITS_PER_NIBBLE) & 0xF;
            int x = packedPos & 0xF;

            BlockState state = getStateFromPalette(globalIdx);

            if (state != null) {
                delta.addBlockChange((byte) x, (sectionY << BITS_PER_NIBBLE) + y, (byte) z, state);
            }
        }
    }

    /**
     * Decodes a dense section containing a full section's worth of data.
     *
     * @param reader     the bit reader
     * @param delta      the target ChunkDelta
     * @param sectionY   the Y-index of the section
     * @param globalBits the number of bits used for global palette indices
     */
    private void decodeDenseSection(BitReader reader, ChunkDelta delta, int sectionY, int globalBits) {
        int localSize = (int) reader.read(8);

        for (int i = 0; i < localSize; i++) {
            localPaletteBuffer[i] = (int) reader.read(globalBits);
        }

        int bitsPerBlock = calculateBitsNeeded(localSize);

        for (int y = 0; y < SECTION_SIZE; y++) {
            for (int z = 0; z < SECTION_SIZE; z++) {
                for (int x = 0; x < SECTION_SIZE; x++) {
                    int localIndex = bitsPerBlock > 0 ? (int) reader.read(bitsPerBlock) : 0;

                    if (localIndex >= localSize) {
                        localIndex = 0;
                    }

                    int globalIndex = localPaletteBuffer[localIndex];
                    BlockState state = getStateFromPalette(globalIndex);

                    if (state != null && !state.isAir()) {
                        delta.addBlockChange((byte) x, (sectionY << BITS_PER_NIBBLE) + y, (byte) z, state);
                    }
                }
            }
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
     * Safely retrieves a BlockState from the global palette by index.
     *
     * @param index the palette index
     * @return the block state, or {@link Blocks#AIR} if the index is invalid
     */
    private BlockState getStateFromPalette(int index) {
        return (index >= 0 && index < globalPalette.size())
                ? globalPalette.get(index)
                : Blocks.AIR.getDefaultState();
    }

    /**
     * Reads a big-endian 32-bit integer from a byte array.
     *
     * @param b   the byte array
     * @param off the offset
     * @return the 32-bit integer
     */
    private static int readIntBE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) |
                ((b[off + 1] & 0xFF) << 16) |
                ((b[off + 2] & 0xFF) << 8) |
                (b[off + 3] & 0xFF);
    }

    /**
     * Reads a big-endian 16-bit short from a byte array.
     *
     * @param b   the byte array
     * @param off the offset
     * @return the 16-bit short
     */
    private static short readShortBE(byte[] b, int off) {
        return (short) (((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF));
    }
}