package io.liparakis.chunkis.network;

import io.liparakis.chunkis.Chunkis;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * High-performance payload with optional compression for large chunk deltas.
 * 
 * @author Liparakis
 * @version 1.0
 */
public record ChunkDeltaPayload(byte[] data, int chunkX, int chunkZ, boolean compressed)
        implements CustomPayload {

    private static final int MAX_PAYLOAD_SIZE = 1024 * 1024;
    private static final int COMPRESSION_THRESHOLD = 4096; // 4 KB
    private static final byte FLAG_COMPRESSED = (byte) 0x01;
    private static final byte FLAG_UNCOMPRESSED = (byte) 0x00;

    // Thread-local compressor/decompressor pool
    private static final ThreadLocal<Deflater> DEFLATER_POOL = ThreadLocal
            .withInitial(() -> new Deflater(Deflater.BEST_SPEED));
    private static final ThreadLocal<Inflater> INFLATER_POOL = ThreadLocal.withInitial(Inflater::new);

    public static final CustomPayload.Id<ChunkDeltaPayload> ID = new CustomPayload.Id<>(
            Identifier.of(Chunkis.MOD_ID, "chunk_delta"));
    public static final PacketCodec<RegistryByteBuf, ChunkDeltaPayload> CODEC = PacketCodec.of(ChunkDeltaPayload::write,
            ChunkDeltaPayload::read);

    /**
     * Creates a payload, automatically compressing if beneficial.
     */
    public static ChunkDeltaPayload create(byte[] data, int chunkX, int chunkZ) {
        Objects.requireNonNull(data);

        if (data.length >= COMPRESSION_THRESHOLD) {
            byte[] compressed = compress(data);
            // Only use compression if it actually reduces size
            if (compressed.length < data.length * 0.9) { // 10% threshold
                return new ChunkDeltaPayload(compressed, chunkX, chunkZ, true);
            }
        }

        return new ChunkDeltaPayload(Arrays.copyOf(data, data.length), chunkX, chunkZ, false);
    }

    /**
     * Compresses the given data using Deflater.
     *
     * @param data The raw data to compress.
     * @return The compressed data.
     */
    private static byte[] compress(byte[] data) {
        Deflater deflater = DEFLATER_POOL.get();
        deflater.reset();
        deflater.setInput(data);
        deflater.finish();

        // Start with half the size as a heuristic estimate
        ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length / 2);
        byte[] buffer = new byte[1024];

        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            baos.write(buffer, 0, count);
        }

        return baos.toByteArray();
    }

    /**
     * Decompresses the given data using Inflater.
     *
     * @param compressed   The compressed data.
     * @param originalSize The expected size of the uncompressed data.
     * @return The uncompressed data.
     * @throws IOException         If the result length does not match expected
     *                             size.
     * @throws DataFormatException If the data format is invalid.
     */
    private static byte[] decompress(byte[] compressed, int originalSize) throws IOException, DataFormatException {
        Inflater inflater = INFLATER_POOL.get();
        inflater.reset();
        inflater.setInput(compressed);

        byte[] result = new byte[originalSize];
        int resultLength = inflater.inflate(result);

        if (resultLength != originalSize) {
            throw new IOException("Decompression size mismatch");
        }

        return result;
    }

    /**
     * Reads a ChunkDeltaPayload from the packet buffer.
     *
     * @param buf The buffer to read from.
     * @return The decoded payload.
     */
    private static ChunkDeltaPayload read(RegistryByteBuf buf) {
        try {
            int chunkX = buf.readInt();
            int chunkZ = buf.readInt();
            byte flags = buf.readByte();
            boolean isCompressed = (flags & FLAG_COMPRESSED) != 0;

            int dataLength = buf.readInt(); // Read length explicitly
            if (dataLength > MAX_PAYLOAD_SIZE) {
                throw new IllegalArgumentException("Payload too large: " + dataLength);
            }

            byte[] data = new byte[dataLength];
            buf.readBytes(data);

            if (isCompressed) {
                int originalSize = buf.readInt();
                data = decompress(data, originalSize);
            }

            return new ChunkDeltaPayload(data, chunkX, chunkZ, isCompressed);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to read payload", e);
        }
    }

    /**
     * Writes this payload to the packet buffer.
     *
     * @param buf The buffer to write to.
     */
    private void write(RegistryByteBuf buf) {
        buf.writeInt(chunkX);
        buf.writeInt(chunkZ);
        buf.writeByte(compressed ? FLAG_COMPRESSED : FLAG_UNCOMPRESSED);
        buf.writeInt(data.length);
        buf.writeBytes(data);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}