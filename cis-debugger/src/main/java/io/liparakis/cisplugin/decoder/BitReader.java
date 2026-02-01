package io.liparakis.cisplugin.decoder;

/**
 * Bit reader for packed binary data.
 * Ported from core module for standalone use.
 */
public final class BitReader {
    private byte[] data;
    private int byteOffset;
    private int bitOffset;
    private int endByte;

    public BitReader() {
        this.data = new byte[0];
    }

    public void setData(byte[] data, int offset, int length) {
        this.data = data;
        this.byteOffset = offset;
        this.bitOffset = 0;
        this.endByte = offset + length;
    }

    /**
     * Reads the specified number of bits and returns them as a long.
     */
    public long read(int bits) {
        if (bits <= 0 || bits > 64) {
            return 0;
        }

        long result = 0;
        int bitsRemaining = bits;

        while (bitsRemaining > 0) {
            if (byteOffset >= endByte) {
                break;
            }

            int bitsInCurrentByte = 8 - bitOffset;
            int bitsToRead = Math.min(bitsRemaining, bitsInCurrentByte);

            int mask = (1 << bitsToRead) - 1;
            int shift = bitsInCurrentByte - bitsToRead;
            int value = (data[byteOffset] >> shift) & mask;

            result = (result << bitsToRead) | value;
            bitsRemaining -= bitsToRead;
            bitOffset += bitsToRead;

            if (bitOffset >= 8) {
                bitOffset = 0;
                byteOffset++;
            }
        }

        return result;
    }

    /**
     * Reads a ZigZag-encoded signed integer.
     */
    public int readZigZag(int bits) {
        int value = (int) read(bits);
        return (value >>> 1) ^ -(value & 1);
    }
}
