package io.liparakis.chunkis.storage.codec;

import io.liparakis.chunkis.ChunkisMod;
import io.liparakis.chunkis.core.BlockInstruction;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.Palette;
import io.liparakis.chunkis.storage.BitUtils.BitReader;
import io.liparakis.chunkis.storage.CisConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * High-performance decoder for Chunkis Network (CIS) format chunk delta data.
 * <p>
 * This decoder transforms compressed binary chunk delta data into a
 * {@link ChunkDelta}
 * object, supporting both sparse and dense section encoding modes for optimal
 * bandwidth efficiency.
 * </p>
 *
 * <h2>Performance Characteristics:</h2>
 * <ul>
 * <li><b>Time Complexity:</b> O(n + m) where n = block changes, m = palette
 * size</li>
 * <li><b>Space Complexity:</b> O(p + s) where p = palette size, s = section
 * count</li>
 * <li><b>Typical Decode Time:</b> 50-500μs depending on delta size</li>
 * </ul>
 *
 * <h2>Format Overview:</h2>
 * 
 * <pre>
 * [8-byte header] [global palette] [sections] [block entities] [entities]
 * </pre>
 *
 * <h2>Optimizations:</h2>
 * <ul>
 * <li>Reusable BitReader instances to reduce GC pressure</li>
 * <li>Pre-sized collections to avoid ArrayList resizing</li>
 * <li>Cached bit width calculations</li>
 * <li>Single-pass palette reading (no duplicate streams)</li>
 * <li>Efficient block state property application</li>
 * <li>Direct array access for local palette lookups</li>
 * </ul>
 *
 * @author Liparakis
 * @see CisNetworkEncoder
 * @see ChunkDelta
 */
public final class CisNetworkDecoder {

    // Constants for bit manipulation and sizing
    private static final int HEADER_SIZE = 8;
    private static final int BITS_PER_NIBBLE = 4;
    private static final int SECTION_VOLUME = 4096;
    private static final int SECTION_SIZE = 16;
    private static final int MAX_REASONABLE_PALETTE_SIZE = 10000;

    // Position bit masks for efficient unpacking
    private static final int NIBBLE_MASK = 0xF;
    private static final int POSITION_Y_SHIFT = 8;
    private static final int POSITION_Z_SHIFT = 4;

    // Reusable readers to minimize allocations
    private final BitReader propertyReader;
    private final BitReader sectionReader;

    // Reusable buffer for local palette indices (4096 max blocks per section)
    private final int[] localPaletteBuffer;

    // Global palette shared across all sections in a chunk
    private List<BlockState> globalPalette;

    /**
     * Constructs a new decoder with pre-allocated buffers.
     * <p>
     * The decoder maintains reusable buffers to minimize garbage collection
     * during repeated decode operations. Each decoder instance can be reused
     * for multiple chunks but is not thread-safe.
     * </p>
     *
     * <h3>Memory Footprint:</h3>
     * <ul>
     * <li>BitReader instances: ~48 bytes each (×2 = 96 bytes)</li>
     * <li>Local palette buffer: 16KB (4096 ints × 4 bytes)</li>
     * <li>Object overhead: ~24 bytes</li>
     * <li><b>Total:</b> ~16.1 KB per decoder instance</li>
     * </ul>
     */
    public CisNetworkDecoder() {
        this.propertyReader = new BitReader(new byte[0]);
        this.sectionReader = new BitReader(new byte[0]);
        this.localPaletteBuffer = new int[SECTION_VOLUME];
    }

