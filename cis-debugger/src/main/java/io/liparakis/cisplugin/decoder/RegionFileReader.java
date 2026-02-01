package io.liparakis.cisplugin.decoder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reader for CIS Region Files.
 * Region files contain 32x32 = 1024 chunks in a single file.
 * Format:
 * - Header: 8192 bytes (1024 entries × 8 bytes each)
 * - Each entry: 4-byte offset + 4-byte length
 * - Data: Deflate-compressed CIS chunk data
 */
public final class RegionFileReader {
    private static final int HEADER_SIZE = 8192;
    private static final int ENTRY_SIZE = 8;
    private static final int CHUNKS_PER_REGION = 1024;
    private static final int REGION_SIZE = 32;

    private final byte[] data;
    private final int[] offsets = new int[CHUNKS_PER_REGION];
    private final int[] lengths = new int[CHUNKS_PER_REGION];

    public RegionFileReader(byte[] data) throws IOException {
        if (data.length < HEADER_SIZE) {
            throw new IOException("File too small for region header: " + data.length + " bytes");
        }
        this.data = data;
        loadHeader();
    }

    private void loadHeader() {
        for (int i = 0; i < CHUNKS_PER_REGION; i++) {
            int pos = i * ENTRY_SIZE;
            offsets[i] = readIntBE(data, pos);
            lengths[i] = readIntBE(data, pos + 4);
        }
    }

    /**
     * Gets a list of all chunks present in this region file.
     */
    public List<ChunkEntry> getChunks() {
        List<ChunkEntry> chunks = new ArrayList<>();
        for (int i = 0; i < CHUNKS_PER_REGION; i++) {
            if (offsets[i] != 0 && lengths[i] > 0) {
                int localX = i % REGION_SIZE;
                int localZ = i / REGION_SIZE;
                chunks.add(new ChunkEntry(i, localX, localZ, offsets[i], lengths[i]));
            }
        }
        return chunks;
    }

    /**
     * Reads and decompresses chunk data at the given index.
     */
    public byte[] readChunk(int index) throws IOException {
        if (index < 0 || index >= CHUNKS_PER_REGION) {
            throw new IOException("Invalid chunk index: " + index);
        }
        if (offsets[index] == 0 || lengths[index] == 0) {
            throw new IOException("Chunk not present at index " + index);
        }

        int offset = offsets[index];
        int length = lengths[index];

        if (offset + length > data.length) {
            throw new IOException("Chunk data extends beyond file: offset=" + offset + ", length=" + length);
        }

        // Extract compressed data
        byte[] compressed = new byte[length];
        System.arraycopy(data, offset, compressed, 0, length);

        // Decompress
        return decompress(compressed);
    }

    private static byte[] decompress(byte[] compressed) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length * 4);
            byte[] buffer = new byte[4096];

            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0 && inflater.needsInput()) {
                    break;
                }
                out.write(buffer, 0, count);
            }

            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new IOException("Failed to decompress chunk data: " + e.getMessage(), e);
        } finally {
            inflater.end();
        }
    }

    private static int readIntBE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) |
                ((b[off + 1] & 0xFF) << 16) |
                ((b[off + 2] & 0xFF) << 8) |
                (b[off + 3] & 0xFF);
    }

    /**
     * Represents a chunk entry in the region file.
     */
    public record ChunkEntry(int index, int localX, int localZ, int offset, int compressedSize) {
        @Override
        public String toString() {
            return String.format("Chunk [%d, %d] (%d bytes)", localX, localZ, compressedSize);
        }
    }
}
