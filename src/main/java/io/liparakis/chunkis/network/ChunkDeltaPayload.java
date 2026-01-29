package io.liparakis.chunkis.network;

import io.liparakis.chunkis.ChunkisMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Optimized payload for transmitting chunk delta data over the network.
 * <p>
 * This record encapsulates chunk modification data along with chunk coordinates,
 * designed for efficient serialization and deserialization in Minecraft's networking system.
 * </p>
 *
 * <h2>Performance Optimizations:</h2>
 * <ul>
 *   <li>Reordered field writes to minimize buffer operations (primitives before arrays)</li>
 *   <li>Direct array reference (no defensive copy) - caller responsible for immutability</li>
 *   <li>Static final fields computed once at class load time</li>
 *   <li>Compact record structure minimizes memory footprint</li>
 * </ul>
 *
 * <h2>Memory Characteristics:</h2>
 * <ul>
 *   <li>Base object overhead: ~16 bytes (object header)</li>
 *   <li>Fields: 8 bytes (array reference) + 8 bytes (2 ints)</li>
 *   <li>Total: ~24 bytes + array size</li>
 * </ul>
 *
 * @param data   The serialized chunk delta data. Must not be null.
 *               Caller is responsible for ensuring immutability.
 * @param chunkX The X coordinate of the chunk in chunk-space
 * @param chunkZ The Z coordinate of the chunk in chunk-space
 *
 * @author Performance optimization
 * @version 1.1
 */
public record ChunkDeltaPayload(byte[] data, int chunkX, int chunkZ) implements CustomPayload {

    /**
     * Unique identifier for this payload type.
     * Initialized once at class load time for optimal performance.
     */
    public static final CustomPayload.Id<ChunkDeltaPayload> ID = new CustomPayload.Id<>(
            Identifier.of(ChunkisMod.MOD_ID, "chunk_delta"));

    /**
     * Codec for encoding and decoding this payload.
     * Uses method references for zero-overhead lambda invocation.
     */
    public static final PacketCodec<RegistryByteBuf, ChunkDeltaPayload> CODEC =
            PacketCodec.of(ChunkDeltaPayload::write, ChunkDeltaPayload::read);

    /**
     * Deserializes a ChunkDeltaPayload from a network buffer.
     * <p>
     * <b>Performance Note:</b> Reads primitives before the byte array to optimize
     * buffer operations. Reading small fixed-size values first allows the buffer
     * to batch subsequent array reads more efficiently.
     * </p>
     *
     * @param buf The buffer to read from. Must not be null.
     * @return A new ChunkDeltaPayload instance
     * @throws IndexOutOfBoundsException if buffer doesn't contain enough data
     */
    private static ChunkDeltaPayload read(RegistryByteBuf buf) {
        // Read primitives first (8 bytes total) for better buffer batching
        int chunkX = buf.readInt();
        int chunkZ = buf.readInt();
        // Read variable-length array last
        byte[] data = buf.readByteArray();

        return new ChunkDeltaPayload(data, chunkX, chunkZ);
    }

    /**
     * Serializes this payload to a network buffer.
     * <p>
     * <b>Performance Note:</b> Writes primitives before the byte array to minimize
     * buffer resizing operations. Small fixed-size values are written first to allow
     * the buffer to optimize memory allocation for the subsequent array write.
     * </p>
     * <p>
     * <b>Time Complexity:</b> O(n) where n is the length of the data array<br>
     * <b>Space Complexity:</b> O(1) additional space (writes to existing buffer)
     * </p>
     *
     * @param buf The buffer to write to. Must not be null.
     */
    private void write(RegistryByteBuf buf) {
        // Write primitives first (8 bytes total)
        buf.writeInt(chunkX);
        buf.writeInt(chunkZ);
        // Write variable-length array last to minimize buffer operations
        buf.writeByteArray(data);
    }

    /**
     * Returns the unique identifier for this payload type.
     *
     * @return The payload ID (never null)
     */
    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}