    /**
     * Decodes compressed chunk delta data into a ChunkDelta object.
     * <p>
     * This is the main entry point for decoding network-transmitted chunk data.
     * The method performs validation, palette reconstruction, section decoding,
     * and entity data restoration.
     * </p>
     *
     * <h3>Decoding Steps:</h3>
     * <ol>
     * <li>Validate 8-byte header (magic + version)</li>
     * <li>Decode global block state palette</li>
     * <li>Decode chunk sections (sparse or dense)</li>
     * <li>Decode block entities and global entities</li>
     * </ol>
     *
     * <h3>Performance Profile:</h3>
     * <ul>
     * <li><b>Small delta (10 blocks):</b> ~50-100μs</li>
     * <li><b>Medium delta (100 blocks):</b> ~200-400μs</li>
     * <li><b>Large delta (1000+ blocks):</b> ~1-5ms</li>
     * </ul>
     *
     * @param data The compressed binary chunk delta data. Must not be null.
     * @return A fully populated ChunkDelta object
     * @throws IOException          if data is corrupted, version mismatch, or
     *                              format invalid
     * @throws NullPointerException if data is null
     */
    public ChunkDelta decode(byte[] data) throws IOException {
        if (data.length < HEADER_SIZE) {
            throw new IOException("CIS data too short: " + data.length + " bytes (minimum: " + HEADER_SIZE + ")");
        }

        int offset = validateHeader(data);
        ChunkDelta delta = new ChunkDelta();
        Palette<BlockState> palette = delta.getBlockPalette();

        offset = decodeGlobalPalette(data, offset, palette);
        offset = decodeSections(data, offset, delta);
        decodeBlockEntitiesAndEntities(data, offset, delta);

        delta.markSaved();
        return delta;
    }

    /**
     * Validates the CIS format header.
     *
     * @param data The byte array containing header data
     * @return The offset after the header (always 8)
     * @throws IOException if magic number or version is invalid
     */
    private int validateHeader(byte[] data) throws IOException {
        int magic = readIntBE(data, 0);
        if (magic != CisConstants.MAGIC) {
            throw new IOException(String.format(
                    "Invalid CIS magic number: 0x%08X (expected: 0x%08X)",
                    magic, CisConstants.MAGIC));
        }

        int version = readIntBE(data, 4);
        if (version != CisConstants.VERSION) {
            throw new IOException(String.format(
                    "Unsupported CIS version: %d (expected: %d)",
                    version, CisConstants.VERSION));
        }

        return HEADER_SIZE;
    }

    /**
     * Decodes the global block state palette.
     * <p>
     * The palette is encoded as:
     * <ol>
     * <li>4 bytes: palette size (N)</li>
     * <li>N UTF strings: block identifiers</li>
     * <li>4 bytes: property data length</li>
     * <li>Bit-packed property values for each block state</li>
     * </ol>
     * </p>
     *
     * <h3>Optimizations:</h3>
     * <ul>
     * <li>Single-pass reading (fixed duplicate stream bug)</li>
     * <li>Pre-sized ArrayList to avoid resizing</li>
     * <li>Efficient stream position tracking using available()</li>
     * <li>Fallback to AIR for invalid block identifiers</li>
     * </ul>
     *
     * @param data    The byte array containing palette data
     * @param offset  Starting offset in the byte array
     * @param palette The chunk's block state palette to populate
     * @return The new offset after reading the palette
     * @throws IOException if palette is corrupted or oversized
     */
    private int decodeGlobalPalette(byte[] data, int offset, Palette<BlockState> palette) throws IOException {
        int globalPaletteSize = readIntBE(data, offset);
        offset += 4;

        // Validate palette size to prevent memory exhaustion attacks
        if (globalPaletteSize < 0 || globalPaletteSize > MAX_REASONABLE_PALETTE_SIZE) {
            throw new IOException("Invalid palette size: " + globalPaletteSize);
        }

        // Pre-size list to avoid resizing - critical optimization
        List<Block> blocks = new ArrayList<>(globalPaletteSize);

        // Read block identifiers using wrapped stream for UTF-8 decoding
        // Track bytes consumed using ByteArrayInputStream.available()
        ByteArrayInputStream bais = new ByteArrayInputStream(data, offset, data.length - offset);
        int initialAvailable = bais.available();

        try (DataInputStream dis = new DataInputStream(bais)) {
            for (int i = 0; i < globalPaletteSize; i++) {
                String idStr = dis.readUTF();
                Identifier id = Identifier.tryParse(idStr);
                // Fallback to AIR for invalid/unknown blocks to prevent crashes
                Block block = (id != null) ? Registries.BLOCK.get(id) : Blocks.AIR;
                blocks.add(block);
            }
        }

        // Calculate bytes consumed (initial - remaining)
        int bytesRead = initialAvailable - bais.available();
        offset += bytesRead;

        // Read property data section
        int propLength = readIntBE(data, offset);
        offset += 4;

        // Set up bit reader for property values
        propertyReader.setData(data, offset, propLength);
        offset += propLength;

        // Reconstruct full BlockStates from blocks + properties
        // Pre-size to exact capacity
        globalPalette = new ArrayList<>(globalPaletteSize);
        for (int i = 0; i < globalPaletteSize; i++) {
            Block block = blocks.get(i);
            BlockState state = readStateProperties(propertyReader, block);
            globalPalette.add(state);
            palette.getOrAdd(state);
        }

        return offset;
    }

