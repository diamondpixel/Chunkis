package io.liparakis.chunkis.mixin.storage;

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
 * Mixin for {@link SerializedChunk} to intercept chunk deserialization
 * for Chunkis delta data restoration.
 * <p>
 * When a chunk is loaded from disk, this mixin checks if Chunkis delta data
 * exists in storage. If found, it attaches the delta to the chunk and resets
 * the chunk status to trigger the regeneration pipeline, which will apply
 * the delta modifications on top of fresh terrain.
 *
 * @author Liparakis
 * @version 1.0
 */
@Mixin(SerializedChunk.class)
public class ChunkSerializerMixin {

    @Unique
    private static final Logger LOGGER = io.liparakis.chunkis.Chunkis.LOGGER;

    /**
     * Intercepts chunk conversion to restore Chunkis delta data.
     * <p>
     * When a chunk is converted from serialized form to ProtoChunk, this method:
     * <ol>
     * <li>Loads any associated delta from CIS storage</li>
     * <li>Attaches the delta to the ProtoChunk instance</li>
     * <li>Resets chunk status to {@link ChunkStatus#EMPTY} to trigger
     * regeneration</li>
     * </ol>
     * <p>
     * The status reset ensures the world generator processes the chunk again,
     * allowing the delta to be applied on top of freshly generated terrain.
     *
     * @param world       the server world context
     * @param poiStorage  the point of interest storage
     * @param key         the storage key
     * @param expectedPos the expected chunk position
     * @param cir         callback containing the converted {@link ProtoChunk}
     */
    @Inject(method = "convert", at = @At("RETURN"))
    private static void chunkis$onConvert(
            ServerWorld world,
            PointOfInterestStorage poiStorage,
            StorageKey key,
            ChunkPos expectedPos,
            CallbackInfoReturnable<ProtoChunk> cir) {

        ProtoChunk chunk = cir.getReturnValue();

        if (chunk == null) {
            return;
        }

        ChunkDelta delta = loadDeltaForChunk(chunk.getPos(), world);

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
     */
    @Unique
    @SuppressWarnings("rawtypes")
    private static ChunkDelta loadDeltaFromDisk(ChunkPos position, ServerWorld world) {
        var storage = FabricCisStorageHelper.getStorage(world);
        var cisPosition = new CisChunkPos(position.x, position.z);
        return storage.load(cisPosition);
    }

    /**
     * Attaches a delta to a ProtoChunk instance if it implements
     * {@link ChunkisDeltaDuck}.
     *
     * @param chunk the chunk to attach delta to
     * @param delta the delta to attach
     */
    @Unique
    @SuppressWarnings("rawtypes")
    private static void attachDeltaToChunk(ProtoChunk chunk, ChunkDelta delta) {
        if (chunk instanceof ChunkisDeltaDuck deltaDuck) {
            deltaDuck.chunkis$setDelta(delta);
        }
    }

    /**
     * Resets chunk status to {@link ChunkStatus#EMPTY} to trigger regeneration
     * with delta modifications applied.
     * <p>
     * This forces the chunk through the generation pipeline again, allowing
     * the delta to be applied on top of freshly generated terrain.
     *
     * @param chunk the chunk to reset
     */
    @Unique
    private static void resetChunkStatusForRegeneration(ProtoChunk chunk) {
        chunk.setStatus(ChunkStatus.EMPTY);
    }
}