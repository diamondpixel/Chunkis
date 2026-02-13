package io.liparakis.chunkis.mixin.storage;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.util.FabricCisStorageHelper;
import io.liparakis.chunkis.util.GlobalChunkTracker;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.SerializedChunk;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.storage.StorageKey;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin for {@link SerializedChunk} to intercept chunk deserialization.
 *
 * <p>
 * Integrates Chunkis delta restoration into Minecraft's standard chunk loading
 * pipeline. When chunks are loaded from disk, this mixin attempts to restore
 * associated delta data from either memory (GlobalChunkTracker) or persistent
 * storage, then resets the chunk status to trigger terrain regeneration with
 * the delta applied.
 *
 * <p>
 * <b>Restoration Flow:</b>
 * <ol>
 * <li>Check GlobalChunkTracker for in-memory delta (handles recent saves)</li>
 * <li>Fallback to disk storage if not found in memory</li>
 * <li>Attach delta to ProtoChunk for downstream processing</li>
 * <li>Force chunk status to EMPTY to trigger generation pipeline</li>
 * </ol>
 *
 * @author Liparakis
 * @version 2.1
 */
@Mixin(SerializedChunk.class)
public class ChunkSerializerMixin {

    @Unique
    private static final Logger LOGGER = Chunkis.LOGGER;

    /**
     * Injects at the end of chunk deserialization to restore Chunkis delta data.
     *
     * <p>
     * This method implements a two-tier loading strategy: first checking the
     * in-memory GlobalChunkTracker for recent deltas (avoiding race conditions
     * during
     * rapid save/load cycles), then falling back to persistent storage. If a delta
     * is found, the chunk status is reset to {@link ChunkStatus#EMPTY} to ensure
     * the world generator applies the delta atop freshly generated terrain.
     *
     * @param world       the server world context for the chunk
     * @param poiStorage  the point of interest storage (unused but required by
     *                    inject)
     * @param key         the storage key for the chunk region
     * @param expectedPos the expected chunk position (unused but required by
     *                    inject)
     * @param cir         callback containing the deserialized {@link ProtoChunk}
     */
    @Inject(method = "convert", at = @At("RETURN"))
    private static void chunkis$onConvert(
            ServerWorld world,
            PointOfInterestStorage poiStorage,
            StorageKey key,
            ChunkPos expectedPos,
            CallbackInfoReturnable<ProtoChunk> cir) {

        var chunk = cir.getReturnValue();
        if (chunk == null) {
            return;
        }

        var delta = loadDeltaForChunk(chunk.getPos(), world);

        if (delta == null || delta.isEmpty()) {
            return;
        }

        // Trigger migration if needed
        if (delta.needsMigration()) {
            LOGGER.info("Chunkis [DEBUG]: Migrating chunk delta for {} to CIS8 format", chunk.getPos());
            GlobalChunkTracker.addDelta(chunk.getPos(), delta);
        }

        attachDeltaToChunk(chunk, delta);
        resetChunkStatusForRegeneration(chunk);
    }

    /**
     * Loads a chunk delta using a two-tier strategy: memory-first, then disk.
     *
     * <p>
     * Checks GlobalChunkTracker first to handle chunks that were recently marked
     * dirty but may not have been persisted yet. This prevents data loss during
     * rapid unload/reload cycles. Falls back to disk storage if not found in
     * memory.
     *
     * @param position the chunk position to load delta for
     * @param world    the server world context
     * @return the loaded delta, or null if none exists
     */
    @Unique
    @SuppressWarnings("rawtypes")
    private static ChunkDelta loadDeltaForChunk(ChunkPos position, ServerWorld world) {
        // Tier 1: Check in-memory tracker (handles race conditions)
        var delta = loadDeltaFromMemory(position);
        if (delta != null) {
            LOGGER.info("Chunkis [DEBUG]: Loaded delta from GlobalChunkTracker for {} (Size: {} blocks)",
                    position, delta.getBlockInstructions().size());
            return delta;
        }

        // Tier 2: Load from persistent storage
        var diskDelta = loadDeltaFromDisk(position, world);
        if (diskDelta != null) {
            LOGGER.info("Chunkis [DEBUG]: Loaded delta from DISK for {} (Size: {} blocks)",
                    position, diskDelta.getBlockInstructions().size());
        } else {
            LOGGER.info("Chunkis [DEBUG]: No delta found on disk for {}", position);
        }
        return diskDelta;
    }

    /**
     * Attempts to load a delta from the in-memory GlobalChunkTracker.
     *
     * @param position the chunk position
     * @return the delta if found and non-empty, otherwise null
     */
    @Unique
    @SuppressWarnings("rawtypes")
    private static ChunkDelta loadDeltaFromMemory(ChunkPos position) {
        var delta = GlobalChunkTracker.getDelta(position);
        if (delta != null && !delta.isEmpty()) {
            return delta;
        }
        return null;
    }

    /**
     * Loads a delta from persistent disk storage.
     *
     * @param position the chunk position
     * @param world    the server world context
     * @return the delta if found, otherwise null
     */
    @Unique
    @SuppressWarnings("rawtypes")
    private static ChunkDelta loadDeltaFromDisk(ChunkPos position, ServerWorld world) {
        var storage = FabricCisStorageHelper.getStorage(world);
        var cisPosition = new CisChunkPos(position.x, position.z);
        return storage.load(cisPosition);
    }

    /**
     * Attaches a delta to a ProtoChunk for downstream processing.
     *
     * <p>
     * The delta is stored via the ChunkisDeltaDuck interface, making it available
     * to subsequent mixins (e.g., WorldChunkMixin) that handle terrain generation.
     *
     * @param chunk the chunk to attach the delta to
     * @param delta the delta data to attach
     */
    @Unique
    @SuppressWarnings("rawtypes")
    private static void attachDeltaToChunk(ProtoChunk chunk, ChunkDelta delta) {
        if (chunk instanceof ChunkisDeltaDuck deltaReceiver) {
            deltaReceiver.chunkis$setDelta(delta);
        }
    }

    /**
     * Resets chunk status to EMPTY to trigger the generation pipeline.
     *
     * <p>
     * This forces Minecraft to regenerate the chunk's terrain, allowing the
     * Chunkis system to apply the delta during generation rather than as a
     * post-processing step. This ensures proper integration with worldgen features.
     *
     * @param chunk the chunk to reset
     */
    @Unique
    private static void resetChunkStatusForRegeneration(ProtoChunk chunk) {
        chunk.setStatus(ChunkStatus.EMPTY);
    }
}