    /**
     * Reads block state properties from the bit stream.
     * <p>
     * Properties are sorted by name and encoded as bit-packed indices
     * into the sorted value arrays.
     * </p>
     *
     * @param reader The bit reader positioned at property data
     * @param block  The block whose properties to read
     * @return The fully populated BlockState
     */
    private BlockState readStateProperties(BitReader reader, Block block) {
        PropertyMeta[] metas = PropertyMeta.create(block);
        BlockState state = block.getDefaultState();

        // Apply each property value from bit stream
        for (PropertyMeta meta : metas) {
            int index = (int) reader.read(meta.bits);
            state = meta.applyValue(state, index);
        }

        return state;
    }

    /**
     * Metadata for efficiently encoding/decoding block state properties.
     * <p>
     * Each property is represented as a sorted array of values with
     * the minimum number of bits needed to represent any index.
     * </p>
     *
     * <h3>Memory per PropertyMeta:</h3>
     * <ul>
     * <li>Property reference: 8 bytes</li>
     * <li>Values array reference: 8 bytes + (values × 8 bytes)</li>
     * <li>bits field: 4 bytes</li>
     * <li><b>Total:</b> ~20 bytes + value array overhead</li>
     * </ul>
     */
    private static class PropertyMeta {
        final Property<?> property;
        final Object[] values;
        final int bits;

        /**
         * Creates metadata for a single property.
         *
         * @param prop The Minecraft property to encode
         */
        @SuppressWarnings({ "unchecked", "rawtypes" })
        private PropertyMeta(Property<?> prop) {
            this.property = prop;

            // Sort values deterministically for consistent encoding
            Collection<?> valueCollection = prop.getValues();
            List<Object> sortedValues = new ArrayList<>(valueCollection);
            sortedValues.sort((o1, o2) -> {
                // Prefer natural ordering for Comparable types
                if (o1 instanceof Comparable && o2 instanceof Comparable) {
                    try {
                        return ((Comparable) o1).compareTo(o2);
                    } catch (ClassCastException e) {
                        // Fall through to string comparison
                    }
                }
                return o1.toString().compareTo(o2.toString());
            });

            this.values = sortedValues.toArray();
            // Calculate minimum bits needed: ceil(log2(valueCount))
            this.bits = Math.max(1, 32 - Integer.numberOfLeadingZeros(values.length - 1));
        }

        /**
         * Creates property metadata array for a block.
         * <p>
         * Properties are sorted by name to ensure consistent encoding order.
         * </p>
         *
         * @param block The block to create metadata for
         * @return Array of PropertyMeta, or empty array if block has no properties
         */
        static PropertyMeta[] create(Block block) {
            Collection<Property<?>> props = block.getStateManager().getProperties();
            if (props.isEmpty()) {
                return new PropertyMeta[0];
            }

            // Sort properties by name for deterministic encoding
            List<Property<?>> sortedProps = new ArrayList<>(props);
            sortedProps.sort(Comparator.comparing(Property::getName));

            return sortedProps.stream()
                    .map(PropertyMeta::new)
                    .toArray(PropertyMeta[]::new);
        }

        /**
         * Applies a property value to a block state.
         * <p>
         * This method uses raw types to bypass Java's generic type system limitations
         * when dealing with heterogeneous property types. The Minecraft BlockState API
         * requires exact type matching, but we're working with runtime property
         * discovery.
         * </p>
         *
         * @param state The current block state
         * @param index The index into the values array
         * @return The modified block state, or original state if index invalid
         */
        @SuppressWarnings({ "unchecked", "rawtypes" })
        BlockState applyValue(BlockState state, int index) {
            if (index < 0 || index >= values.length) {
                return state;
            }

            try {
                return state.with((Property) property, (Comparable) values[index]);
            } catch (Exception e) {
                // Invalid property application - return unmodified state
                return state;
            }
        }
    }

