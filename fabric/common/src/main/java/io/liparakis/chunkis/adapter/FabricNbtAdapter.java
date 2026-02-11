package io.liparakis.chunkis.adapter;

import io.liparakis.chunkis.spi.NbtAdapter;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import org.jetbrains.annotations.NotNull;

import java.io.*;

/**
 * Streaming NBT adapter that avoids intermediate buffering when possible.
 *
 * <p>
 * This version uses direct streaming for InputStream/OutputStream,
 * falling back to buffering only when necessary for DataInput/DataOutput.
 *
 * @author Liparakis
 * @version 1.0
 */
public final class FabricNbtAdapter implements NbtAdapter<NbtCompound> {

    private static final int MAX_NBT_SIZE = 2 * 1024 * 1024;
    private static final int BUFFER_SIZE = 8192;

    // Reusable buffer pool
    private static final ThreadLocal<BufferHolder> BUFFER_POOL = ThreadLocal.withInitial(BufferHolder::new);

    @Override
    public void write(NbtCompound nbt, DataOutput output) throws IOException {
        if (output instanceof DataOutputStream dos) {
            // Fast path: direct streaming
            writeStreaming(nbt, dos);
        } else {
            // Slow path: buffered
            writeBuffered(nbt, output);
        }
    }

    /**
     * Writes NBT using direct streaming to avoid buffering whole output.
     *
     * @param nbt    The NBT compound to write.
     * @param output The output stream.
     * @throws IOException If write fails or data exceeds limit.
     */
    private void writeStreaming(NbtCompound nbt, DataOutputStream output) throws IOException {
        // Use temporary buffer to measure size
        BufferHolder holder = BUFFER_POOL.get();
        holder.reset();

        // Write to thread-local buffer first to calculate size
        NbtIo.writeCompressed(nbt, holder.buffer);

        int size = holder.buffer.size();
        if (size > MAX_NBT_SIZE) {
            throw new IOException("NBT too large: " + size);
        }

        // Write length prefix followed by data
        output.writeInt(size);
        holder.buffer.writeTo(output);
    }

    /**
     * Writes NBT to a generic DataOutput by buffering the result.
     * <p>
     * Used when the output is not a DataOutputStream (e.g. RandomAccessFile).
     *
     * @param nbt    The NBT compound to write.
     * @param output The data output.
     * @throws IOException If write fails.
     */
    private void writeBuffered(NbtCompound nbt, DataOutput output) throws IOException {
        BufferHolder holder = BUFFER_POOL.get();
        holder.reset();

        // Compress into buffer
        NbtIo.writeCompressed(nbt, holder.buffer);
        byte[] data = holder.buffer.toByteArray();

        if (data.length > MAX_NBT_SIZE) {
            throw new IOException("NBT too large: " + data.length);
        }

        // Write length and bytes
        output.writeInt(data.length);
        output.write(data);
    }

    @Override
    public NbtCompound read(DataInput input) throws IOException {
        int length = input.readInt();

        if (length < 0 || length > MAX_NBT_SIZE) {
            throw new IOException("Invalid NBT size: " + length);
        }

        if (input instanceof DataInputStream dis) {
            // Fast path: wrap stream directly
            return readStreaming(dis, length);
        } else {
            // Slow path: buffer data
            return readBuffered(input, length);
        }
    }

    /**
     * Reads NBT directly from a DataInputStream.
     *
     * @param input  The input stream.
     * @param length The expected length of the NBT data.
     * @return The parsed NBT compound.
     * @throws IOException If read fails.
     */
    private NbtCompound readStreaming(DataInputStream input, int length) throws IOException {
        // Use bounded input stream to prevent over-reading
        try (InputStream bounded = new BoundedInputStream(input, length)) {
            return NbtIo.readCompressed(bounded, NbtSizeTracker.of(MAX_NBT_SIZE));
        }
    }

    /**
     * Reads NBT from a generic DataInput by buffering the data first.
     *
     * @param input  The input source.
     * @param length The length of data to read.
     * @return The parsed NBT compound.
     * @throws IOException If read fails.
     */
    private NbtCompound readBuffered(DataInput input, int length) throws IOException {
        BufferHolder holder = BUFFER_POOL.get();
        // Ensure read buffer is large enough
        byte[] buffer = holder.getReadBuffer(length);

        input.readFully(buffer, 0, length);

        // Parse from in-memory byte array
        try (ByteArrayInputStream bais = new ByteArrayInputStream(buffer, 0, length)) {
            return NbtIo.readCompressed(bais, NbtSizeTracker.of(MAX_NBT_SIZE));
        }
    }

    /**
     * Holds reusable buffers for a thread.
     */
    private static final class BufferHolder {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(BUFFER_SIZE);
        byte[] readBuffer = new byte[BUFFER_SIZE];

        void reset() {
            buffer.reset();
        }

        byte[] getReadBuffer(int size) {
            if (size > readBuffer.length) {
                readBuffer = new byte[size];
            }
            return readBuffer;
        }
    }

    /**
     * Input stream that reads at most N bytes.
     */
    private static final class BoundedInputStream extends FilterInputStream {
        private long remaining;

        BoundedInputStream(InputStream in, long maxBytes) {
            super(in);
            this.remaining = maxBytes;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0)
                return -1;
            int result = super.read();
            if (result != -1)
                remaining--;
            return result;
        }

        @Override
        public int read(byte @NotNull [] b, int off, int len) throws IOException {
            if (remaining <= 0)
                return -1;
            int toRead = (int) Math.min(len, remaining);
            int result = super.read(b, off, toRead);
            if (result > 0)
                remaining -= result;
            return result;
        }
    }
}