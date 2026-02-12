package io.liparakis.chunkis.storage.codec;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.spi.NbtAdapter;
import io.liparakis.chunkis.storage.BitUtils;
import io.liparakis.chunkis.storage.CisAdapter;
import io.liparakis.chunkis.storage.CisConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link CisDecoder} focusing on error handling branches.
 */
class CisDecoderTest {

    private CisDecoder<String, String> decoder;
    private TestCisAdapter cisAdapter;
    private TestBlockStateAdapter stateAdapter;
    private TestNbtAdapter nbtAdapter;

    @BeforeEach
    void setUp() {
        cisAdapter = new TestCisAdapter();
        stateAdapter = new TestBlockStateAdapter();
        nbtAdapter = new TestNbtAdapter();
        decoder = new CisDecoder<>(cisAdapter, stateAdapter, nbtAdapter, "air");
    }

    // ========== decodeGlobalPalette Error Handling Tests ==========

    @Test
    void decodeGlobalPalette_truncatedPaletteSize_throwsIOException() {
        // Create data with valid header but truncated palette size (less than 4 bytes)
        byte[] data = createHeaderOnly();

        assertThatThrownBy(() -> decoder.decode(data))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Truncated data: cannot read global palette size");
    }

    @Test
    void decodeGlobalPalette_negativePaletteSize_throwsIOException() {
        // Create data with negative palette size
        ByteBuffer buffer = ByteBuffer.allocate(100);
        writeHeader(buffer);
        buffer.putInt(-1); // negative palette size

        byte[] data = getBytes(buffer);

        assertThatThrownBy(() -> decoder.decode(data))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Invalid palette size: -1");
    }

    @Test
    void decodeGlobalPalette_excessivePaletteSize_throwsIOException() {
        // Create data with palette size exceeding MAX_REASONABLE_PALETTE_SIZE
        ByteBuffer buffer = ByteBuffer.allocate(100);
        writeHeader(buffer);
        buffer.putInt(20000); // exceeds MAX_REASONABLE_PALETTE_SIZE (10000)

        byte[] data = getBytes(buffer);

        assertThatThrownBy(() -> decoder.decode(data))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Invalid palette size: 20000");
    }

    @Test
    void decodeGlobalPalette_truncatedPaletteData_throwsIOException() {
        // Create data with valid palette size but insufficient data for block IDs
        ByteBuffer buffer = ByteBuffer.allocate(100);
        writeHeader(buffer);
        buffer.putInt(10); // palette size = 10, requires 10*2 + 4 = 24 bytes
        // Only provide 2 bytes instead of 24
        buffer.putShort((short) 1);

        byte[] data = getBytes(buffer);

        assertThatThrownBy(() -> decoder.decode(data))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Truncated data in palette");
    }

    @Test
    void decodeGlobalPalette_truncatedProperties_throwsIOException() {
        // Create data with valid palette and block IDs but truncated properties
        ByteBuffer buffer = ByteBuffer.allocate(100);
        writeHeader(buffer);
        buffer.putInt(2); // palette size = 2

        // Write 2 block IDs
        buffer.putShort((short) 1);
        buffer.putShort((short) 2);

        // Write property length that exceeds remaining data
        buffer.putInt(1000); // claims 1000 bytes of properties

        byte[] data = getBytes(buffer);

        assertThatThrownBy(() -> decoder.decode(data))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Truncated data in properties");
    }

    @Test
    void decodeGlobalPalette_validMinimalData_succeeds() throws IOException {
        // Create minimal valid CIS data
        ByteBuffer buffer = ByteBuffer.allocate(200);
        writeHeader(buffer);

        // Global palette: size 1
        buffer.putInt(1);
        buffer.putShort((short) 1); // block ID
        buffer.putInt(0); // properties length = 0

        // Sections: count 0
        buffer.putShort((short) 0);
        buffer.putInt(0); // section data length

        // Block entities: count 0
        buffer.putInt(0);

        byte[] data = getBytes(buffer);

        ChunkDelta<String, String> result = decoder.decode(data);

        assertThat(result).isNotNull();
        assertThat(result.getBlockPalette().getAll()).hasSize(1);
    }

    // ========== Helper Methods ==========

    private byte[] createHeaderOnly() {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        writeHeader(buffer);
        return getBytes(buffer);
    }

    private void writeHeader(ByteBuffer buffer) {
        buffer.putInt(CisConstants.MAGIC);
        buffer.putInt(CisConstants.VERSION);
    }

    private byte[] getBytes(ByteBuffer buffer) {
        byte[] data = new byte[buffer.position()];
        buffer.flip();
        buffer.get(data);
        return data;
    }

    // ========== Test Adapter Implementations ==========

    private static class TestCisAdapter implements CisAdapter<String> {
        @Override
        public int getBlockId(String state) {
            return state.hashCode();
        }

        @Override
        public void writeStateProperties(BitUtils.BitWriter writer, String state) {
            // No properties
        }

        @Override
        public String readStateProperties(BitUtils.BitReader reader, int blockId) {
            return "block_" + blockId;
        }
    }

    private static class TestBlockStateAdapter implements BlockStateAdapter<String, String, String> {
        @Override
        public String getDefaultState(String block) {
            return block;
        }

        @Override
        public String getBlock(String state) {
            return state;
        }

        @Override
        public List<String> getProperties(String block) {
            return Collections.emptyList();
        }

        @Override
        public String getPropertyName(String property) {
            return property;
        }

        @Override
        public List<Object> getPropertyValues(String property) {
            return Collections.emptyList();
        }

        @Override
        public int getValueIndex(String state, String property) {
            return 0;
        }

        @Override
        public String withProperty(String state, String property, int valueIndex) {
            return state;
        }

        @Override
        public Comparator<Object> getValueComparator() {
            return Comparator.comparing(Object::toString);
        }

        @Override
        public boolean isAir(String state) {
            return "air".equals(state);
        }
    }

    private static class TestNbtAdapter implements NbtAdapter<String> {
        @Override
        public void write(String tag, DataOutput output) throws IOException {
            output.writeUTF(tag);
        }

        @Override
        public String read(DataInput input) throws IOException {
            return input.readUTF();
        }
    }
}
