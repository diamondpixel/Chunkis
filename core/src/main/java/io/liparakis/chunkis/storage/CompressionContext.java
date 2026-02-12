package io.liparakis.chunkis.storage;

import java.io.ByteArrayOutputStream;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Thread-local compression context to avoid allocations and synchronization.
 */
final class CompressionContext {
    private static final int COMPRESSION_BUFFER_SIZE = 8192;

    private final Deflater deflater;
    private final Inflater inflater;
    private final byte[] buffer = new byte[COMPRESSION_BUFFER_SIZE];
    private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(COMPRESSION_BUFFER_SIZE);

    CompressionContext() {
        this(new Deflater(CisConstants.COMPRESSION_LEVEL), new Inflater());
    }

    // Visible for testing
    CompressionContext(Deflater deflater, Inflater inflater) {
        this.deflater = deflater;
        this.inflater = inflater;
    }

    /**
     * Compresses data using DEFLATE.
     *
     * @param data the raw data
     * @return the compressed data
     */
    byte[] compress(byte[] data) {
        deflater.reset();
        deflater.setInput(data);
        deflater.finish();
        outputStream.reset();

        while (!deflater.finished()) {
            int bytesCompressed = deflater.deflate(buffer);
            outputStream.write(buffer, 0, bytesCompressed);
        }

        return outputStream.toByteArray();
    }

    /**
     * Decompresses data using INFLATE.
     *
     * @param data the compressed data
     * @return the raw data
     * @throws Exception if inflation fails
     */
    byte[] decompress(byte[] data) throws Exception {
        inflater.reset();
        inflater.setInput(data);
        outputStream.reset();

        while (!inflater.finished()) {
            int bytesDecompressed = inflater.inflate(buffer);
            if (bytesDecompressed == 0) {
                if (inflater.needsInput()) {
                    break;
                }
                if (inflater.needsDictionary()) {
                    throw new java.util.zip.ZipException("Decompression requires a dictionary");
                }
                // If we get here: bytesDecompressed == 0, not finished, not needing input, not
                // needing dictionary.
                // This means the inflater is stalled (potentially corrupted data or infinite
                // loop condition).
                throw new java.util.zip.ZipException("Decompression stalled");
            }
            outputStream.write(buffer, 0, bytesDecompressed);
        }

        return outputStream.toByteArray();
    }
}
