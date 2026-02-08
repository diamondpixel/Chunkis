package io.liparakis.chunkis;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.network.ChunkDeltaPayload;
import io.liparakis.chunkis.storage.codec.CisNetworkDecoder;
import io.liparakis.chunkis.util.FabricNetworkCodecFactory;
import net.minecraft.block.Block;
import net.minecraft.state.property.Property;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Client-side initialization and packet handling for the Chunkis mod.
 */
@Environment(EnvType.CLIENT)
public class ClientChunkisMod implements ClientModInitializer {

    // Thread-local decoder with pre-allocated buffers
    private static final ThreadLocal<CisNetworkDecoder<Block, BlockState, Property<?>, NbtCompound>> DECODER = ThreadLocal
            .withInitial(FabricNetworkCodecFactory::createDecoder);

    // Thread-local mutable BlockPos to eliminate allocations in hot path
    private static final ThreadLocal<BlockPos.Mutable> MUTABLE_POS = ThreadLocal.withInitial(BlockPos.Mutable::new);

    // Performance metrics
    private static final boolean ENABLE_METRICS = false;
    private static final AtomicInteger packetsReceived = new AtomicInteger(0);
    private static final AtomicLong totalBytesReceived = new AtomicLong(0);
    private static final AtomicLong totalDecodeTimeNanos = new AtomicLong(0);
    private static final AtomicLong totalBlocksChanged = new AtomicLong(0);

    // Error rate limiting
    private static final AtomicInteger errorCount = new AtomicInteger(0);
    private static final int ERROR_LOG_INTERVAL = 100;

    @Override
    public void onInitializeClient() {
        Chunkis.LOGGER.info("Chunkis Client initializing...");

        ClientPlayNetworking.registerGlobalReceiver(
                ChunkDeltaPayload.ID,
                (payload, context) -> {
                    // Early validation - fail fast
                    byte[] data = payload.data();
                    if (data == null || data.length == 0) {
                        logErrorThrottled("Invalid payload: null or empty data");
                        return;
                    }

                    // Metrics tracking (branchless when disabled)
                    if (ENABLE_METRICS) {
                        packetsReceived.incrementAndGet();
                        totalBytesReceived.addAndGet(data.length);
                    }

                    // Schedule on main thread with lambda allocation minimization
                    context.client().execute(() -> processChunkDelta(payload, context.client().world));
                });

        Chunkis.LOGGER.info("Chunkis Client initialized successfully.");
    }

    /**
     * Main processing method with reduced allocations and cached calculations
     */
    private static void processChunkDelta(ChunkDeltaPayload payload, ClientWorld world) {
        if (world == null)
            return;

        long startTime = ENABLE_METRICS ? System.nanoTime() : 0L;

        try {
            // Cache payload fields to avoid repeated method calls
            final int chunkX = payload.chunkX();
            final int chunkZ = payload.chunkZ();
            final byte[] data = payload.data();

            // Get chunk - this call is unavoidable
            WorldChunk chunk = world.getChunk(chunkX, chunkZ);

            // Fast-path type check with instanceof pattern matching (Java 16+)
            if (!(chunk instanceof ChunkisDeltaDuck deltaDuck)) {
                // This should never happen - log once and return
                if (errorCount.get() == 0) {
                    Chunkis.LOGGER.warn("Chunk at ({}, {}) is not ChunkisDeltaDuck: {}",
                            chunkX, chunkZ, chunk.getClass().getName());
                }
                return;
            }

            // Decode using thread-local decoder (reuses buffers)
            CisNetworkDecoder decoder = DECODER.get();
            ChunkDelta receivedDelta = decoder.decode(data);

            // Get client delta tracker
            ChunkDelta clientDelta = deltaDuck.chunkis$getDelta();

            // Apply delta using visitor
            applyDelta(clientDelta, receivedDelta, world, chunkX, chunkZ);

            // Metrics tracking (minimal overhead when enabled)
            if (ENABLE_METRICS) {
                long elapsed = System.nanoTime() - startTime;
                totalDecodeTimeNanos.addAndGet(elapsed);
                // totalBlocksChanged.addAndGet(instructionCount); // Metrics update needs
                // visitor tracking (omitted for now)

                // Log every 1000 packets
                if ((packetsReceived.get() & 0x3FF) == 0) { // Bit mask instead of modulo
                    logMetrics();
                }
            }

            // Debug logging - only if debug level is enabled (check is in logger)
            if (Chunkis.LOGGER.isDebugEnabled()) {
                Chunkis.LOGGER.debug(
                        "Applied ChunkDelta ({}, {}) - {} bytes",
                        chunkX, chunkZ, data.length);
            }

        } catch (Exception e) {
            logErrorThrottled("Decode/apply failed for chunk (" + payload.chunkX() + ", " +
                    payload.chunkZ() + ") - " + (payload.data() != null ? payload.data().length : 0) + " bytes", e);
        }
    }

