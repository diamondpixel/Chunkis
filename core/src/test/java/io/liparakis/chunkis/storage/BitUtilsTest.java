package io.liparakis.chunkis.storage;

import io.liparakis.chunkis.storage.BitUtils.BitReader;
import io.liparakis.chunkis.storage.BitUtils.BitWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link BitUtils} including ZigZag encoding and bit-level I/O.
 */
class BitUtilsTest {

    // ===== ZigZag Encoding Tests =====

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, -1, 127, -128, Integer.MAX_VALUE, Integer.MIN_VALUE })
    void zigZagRoundTrip(int value) {
        int encoded = BitUtils.encodeZigZag(value);
        int decoded = BitUtils.decodeZigZag(encoded);
        assertThat(decoded).isEqualTo(value);
    }

    @Test
    void zigZagEncodesSmallValuesEfficiently() {
        // ZigZag should map small absolute values to small unsigned values
        assertThat(BitUtils.encodeZigZag(0)).isEqualTo(0);
        assertThat(BitUtils.encodeZigZag(-1)).isEqualTo(1);
        assertThat(BitUtils.encodeZigZag(1)).isEqualTo(2);
        assertThat(BitUtils.encodeZigZag(-2)).isEqualTo(3);
        assertThat(BitUtils.encodeZigZag(2)).isEqualTo(4);
    }

    // ===== BitWriter/BitReader Tests =====

    @Test
    void writeSingleByte() {
        BitWriter writer = new BitWriter(16);
        writer.write(0xAB, 8);
        byte[] data = writer.toByteArray();

        assertThat(data).hasSize(1);
        assertThat(data[0] & 0xFF).isEqualTo(0xAB);
    }

    @Test
    void writeMultipleBytes() {
        BitWriter writer = new BitWriter(16);
        writer.write(0xDEAD, 16);
        byte[] data = writer.toByteArray();

        assertThat(data).hasSize(2);
        assertThat(data[0] & 0xFF).isEqualTo(0xDE);
        assertThat(data[1] & 0xFF).isEqualTo(0xAD);
    }

    @Test
    void writeAcrossByteBoundary() {
        BitWriter writer = new BitWriter(16);
        writer.write(0b111, 3); // 3 bits: 111
        writer.write(0b10101, 5); // 5 bits: 10101
        byte[] data = writer.toByteArray();

        // Expected: 111_10101 = 0b11110101 = 0xF5
        assertThat(data).hasSize(1);
        assertThat(data[0] & 0xFF).isEqualTo(0xF5);
    }

    @Test
    void writeAndReadRoundTrip() {
        BitWriter writer = new BitWriter(64);
        writer.write(5, 4); // 4 bits
        writer.write(123, 8); // 8 bits
        writer.write(7, 3); // 3 bits
        writer.write(0xCAFE, 16); // 16 bits
        byte[] data = writer.toByteArray();

        BitReader reader = new BitReader(data);
        assertThat(reader.read(4)).isEqualTo(5);
        assertThat(reader.read(8)).isEqualTo(123);
        assertThat(reader.read(3)).isEqualTo(7);
        assertThat(reader.read(16)).isEqualTo(0xCAFE);
    }

    @Test
    void zigZagWriteReadRoundTrip() {
        BitWriter writer = new BitWriter(32);
        writer.writeZigZag(-50, 8);
        writer.writeZigZag(100, 8);
        writer.writeZigZag(-1, 8);
        byte[] data = writer.toByteArray();

        BitReader reader = new BitReader(data);
        assertThat(reader.readZigZag(8)).isEqualTo(-50);
        assertThat(reader.readZigZag(8)).isEqualTo(100);
        assertThat(reader.readZigZag(8)).isEqualTo(-1);
    }

    @Test
    void writerResetClearsState() {
        BitWriter writer = new BitWriter(16);
        writer.write(0xFF, 8);
        writer.reset();
        writer.write(0x00, 8);
        byte[] data = writer.toByteArray();

        assertThat(data).hasSize(1);
        assertThat(data[0]).isEqualTo((byte) 0x00);
    }

    @Test
    void readerSetDataResetsPosition() {
        byte[] data1 = { (byte) 0xAA };
        byte[] data2 = { (byte) 0x55 };

        BitReader reader = new BitReader(data1);
        assertThat(reader.read(8)).isEqualTo(0xAA);

        reader.setData(data2, 0, data2.length);
        assertThat(reader.read(8)).isEqualTo(0x55);
    }

    @Test
    void largeBitWrite() {
        BitWriter writer = new BitWriter(16);
        long largeValue = 0x123456789ABCL; // 48 bits
        writer.write(largeValue, 48);
        byte[] data = writer.toByteArray();

        assertThat(data).hasSize(6);

        BitReader reader = new BitReader(data);
        assertThat(reader.read(48)).isEqualTo(largeValue);
    }

    @Test
    void readZeroBits() {
        BitReader reader = new BitReader(new byte[] { (byte) 0xFF });
        assertThat(reader.read(0)).isEqualTo(0);
    }

    @Test
    void readPastEndOfData() {
        // Data has 8 bits
        BitReader reader = new BitReader(new byte[] { (byte) 0xAA });

        // Read 4 bits -> 1010
        assertThat(reader.read(4)).isEqualTo(0xA);

        // Read 4 bits -> 1010
        assertThat(reader.read(4)).isEqualTo(0xA);

        // Read 4 more bits -> Should return 0 (shifted if bits > 0, but no data)
        assertThat(reader.read(4)).isEqualTo(0);

        // Ensure proper behavior when asking for more than available from start
        BitReader reader2 = new BitReader(new byte[] { (byte) 0xFF });
        // Request 16 bits, only 8 available.
        // Logic: reads 8, then byteIndex >= endIndex loop will return result <<
        // remaining
        // 0xFF (8 bits) << 8 (remaining) = 0xFF00? No, let's trace:
        // read(16):
        // 1. read 8 bits -> result = 0xFF. bits = 8.
        // 2. loop: byteIndex (1) >= endIndex (1). return result (0xFF) << 8 = 0xFF00.
        assertThat(reader2.read(16)).isEqualTo(0xFF00);
    }

    @Test
    void writerExpandsBufferCorrectly() {
        // Start with small capacity (1 byte)
        BitWriter writer = new BitWriter(1);

        // Write more than 1 byte to force expansion
        // Write 0x1234 (16 bits) -> requires 2 bytes + safety margin
        writer.write(0x1234, 16);

        byte[] data = writer.toByteArray();
        assertThat(data).hasSize(2);
        assertThat(data[0] & 0xFF).isEqualTo(0x12);
        assertThat(data[1] & 0xFF).isEqualTo(0x34);

        // Write even more to force another expansion
        long largeVal = 0xAABBCCDDEEFFL; // 48 bits = 6 bytes
        writer.write(largeVal, 48);

        byte[] data2 = writer.toByteArray();
        assertThat(data2).hasSize(8); // 2 previous + 6 new
    }

    @Test
    void writeZeroBitsDoesNothing() {
        BitWriter writer = new BitWriter(16);
        writer.write(0xFFFF, 0); // Should do nothing
        byte[] data = writer.toByteArray();
        assertThat(data).isEmpty();
    }

    @Test
    void write64Bits() {
        BitWriter writer = new BitWriter(16);
        long val = 0x1234567890ABCDEFL;
        writer.write(val, 64);
        byte[] data = writer.toByteArray();

        assertThat(data).hasSize(8);

        BitReader reader = new BitReader(data);
        assertThat(reader.read(64)).isEqualTo(val);
    }
}