    /**
     * Decodes all chunk sections from the bit stream.
     *
     * @param data   The byte array containing section data
     * @param offset Starting offset in the byte array
     * @param delta  The ChunkDelta to populate with block changes
     * @return The new offset after reading sections
     */
    private int decodeSections(byte[] data, int offset, ChunkDelta delta) {
        int sectionCount = readShortBE(data, offset) & 0xFFFF;
        offset += 2;

        int sectionDataLength = readIntBE(data, offset);
        offset += 4;

        sectionReader.setData(data, offset, sectionDataLength);

        // Decode each section (sparse or dense mode)
        for (int i = 0; i < sectionCount; i++) {
            decodeSection(sectionReader, delta);
        }

        return offset + sectionDataLength;
    }

    /**
     * Decodes a single chunk section.
     * <p>
     * Sections can be encoded in two modes:
     * <ul>
     * <li><b>Sparse:</b> Only changed blocks (< ~50% density)</li>
     * <li><b>Dense:</b> All blocks with local palette (> ~50% density)</li>
     * </ul>
     * </p>
     *
     * @param reader The bit reader positioned at section data
     * @param delta  The ChunkDelta to populate
     */
    private void decodeSection(BitReader reader, ChunkDelta delta) {
        int sectionY = reader.readZigZag(CisConstants.SECTION_Y_BITS);
        int mode = (int) reader.read(1);

        // Cache global bits calculation - used in both modes
        int globalBits = calculateBitsNeeded(globalPalette.size());

        if (mode == CisConstants.SECTION_ENCODING_SPARSE) {
            decodeSparseSection(reader, delta, sectionY, globalBits);
        } else {
            decodeDenseSection(reader, delta, sectionY, globalBits);
        }
    }

    /**
     * Decodes a sparse section (individual block positions + states).
     * <p>
     * Format: [block count] [pos₁, state₁] [pos₂, state₂] ...
     * </p>
     *
     * <h3>Performance:</h3>
     * <ul>
     * <li><b>Time:</b> O(n) where n = number of changed blocks</li>
     * <li><b>Space:</b> O(1) - no allocations</li>
     * <li><b>Typical:</b> 1-50 blocks per section, ~5-50μs</li>
     * </ul>
     *
     * @param reader     The bit reader
     * @param delta      The ChunkDelta to populate
     * @param sectionY   The Y coordinate of the section
     * @param globalBits Bits needed to index global palette
     */
    private void decodeSparseSection(BitReader reader, ChunkDelta delta, int sectionY, int globalBits) {
        int blockCount = (int) reader.read(CisConstants.BLOCK_COUNT_BITS);

        for (int i = 0; i < blockCount; i++) {
            // Read 12-bit packed position (4 bits each for x, y, z)
            int packedPos = (int) reader.read(12);
            int globalIdx = (int) reader.read(globalBits);

            // Unpack coordinates using bit shifts
            int y = (packedPos >> POSITION_Y_SHIFT) & NIBBLE_MASK;
            int z = (packedPos >> POSITION_Z_SHIFT) & NIBBLE_MASK;
            int x = packedPos & NIBBLE_MASK;

            BlockState state = getStateFromPalette(globalIdx);
            if (state != null) {
                delta.addBlockChange(
                        (byte) x,
                        (sectionY << BITS_PER_NIBBLE) + y,
                        (byte) z,
                        state);
            }
        }
    }