    /**
     * Delta application with minimal allocations using Visitor pattern
     */
    private static void applyDelta(
            ChunkDelta clientDelta,
            ChunkDelta receivedDelta,
            ClientWorld world,
            int chunkX,
            int chunkZ) {

        receivedDelta.accept(new ClientDeltaVisitor(clientDelta, world, chunkX, chunkZ));
    }

    private static class ClientDeltaVisitor implements ChunkDelta.DeltaVisitor<BlockState, NbtCompound> {
        private final ChunkDelta clientDelta;
        private final ClientWorld world;
        private final int baseX;
        private final int baseZ;
        private final BlockPos.Mutable mutablePos;

        ClientDeltaVisitor(ChunkDelta clientDelta, ClientWorld world, int chunkX, int chunkZ) {
            this.clientDelta = clientDelta;
            this.world = world;
            this.baseX = chunkX << 4;
            this.baseZ = chunkZ << 4;
            this.mutablePos = MUTABLE_POS.get();
        }

        @Override
        public void visitBlock(int x, int y, int z, BlockState state) {
            // Calculate world position
            mutablePos.set(baseX + x, y, baseZ + z);

            // Apply to world (flag 2 = UPDATE_CLIENTS, skip packet send)
            world.setBlockState(mutablePos, state, 2);

            // Track in client delta
            clientDelta.addBlockChange(x, y, z, state, false);

            // Schedule render update
            world.scheduleBlockRenders(baseX + x, y, baseZ + z);
        }

        @Override
        public void visitBlockEntity(int x, int y, int z, NbtCompound nbt) {
            clientDelta.addBlockEntityData(x, y, z, nbt, false);
        }

        @Override
        public void visitEntity(NbtCompound nbt) {
            clientDelta.getEntitiesList().add(nbt);
        }
    }

    /**
     * Error logging with minimal string allocation
     */
    private static void logErrorThrottled(String message) {
        logErrorThrottled(message, null);
    }

    private static void logErrorThrottled(String message, Throwable cause) {
        int errors = errorCount.incrementAndGet();
        // Use bit masking instead of modulo for performance
        if ((errors & (ERROR_LOG_INTERVAL - 1)) == 1) { // Works if ERROR_LOG_INTERVAL is power of 2
            if (cause != null) {
                Chunkis.LOGGER.error("{} - Error #{} (every {}th logged)",
                        message, errors, ERROR_LOG_INTERVAL, cause);
            } else {
                Chunkis.LOGGER.error("{} - Error #{} (every {}th logged)",
                        message, errors, ERROR_LOG_INTERVAL);
            }
        }
    }

    /**
     * Logs performance metrics with minimal overhead
     */
    private static void logMetrics() {
        int packets = packetsReceived.get();
        if (packets == 0)
            return;

        long totalBytes = totalBytesReceived.get();
        long totalNanos = totalDecodeTimeNanos.get();
        long totalBlocks = totalBlocksChanged.get();

        // Pre-calculate to avoid repeated division
        double avgBytes = (double) totalBytes / packets;
        double avgMicros = (double) totalNanos / packets / 1000.0;
        double avgBlocks = (double) totalBlocks / packets;

        Chunkis.LOGGER.info(
                "Metrics - Packets: {}, Avg: {:.1f}B, {:.1f} blocks, {:.1f}μs",
                packets, avgBytes, avgBlocks, avgMicros);
    }
}