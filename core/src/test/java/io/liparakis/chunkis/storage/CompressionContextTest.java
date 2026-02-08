package io.liparakis.chunkis.storage;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class CompressionContextTest {

    @Test
    void compress_decompress_roundTrip() throws Exception {
        CompressionContext ctx = new CompressionContext();
        String original = "Hello Chunkis! This is a test string to verify compression and decompression.";
        byte[] data = original.getBytes(StandardCharsets.UTF_8);

        byte[] compressed = ctx.compress(data);
        assertThat(compressed).isNotEqualTo(data); // Compressed data should differ

        byte[] decompressed = ctx.decompress(compressed);
        assertThat(decompressed).isEqualTo(data);
        assertThat(new String(decompressed, StandardCharsets.UTF_8)).isEqualTo(original);
    }

    @Test
    void compress_decompress_emptyArray() throws Exception {
        CompressionContext ctx = new CompressionContext();
        byte[] data = new byte[0];

        byte[] compressed = ctx.compress(data);
        // Deflater adds headers/trailers even for empty input
        assertThat(compressed).isNotEmpty();

        byte[] decompressed = ctx.decompress(compressed);
        assertThat(decompressed).isEmpty();
    }

    @Test
    void decompress_truncatedData_handlesGracefully() throws Exception {
        CompressionContext ctx = new CompressionContext();
        String original = "Significant amount of data to ensure multiple chunks are processed if needed.";
        byte[] data = original.getBytes(StandardCharsets.UTF_8);
        byte[] compressed = ctx.compress(data);

        // Truncate the compressed data (remove last few bytes)
        // This causes the inflater to need more input while not finished
        byte[] truncated = Arrays.copyOf(compressed, compressed.length - 1);

        // The current implementation breaks the loop when input is needed but missing
        try {
            byte[] result = ctx.decompress(truncated);
            assertThat(result).isNotNull();
        } catch (java.util.zip.DataFormatException e) {
            // acceptable
        }
    }

    @Test
    void decompress_needsDictionary_throwsException() {
        CompressionContext ctx = new CompressionContext();

        byte[] input = "data using dictionary".getBytes(StandardCharsets.UTF_8);
        byte[] dictionary = "dictionary".getBytes(StandardCharsets.UTF_8);

        java.util.zip.Deflater deflater = new java.util.zip.Deflater();
        deflater.setDictionary(dictionary);
        deflater.setInput(input);
        deflater.finish();

        byte[] buffer = new byte[1024];
        int compressedSize = deflater.deflate(buffer);
        byte[] compressed = Arrays.copyOf(buffer, compressedSize);
        deflater.end();

        try {
            ctx.decompress(compressed);
        } catch (Exception e) {
            assertThat(e).isInstanceOf(java.util.zip.ZipException.class)
                    .hasMessage("Decompression requires a dictionary");
            return;
        }
        assertThat(false).withFailMessage("Expected ZipException for missing dictionary").isTrue();
    }

    @Test
    void decompress_stalled_throwsException() {
        // Create an Inflater that simulates a stalled state:
        // finished=false, needsInput=false, needsDictionary=false, inflate returns 0
        java.util.zip.Inflater stalledInflater = new java.util.zip.Inflater() {
            @Override
            public boolean finished() {
                return false;
            }

            @Override
            public boolean needsInput() {
                return false;
            }

            @Override
            public boolean needsDictionary() {
                return false;
            }

            @Override
            public int inflate(byte[] b) throws java.util.zip.DataFormatException {
                return 0;
            }
        };

        CompressionContext ctx = new CompressionContext(new java.util.zip.Deflater(), stalledInflater);
        byte[] dummyData = new byte[10];

        try {
            ctx.decompress(dummyData);
        } catch (Exception e) {
            assertThat(e).isInstanceOf(java.util.zip.ZipException.class)
                    .hasMessage("Decompression stalled");
            return;
        }
        assertThat(false).withFailMessage("Expected ZipException for stalled decompression").isTrue();
    }
}
