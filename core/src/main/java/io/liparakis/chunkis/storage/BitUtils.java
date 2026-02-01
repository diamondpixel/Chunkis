package io.liparakis.chunkis.storage;

import java.util.Arrays;

/**
 * High-performance bit manipulation utilities for storage and networking.
 * Provides optimized bit-level reading and writing operations with minimal
 * memory overhead.
 */
public final class BitUtils {

    private BitUtils() {
    }

    /**
     * Encodes a signed integer using ZigZag encoding for efficient storage of small
     * values.
     * Maps signed integers to unsigned: 0,-1,1,-2,2 -> 0,1,2,3,4
     *
     * @param n the signed integer to encode
     * @return the ZigZag encoded value
     */
    public static int encodeZigZag(int n) {
        return (n << 1) ^ (n >> 31);
    }

    /**
     * Decodes a ZigZag encoded integer back to its original signed value.
     *
     * @param n the ZigZag encoded value
     * @return the decoded signed integer
     */
    public static int decodeZigZag(int n) {
        return (n >>> 1) ^ -(n & 1);
    }

    /**
     * A non-thread-safe, high-performance bit writer backed by a raw byte array.
     * Designed for reuse via {@link #reset()} to minimize garbage collection
     * pressure.
     * Writes bits from most significant to least significant.
     */
    public static final class BitWriter {
        /**
         * The raw buffer storing written data.
         */
        private byte[] buffer;

        /**
         * The current byte index in the buffer.
         */
        private int index;

        /**
         * The current bit position within the current byte (0-7).
         */
        private int bitIndex;

        /**
         * Creates a new BitWriter with the specified initial capacity.
         *
         * @param initialCapacity the initial buffer size in bytes
         */
        public BitWriter(int initialCapacity) {
            this.buffer = new byte[initialCapacity];
        }

        /**
         * Resets the writer for reuse without reallocating the underlying buffer.
         * Critical for minimizing GC pressure in tight loops.
         */
        public void reset() {
            this.index = 0;
            this.bitIndex = 0;
        }

        /**
         * Ensures the buffer has capacity for the specified number of additional bytes.
         * Grows the buffer by 2x when needed to amortize resizing costs.
         *
         * @param bytesNeeded the number of bytes needed
         */
        private void ensureCapacity(int bytesNeeded) {
            int required = index + bytesNeeded;
            if (required >= buffer.length) {
                buffer = Arrays.copyOf(buffer, Math.max(buffer.length << 1, required + 128));
            }
        }

        /**
         * Writes up to 64 bits to the stream.
         *
         * @param value the value to write
         * @param bits  the number of bits to write (0-64)
         */
        public void write(long value, int bits) {
            if (bits == 0)
                return;

            ensureCapacity((bits >>> 3) + 2);

            if (bits != 64) {
                value &= (1L << bits) - 1;
            }

            while (bits > 0) {
                int space = 8 - bitIndex;
                int take = Math.min(bits, space);
                long chunk = (value >>> (bits - take)) & ((1L << take) - 1);

                if (bitIndex == 0) {
                    buffer[index] = (byte) (chunk << (space - take));
                } else {
                    buffer[index] |= (byte) (chunk << (space - take));
                }

                bitIndex += take;
                bits -= take;

                if (bitIndex == 8) {
                    index++;
                    bitIndex = 0;
                }
            }
        }

        /**
         * Writes a ZigZag encoded signed integer.
         *
         * @param value the signed integer to encode and write
         * @param bits  the number of bits to write
         */
        public void writeZigZag(int value, int bits) {
            write(encodeZigZag(value), bits);
        }

        /**
         * Flushes any partial byte to the buffer, advancing to the next byte boundary.
         */
        public void flush() {
            if (bitIndex > 0) {
                index++;
                bitIndex = 0;
            }
        }

        /**
         * Returns a copy of the written bytes, sized exactly to the data written.
         *
         * @return a byte array containing all written data
         */
        public byte[] toByteArray() {
            int length = bitIndex > 0 ? index + 1 : index;
            return Arrays.copyOf(buffer, length);
        }
    }

    /**
     * Optimized bit reader working directly on a byte array with zero-copy support.
     * Non-thread-safe. Reads bits from most significant to least significant.
     */
    public static final class BitReader {
        /**
         * The raw data buffer being read from.
         */
        private byte[] data;

        /**
         * The current byte index in the buffer.
         */
        private int byteIndex;

        /**
         * The current bit position within the current byte (0-7).
         */
        private int bitIndex;

        /**
         * The exclusive end index of the data slice.
         */
        private int endIndex;

        /**
         * Creates a new BitReader for the given byte array.
         *
         * @param data the byte array to read from
         */
        public BitReader(byte[] data) {
            setData(data, 0, data.length);
        }

        /**
         * Configures the reader to read from a slice of a byte array.
         * Zero-copy operation - does not copy the array.
         *
         * @param data   the byte array to read from
         * @param offset the starting offset in the array
         * @param length the number of bytes to read
         */
        public void setData(byte[] data, int offset, int length) {
            this.data = data;
            this.byteIndex = offset;
            this.bitIndex = 0;
            this.endIndex = offset + length;
        }

        /**
         * Reads the specified number of bits and returns them as a long value.
         * Returns partial results if end of data is reached.
         *
         * @param bits the number of bits to read (0-64)
         * @return the read value as a long
         */
        public long read(int bits) {
            if (bits == 0)
                return 0;

            long result = 0;

            while (bits > 0) {
                if (byteIndex >= endIndex) {
                    return result << bits;
                }

                int b = data[byteIndex] & 0xFF;
                int remaining = 8 - bitIndex;
                int take = Math.min(bits, remaining);
                int chunk = (b >>> (remaining - take)) & ((1 << take) - 1);

                result = (result << take) | chunk;

                bitIndex += take;
                bits -= take;

                if (bitIndex == 8) {
                    byteIndex++;
                    bitIndex = 0;
                }
            }

            return result;
        }

        /**
         * Reads and decodes a ZigZag encoded signed integer.
         *
         * @param bits the number of bits to read
         * @return the decoded signed integer
         */
        public int readZigZag(int bits) {
            return decodeZigZag((int) read(bits));
        }
    }
}