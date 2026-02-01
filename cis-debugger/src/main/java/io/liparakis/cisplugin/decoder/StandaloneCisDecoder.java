package io.liparakis.cisplugin.decoder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone decoder for CIS V7 format.
 * No Minecraft dependencies - suitable for IntelliJ plugin use.
 */
public final class StandaloneCisDecoder {
    private static final int HEADER_SIZE = 8;
    private static final int SECTION_SIZE = 16;
    private static final int SECTION_VOLUME = 4096;

    private final BitReader sectionReader = new BitReader();
    private final int[] localPaletteBuffer = new int[SECTION_VOLUME];
    private List<SimpleBlockState> globalPalette;
    private final MappingLoader mappingLoader;

    public StandaloneCisDecoder() {
        this(null);
    }

    public StandaloneCisDecoder(MappingLoader mappingLoader) {
        this.mappingLoader = mappingLoader;
    }

    /**
     * Decodes a CIS file from raw bytes.
     *
     * @param data the raw CIS file bytes
     * @return decoded file data
     * @throws IOException if the file is invalid or corrupted
     */
    public CisFileData decode(byte[] data) throws IOException {
        if (data.length < HEADER_SIZE) {
            throw new IOException("File too short: " + data.length + " bytes");
        }

        int offset = validateHeader(data);
        offset = decodeGlobalPalette(data, offset);
        List<CisFileData.SectionData> sections = new ArrayList<>();
        offset = decodeSections(data, offset, sections);
        int[] entityCounts = decodeEntityCounts(data, offset);

        return new CisFileData(CisConstants.VERSION, globalPalette, sections,
                entityCounts[0], entityCounts[1]);
    }

    private int validateHeader(byte[] data) throws IOException {
        int magic = readIntBE(data, 0);
        if (magic != CisConstants.MAGIC) {
            throw new IOException(String.format("Invalid magic: 0x%08X (expected 0x%08X)",
                    magic, CisConstants.MAGIC));
        }

        int version = readIntBE(data, 4);
        if (version != CisConstants.VERSION) {
            throw new IOException("Unsupported version: " + version + " (expected " + CisConstants.VERSION + ")");
        }

        return HEADER_SIZE;
    }

    private int decodeGlobalPalette(byte[] data, int offset) throws IOException {
        if (offset + 4 > data.length) {
            throw new IOException("Truncated palette size");
        }

        int paletteSize = readIntBE(data, offset);
        offset += 4;

        if (paletteSize < 0 || paletteSize > 10000) {
            throw new IOException("Invalid palette size: " + paletteSize);
        }

        // Read block IDs (2 bytes each)
        int[] blockIds = new int[paletteSize];
        for (int i = 0; i < paletteSize; i++) {
            if (offset + 2 > data.length) {
                throw new IOException("Truncated palette data");
            }
            blockIds[i] = readShortBE(data, offset) & 0xFFFF;
            offset += 2;
        }

        // Read property data length
        if (offset + 4 > data.length) {
            throw new IOException("Truncated property length");
        }
        int propLength = readIntBE(data, offset);
        offset += 4;

        // Skip property data (we can't decode block names without Minecraft)
        offset += propLength;

        // Build palette with IDs and Names
        globalPalette = new ArrayList<>(paletteSize);
        for (int i = 0; i < paletteSize; i++) {
            int blockId = blockIds[i];
            String name = (mappingLoader != null) ? mappingLoader.getName(blockId) : "unknown:" + blockId;
            globalPalette.add(new SimpleBlockState(blockId, name));
        }

        return offset;
    }

    private int decodeSections(byte[] data, int offset, List<CisFileData.SectionData> sections) throws IOException {
        if (offset + 6 > data.length) {
            throw new IOException("Truncated section header");
        }

        int sectionCount = readShortBE(data, offset) & 0xFFFF;
        offset += 2;

        int sectionDataLength = readIntBE(data, offset);
        offset += 4;

        if (offset + sectionDataLength > data.length) {
            throw new IOException("Truncated section data");
        }

        sectionReader.setData(data, offset, sectionDataLength);

        int globalBits = calculateBitsNeeded(globalPalette.size());

        for (int i = 0; i < sectionCount; i++) {
            sections.add(decodeSection(globalBits));
        }

        return offset + sectionDataLength;
    }

    private CisFileData.SectionData decodeSection(int globalBits) {
        int sectionY = sectionReader.readZigZag(CisConstants.SECTION_Y_BITS);
        int mode = (int) sectionReader.read(1);
        boolean sparse = (mode == CisConstants.SECTION_ENCODING_SPARSE);

        List<CisFileData.BlockEntry> blocks = new ArrayList<>();
        int blockCount;

        if (sparse) {
            blockCount = (int) sectionReader.read(CisConstants.BLOCK_COUNT_BITS);
            for (int i = 0; i < blockCount; i++) {
                int packedPos = (int) sectionReader.read(12);
                int globalIdx = (int) sectionReader.read(globalBits);

                int y = (packedPos >> 8) & 0xF;
                int z = (packedPos >> 4) & 0xF;
                int x = packedPos & 0xF;

                blocks.add(new CisFileData.BlockEntry(x, y, z, globalIdx));
            }
        } else {
            // Dense section
            int localSize = (int) sectionReader.read(8);
            for (int i = 0; i < localSize; i++) {
                localPaletteBuffer[i] = (int) sectionReader.read(globalBits);
            }

            int bitsPerBlock = calculateBitsNeeded(localSize);
            blockCount = 0;

            for (int y = 0; y < SECTION_SIZE; y++) {
                for (int z = 0; z < SECTION_SIZE; z++) {
                    for (int x = 0; x < SECTION_SIZE; x++) {
                        int localIndex = bitsPerBlock > 0 ? (int) sectionReader.read(bitsPerBlock) : 0;
                        if (localIndex >= localSize)
                            localIndex = 0;
                        int globalIndex = localPaletteBuffer[localIndex];

                        // Only track non-air blocks (assume index 0 might be air)
                        if (globalIndex > 0) {
                            blocks.add(new CisFileData.BlockEntry(x, y, z, globalIndex));
                            blockCount++;
                        }
                    }
                }
            }
        }

        return new CisFileData.SectionData(sectionY, sparse, blockCount, blocks);
    }

    private int[] decodeEntityCounts(byte[] data, int offset) {
        int beCount = 0;
        int entityCount = 0;

        try {
            if (offset + 4 <= data.length) {
                beCount = readIntBE(data, offset);
            }
            // Skip block entities and try to read entity count
            // This is a simplified approach - just getting counts
        } catch (Exception e) {
            // Ignore - entity data may be missing or truncated
        }

        return new int[] { beCount, entityCount };
    }

    private static int calculateBitsNeeded(int maxValue) {
        return Math.max(1, 32 - Integer.numberOfLeadingZeros(Math.max(1, maxValue - 1)));
    }

    private static int readIntBE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) |
                ((b[off + 1] & 0xFF) << 16) |
                ((b[off + 2] & 0xFF) << 8) |
                (b[off + 3] & 0xFF);
    }

    private static short readShortBE(byte[] b, int off) {
        return (short) (((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF));
    }
}
