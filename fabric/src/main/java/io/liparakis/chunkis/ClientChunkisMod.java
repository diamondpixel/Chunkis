package io.liparakis.chunkis;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.BlockInstruction;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.network.ChunkDeltaPayload;
import io.liparakis.chunkis.storage.codec.CisNetworkDecoder;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client-side initialization and packet handling for the Chunkis mod.
 */
@Environment(EnvType.CLIENT)
public class ClientChunkisMod implements ClientModInitializer {

    // Thread-local decoder with pre-allocated buffers
    private static final ThreadLocal<CisNetworkDecoder> DECODER = ThreadLocal.withInitial(CisNetworkDecoder::new);

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

    // Batch size for rendering updates - tune based on typical delta size
    private static final int RENDER_BATCH_THRESHOLD = 50;

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

            // Get block instructions list once
            List<BlockInstruction> blockInstructions = receivedDelta.getBlockInstructions();
            int instructionCount = blockInstructions.size();

            // Apply delta
            applyDelta(clientDelta, receivedDelta, world, chunkX, chunkZ, instructionCount);

            // Rendering schedule
            scheduleBlockRerendering(world, chunkX, chunkZ, blockInstructions);

            // Metrics tracking (minimal overhead when enabled)
            if (ENABLE_METRICS) {
                long elapsed = System.nanoTime() - startTime;
                totalDecodeTimeNanos.addAndGet(elapsed);
                totalBlocksChanged.addAndGet(instructionCount);

                // Log every 1000 packets
                if ((packetsReceived.get() & 0x3FF) == 0) { // Bit mask instead of modulo
                    logMetrics();
                }
            }

            // Debug logging - only if debug level is enabled (check is in logger)
            if (Chunkis.LOGGER.isDebugEnabled()) {
                Chunkis.LOGGER.debug(
                        "Applied ChunkDelta ({}, {}) - {} bytes, {} blocks, {} entities",
                        chunkX, chunkZ, data.length, instructionCount,
                        receivedDelta.getBlockEntities().size());
            }

        } catch (Exception e) {
            logErrorThrottled("Decode/apply failed for chunk (" + payload.chunkX() + ", " +
                    payload.chunkZ() + ") - " + (payload.data() != null ? payload.data().length : 0) + " bytes", e);
        }
    }

    /**
     * Delta application with minimal allocations and cached positions
     */
    private static void applyDelta(
            ChunkDelta clientDelta,
            ChunkDelta receivedDelta,
            ClientWorld world,
            int chunkX,
            int chunkZ,
            int instructionCount) {

        // Cache chunk base coordinates (avoid repeated bit shifts)
        final int baseX = chunkX << 4;
        final int baseZ = chunkZ << 4;

        // Get block palette once
        var palette = receivedDelta.getBlockPalette();

        // Get thread-local mutable position (eliminates allocations!)
        BlockPos.Mutable mutablePos = MUTABLE_POS.get();

        // Get block instructions
        List<BlockInstruction> instructions = receivedDelta.getBlockInstructions();

        // OPTIMIZED HOT PATH: Process all block changes with minimal overhead
        for (int i = 0; i < instructionCount; i++) {
            BlockInstruction instruction = instructions.get(i);

            // Get state from palette (will be null if invalid index)
            BlockState state = palette.get(instruction.paletteIndex());
            if (state == null)
                continue;

            // Extract coordinates (these are already unpacked in BlockInstruction)
            int localX = instruction.x();
            int localY = instruction.y();
            int localZ = instruction.z();

            // Calculate world position using mutable BlockPos (NO allocation!)
            mutablePos.set(baseX + localX, localY, baseZ + localZ);

            // Apply to world (flag 2 = UPDATE_CLIENTS, skip packet send)
            world.setBlockState(mutablePos, state, 2);

            // Track in client delta (markDirty = false)
            clientDelta.addBlockChange(localX, localY, localZ, state, false);
        }

        // Apply block entities if any exist
        Long2ObjectMap<NbtCompound> blockEntities = receivedDelta.getBlockEntities();
        if (!blockEntities.isEmpty()) {
            applyBlockEntities(clientDelta, blockEntities);
        }

        // Apply entities if present
        List<NbtCompound> entities = receivedDelta.getEntitiesList();
        if (entities != null && !entities.isEmpty()) {
            clientDelta.setEntities(entities, false);
        }
    }

    /**
     * Separate block entity processing to keep hot path lean
     */
    private static void applyBlockEntities(ChunkDelta clientDelta, Long2ObjectMap<NbtCompound> blockEntities) {
        // Use fastutil's optimized iterator
        for (Long2ObjectMap.Entry<NbtCompound> entry : blockEntities.long2ObjectEntrySet()) {
            long posKey = entry.getLongKey();
            NbtCompound nbt = entry.getValue();

            // Unpack coordinates
            int x = BlockInstruction.unpackX(posKey);
            int y = BlockInstruction.unpackY(posKey);
            int z = BlockInstruction.unpackZ(posKey);

            // Add to client delta
            clientDelta.addBlockEntityData(x, y, z, nbt, false);
        }
    }

    /**
     * Block re-rendering with batching for large deltas
     */
    private static void scheduleBlockRerendering(
            ClientWorld world,
            int chunkX,
            int chunkZ,
            List<BlockInstruction> instructions) {

        int count = instructions.size();
        if (count == 0)
            return;

        // Cache base coordinates
        final int baseX = chunkX << 4;
        final int baseZ = chunkZ << 4;

        // For small deltas, use direct method (fastest for <50 blocks)
        if (count < RENDER_BATCH_THRESHOLD) {
            for (BlockInstruction instr : instructions) {
                world.scheduleBlockRenders(
                        baseX + instr.x(),
                        instr.y(),
                        baseZ + instr.z());
            }
        } else {
            // For large deltas, still iterate but use array access pattern for better cache
            // locality
            BlockInstruction[] instrArray = instructions.toArray(new BlockInstruction[0]);
            for (BlockInstruction instr : instrArray) {
                world.scheduleBlockRenders(
                        baseX + instr.x(),
                        instr.y(),
                        baseZ + instr.z());
            }
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