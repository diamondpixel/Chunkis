package io.liparakis.chunkis.network;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.storage.codec.CisNetworkEncoder;
import io.liparakis.chunkis.util.FabricNetworkCodecFactory;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Objects;

/**
 * Lightweight networking handler for Chunkis with essential optimizations.
 * 
 * @author Liparakis
 * @version 1.0
 */
public final class ChunkisNetworking {

    private static final int MAX_DELTA_SIZE = 1_024_000; // 1 MB
    private static final int COMPRESSION_THRESHOLD = 4096; // 4 KB

    // Thread-local encoder pool - eliminates allocation overhead
    private static final ThreadLocal<CisNetworkEncoder> ENCODER_POOL = ThreadLocal
            .withInitial(FabricNetworkCodecFactory::createEncoder);

    private ChunkisNetworking() {
        throw new AssertionError("Utility class");
    }

    public static void sendDelta(ServerPlayerEntity player, WorldChunk chunk) {
        Objects.requireNonNull(player, "Player cannot be null");
        Objects.requireNonNull(chunk, "Chunk cannot be null");

        // Type check and extract delta
        if (!(chunk instanceof ChunkisDeltaDuck deltaDuck)) {
            return;
        }

        ChunkDelta delta = deltaDuck.chunkis$getDelta();

        // Early return for empty deltas (most common case)
        if (delta == null || delta.isEmpty()) {
            return;
        }

        // Validate player is connected
        if (player.isRemoved() || player.isDisconnected()) {
            return;
        }

        ChunkPos pos = chunk.getPos();

        try {
            // Use pooled encoder
            byte[] data = ENCODER_POOL.get().encode(delta);

            // Validate size
            if (data.length > MAX_DELTA_SIZE) {
                Chunkis.LOGGER.error(
                        "Delta too large for chunk ({}, {}): {} bytes",
                        pos.x, pos.z, data.length);
                return;
            }

            // Determine if compression should be used based on threshold
            boolean shouldCompress = data.length >= COMPRESSION_THRESHOLD;

            // Send packet with 4 arguments: data, chunkX, chunkZ, compressed
            ServerPlayNetworking.send(
                    player,
                    new ChunkDeltaPayload(data, pos.x, pos.z, shouldCompress));

        } catch (Exception e) {
            Chunkis.LOGGER.error(
                    "Failed to send delta for chunk ({}, {}): {}",
                    pos.x, pos.z, e.getMessage());
        }
    }
}