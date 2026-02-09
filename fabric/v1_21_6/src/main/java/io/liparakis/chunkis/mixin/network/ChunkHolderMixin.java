package io.liparakis.chunkis.mixin.network;

import io.liparakis.chunkis.network.ChunkisNetworking;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Mixin that intercepts chunk packet transmission to players.
 * <p>
 * This mixin hooks into the {@code ChunkHolder#sendPacketToPlayers} method to
 * detect
 * when chunk data is being sent to players, allowing the Chunkis mod to send
 * optimized delta packets alongside the vanilla chunk data.
 * </p>
 *
 * <h2>Performance Characteristics:</h2>
 * <ul>
 * <li><b>instanceof check:</b> O(1) - JVM optimizes with type profile inline
 * cache</li>
 * <li><b>Null check hoisting:</b> Single chunk retrieval for all players</li>
 * <li><b>Loop overhead:</b> O(n) where n = number of players receiving
 * chunk</li>
 * </ul>
 *
 * <h2>Optimizations Applied:</h2>
 * <ul>
 * <li>Single instanceof check with pattern matching (JVM optimized)</li>
 * <li>Early return on null chunk to avoid unnecessary iteration</li>
 * <li>Chunk reference cached before loop to prevent repeated virtual calls</li>
 * <li>Direct class reference instead of string comparison for type
 * checking</li>
 * </ul>
 *
 * @author Liparakis
 * @version 1.1
 * @see ChunkHolder
 * @see ChunkisNetworking#sendDelta(ServerPlayerEntity, WorldChunk)
 */
@Mixin(ChunkHolder.class)
public abstract class ChunkHolderMixin {

    /**
     * Retrieves the world chunk managed by this chunk holder.
     * <p>
     * This is a shadow method from the original {@link ChunkHolder} class.
     * </p>
     *
     * @return The world chunk, or null if not loaded
     */
    @Shadow
    public abstract WorldChunk getWorldChunk();

    /**
     * Intercepts packet transmission to players and sends delta updates for chunk
     * data packets.
     * <p>
     * This method is injected at the HEAD of
     * {@code ChunkHolder#sendPacketToPlayers},
     * executing before the original packet is sent. When a
     * {@link ChunkDataS2CPacket} is
     * detected, it sends an optimized delta packet to each player via
     * {@link ChunkisNetworking}.
     * </p>
     *
     * <h3>Performance Analysis:</h3>
     * <ul>
     * <li><b>Best case:</b> O(1) - packet is not a ChunkDataS2CPacket (single
     * instanceof check)</li>
     * <li><b>Worst case:</b> O(n) - packet is ChunkDataS2CPacket with n
     * players</li>
     * <li><b>instanceof overhead:</b> ~1-2ns with JIT optimization (type profile
     * caching)</li>
     * <li><b>Chunk retrieval:</b> Single call (hoisted outside loop)</li>
     * </ul>
     *
     * <h3>Memory Impact:</h3>
     * <ul>
     * <li>No additional heap allocations in this method</li>
     * <li>Stack space: ~32 bytes (local variables + method frame)</li>
     * <li>Delta packets allocated in {@link ChunkisNetworking#sendDelta}</li>
     * </ul>
     *
     * @param players The list of players receiving the packet (typically 1-10
     *                players per chunk)
     * @param packet  The packet being sent (checked for ChunkDataS2CPacket type)
     * @param ci      Mixin callback info (unused but required by injection)
     */
    @Inject(method = "sendPacketToPlayers", at = @At("HEAD"))
    private void chunkis$onSendPacketToPlayers(
            List<ServerPlayerEntity> players,
            Packet<?> packet,
            CallbackInfo ci) {

        if (!(packet instanceof ChunkDataS2CPacket))
            return;

        WorldChunk chunk = this.getWorldChunk();

        if (chunk == null)
            return;

        // Send delta to each player
        for (ServerPlayerEntity player : players) {
            ChunkisNetworking.sendDelta(player, chunk);
        }
    }
}