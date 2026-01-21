package io.liparakis.chunkis.storage;

import java.util.Arrays;

/**
 * High-performance bit manipulation utilities for storage and networking.
 * Refactored for memory efficiency, object reuse, and tick safety.
 */
public final class BitUtils {

    private BitUtils() {
    } // Prevent instantiation

    /**
     * Maps signed integers to unsigned: 0,-1,1,-2,2 -> 0,1,2,3,4
     * Branchless implementation.
     */
    public static int encodeZigZag(int n) {
        return (n << 1) ^ (n >> 31);
    }

    public static int decodeZigZag(int n) {
        return (n >>> 1) ^ -(n & 1);
    }

    // --- Bit Writer ---

    /**
     * A non-thread-safe, high-performance bit writer backed by a raw byte array.
     * Removes the overhead of ByteArrayOutputStream (synchronization/allocation).
     * Designed to be instantiated once and reused via reset().
     */
    public static class BitWriter {
        private byte[] buffer;
        private int index = 0;
        private int bitIndex = 0; // 0..7 (bits consumed in current byte)

        // default initial capacity suitable for typical chunk sections or small packets
        private static final int DEFAULT_CAPACITY = 1024;

        public BitWriter() {
            this(DEFAULT_CAPACITY);
        }

        public BitWriter(int initialCapacity) {
            this.buffer = new byte[initialCapacity];
        }

        /**
         * Resets the writer for reuse without re-allocating the underlying buffer.
         * Critical for minimizing GC pressure in tick loops.
         */
        public void reset() {
            this.index = 0;
            this.bitIndex = 0;
            // Note: We do not zero out the array for performance;
            // subsequent writes will overwrite relevant bits.
            // We only zero the first byte if we want to be pedantic,
            // but the write logic handles initialization of new bytes.
            if (buffer.length > 0) buffer[0] = 0;
        }

        private void ensureCapacity(int bytesNeeded) {
            if (index + bytesNeeded >= buffer.length) {
                // Growth factor of 2x to amortize resizing costs
                int newSize = Math.max(buffer.length * 2, index + bytesNeeded + 128);
                buffer = Arrays.copyOf(buffer, newSize);
            }
        }

        /**
         * Write up to 64 bits to the stream.
         *
         * @param value The value to write.
         * @param bits  The number of bits to write (0-64).
         */
        public void write(long value, int bits) {
            if (bits == 0) return;

            // Ensure we have enough buffer space to handle the worst-case spill
            // (bits / 8) + 2 bytes roughly covers it.
            ensureCapacity((bits >> 3) + 2);

            // Mask value to ensure clean upper bits
            value &= (bits == 64) ? -1L : (1L << bits) - 1;

            while (bits > 0) {
                int space = 8 - bitIndex;
                int take = Math.min(bits, space); // Math.min optimized

                // Extract 'take' bits from the Top (MSB) of the value
                // Logic: (value >> (bits - take)) & mask
                long chunk = (value >>> (bits - take)) & ((1L << take) - 1);

                // If starting a new byte, ensure it is zeroed out first
                if (bitIndex == 0) {
                    buffer[index] = 0;
                }

                // Place chunk into the current byte
                // Shift: (8 - bitIndex - take)
                buffer[index] |= (byte) (chunk << (8 - bitIndex - take));

                bitIndex += take;
                bits -= take;

                if (bitIndex == 8) {
                    index++;
                    bitIndex = 0;
                }
            }
        }

        public void writeBool(boolean b) {
            // Optimized fast path for single bit
            if (index >= buffer.length) ensureCapacity(1);

            if (bitIndex == 0) buffer[index] = 0;
            if (b) {
                buffer[index] |= (byte) (1 << (7 - bitIndex));
            }

            bitIndex++;
            if (bitIndex == 8) {
                index++;
                bitIndex = 0;
            }
        }

        public void writeZigZag(int value, int bits) {
            write(encodeZigZag(value), bits);
        }

        /**
         * Forces the current partial byte to be flushed to the index count.
         * (Technically just increments index if we are mid-byte).
         */
        public void flush() {
            if (bitIndex > 0) {
                index++;
                bitIndex = 0;
            }
        }

        /**
         * Returns a copy of the written bytes.
         * For zero-copy access, access the buffer directly via a getter if strict optimization is needed,
         * but for safety, we return a copy sized exactly to the data written.
         */
        public byte[] toByteArray() {
            flush();
            return Arrays.copyOf(buffer, index);
        }
    }

    // --- Bit Reader ---

    /**
     * Optimized Bit Reader working directly on a byte array.
     */
    public static class BitReader {
        private byte[] data;
        private int byteIndex = 0;
        private int bitIndex = 0;
        private int length; // Cached length

        public BitReader(byte[] data) {
            setData(data);
        }

        /**
         * Allows reusing the Reader instance for different data arrays.
         */
        public void setData(byte[] data) {
            this.data = data;
            this.length = data.length;
            this.byteIndex = 0;
            this.bitIndex = 0;
        }

        public long read(int bits) {
            if (bits == 0) return 0;
            long result = 0;

            while (bits > 0) {
                if (byteIndex >= length) {
                    // unexpected EOF, return result shifted by remaining bits to maintain magnitude
                    return result << bits;
                }

                int b = data[byteIndex] & 0xFF;
                int remaining = 8 - bitIndex;
                int take = Math.min(bits, remaining);

                // Extract 'take' bits starting at 'bitIndex'
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

        public boolean readBool() {
            if (byteIndex >= length) return false;

            int b = data[byteIndex] & 0xFF;
            // Read 1 bit at bitIndex
            boolean val = ((b >>> (7 - bitIndex)) & 1) == 1;

            bitIndex++;
            if (bitIndex == 8) {
                byteIndex++;
                bitIndex = 0;
            }
            return val;
        }

        public int readZigZag(int bits) {
            return decodeZigZag((int) read(bits));
        }
    }
}