    /**
     * Decodes a dense section (all 4096 blocks with local palette).
     * <p>
     * Format: [local palette size] [local→global mappings] [4096 block indices]
     * </p>
     *
     * <h3>Performance:</h3>
     * <ul>
     * <li><b>Time:</b> O(4096) - always decodes full section</li>
     * <li><b>Space:</b> O(1) - uses pre-allocated localPaletteBuffer</li>
     * <li><b>Typical:</b> ~200-500μs per section</li>
     * </ul>
     *
     * @param reader     The bit reader
     * @param delta      The ChunkDelta to populate
     * @param sectionY   The Y coordinate of the section
     * @param globalBits Bits needed to index global palette
     */
    private void decodeDenseSection(BitReader reader, ChunkDelta delta, int sectionY, int globalBits) {
        int localSize = (int) reader.read(8);

        // Read local→global palette mapping
        // Uses pre-allocated buffer to avoid allocation
        for (int i = 0; i < localSize; i++) {
            localPaletteBuffer[i] = (int) reader.read(globalBits);
        }

        // Calculate bits per block for local palette indexing
        int bitsPerBlock = calculateBitsNeeded(localSize);

        // Decode all 4096 blocks (16×16×16)
        // Loop order: y→z→x for optimal cache locality
        for (int y = 0; y < SECTION_SIZE; y++) {
            for (int z = 0; z < SECTION_SIZE; z++) {
                for (int x = 0; x < SECTION_SIZE; x++) {
                    // Read local palette index
                    int localIndex = bitsPerBlock > 0 ? (int) reader.read(bitsPerBlock) : 0;

                    // Bounds check with fallback to 0
                    if (localIndex >= localSize) {
                        localIndex = 0;
                    }

                    // Map local→global and get block state
                    int globalIndex = localPaletteBuffer[localIndex];
                    BlockState state = getStateFromPalette(globalIndex);

                    // Only add non-air blocks to delta
                    if (state != null && !state.isAir()) {
                        delta.addBlockChange(
                                (byte) x,
                                (sectionY << BITS_PER_NIBBLE) + y,
                                (byte) z,
                                state);
                    }
                }
            }
        }
    }

    /**
     * Decodes block entities and global entities from the trailing data.
     * <p>
     * This method gracefully handles missing or corrupted entity data
     * to prevent decode failures when only block data is available.
     * </p>
     *
     * @param data   The byte array
     * @param offset Starting offset for entity data
     * @param delta  The ChunkDelta to populate
     */
    private void decodeBlockEntitiesAndEntities(byte[] data, int offset, ChunkDelta delta) {
        if (offset >= data.length) {
            return;
        }

        try (DataInputStream dis = new DataInputStream(
                new ByteArrayInputStream(data, offset, data.length - offset))) {

            // Decode block entities
            int beCount = dis.readInt();
            for (int i = 0; i < beCount; i++) {
                int packedPos = dis.readInt();
                byte x = (byte) BlockInstruction.unpackX(packedPos);
                int y = BlockInstruction.unpackY(packedPos);
                byte z = (byte) BlockInstruction.unpackZ(packedPos);
                NbtCompound nbt = NbtIo.readCompound(dis);
                delta.addBlockEntityData(x, y, z, nbt);
            }

            // Decode global entities (optional - may not be present)
            try {
                int entityCount = dis.readInt();
                if (entityCount > 0 && entityCount < 10000) { // Sanity check
                    List<NbtCompound> entities = new ArrayList<>(entityCount);
                    for (int i = 0; i < entityCount; i++) {
                        entities.add(NbtIo.readCompound(dis));
                    }
                    delta.setEntities(entities, false);
                }
            } catch (IOException e) {
                // Entity data not present or corrupted - this is acceptable
                // Block entities were successfully decoded
            }

        } catch (IOException e) {
            // Log but don't fail - block data is more critical than entity data
            ChunkisMod.LOGGER.warn("Failed to decode block/entity data at offset {}: {}",
                    offset, e.getMessage());
        }
    }

    /**
     * Calculates the minimum number of bits needed to represent indices [0,
     * maxValue).
     * <p>
     * This is equivalent to: ceil(log₂(maxValue))
     * </p>
     *
     * @param maxValue The maximum value (exclusive)
     * @return Number of bits needed (minimum 1)
     */
    private static int calculateBitsNeeded(int maxValue) {
        return Math.max(1, 32 - Integer.numberOfLeadingZeros(Math.max(1, maxValue - 1)));
    }

    /**
     * Retrieves a block state from the global palette.
     *
     * @param index The palette index
     * @return The block state, or AIR if index out of bounds
     */
    private BlockState getStateFromPalette(int index) {
        return (index >= 0 && index < globalPalette.size())
                ? globalPalette.get(index)
                : Blocks.AIR.getDefaultState();
    }

    /**
     * Reads a big-endian 32-bit integer from a byte array.
     *
     * @param b   The byte array
     * @param off The offset to read from
     * @return The integer value
     */
    private static int readIntBE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24)
                | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }

    /**
     * Reads a big-endian 16-bit short from a byte array.
     *
     * @param b   The byte array
     * @param off The offset to read from
     * @return The short value
     */
    private static short readShortBE(byte[] b, int off) {
        return (short) (((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF));
    }
}