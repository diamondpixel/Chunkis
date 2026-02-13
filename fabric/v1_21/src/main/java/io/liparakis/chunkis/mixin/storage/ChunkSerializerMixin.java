package io.liparakis.chunkis.mixin.storage;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.util.CisNbtUtil;
import io.liparakis.chunkis.util.FabricCisStorageHelper;
import io.liparakis.chunkis.util.GlobalChunkTracker;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.ChunkSerializer;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.storage.StorageKey;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin for {@link ChunkSerializer} to intercept chunk deserialization
 * for Chunkis delta data persistence.
 * <p>
 * This mixin restores previously saved chunk modifications from NBT data
 * and prepares chunks for delta application during world generation.
 *
 * @author Liparakis
 * @version 1.0
 */
@Mixin(ChunkSerializer.class)
public class ChunkSerializerMixin {

    @Unique
    private static final Logger LOGGER = io.liparakis.chunkis.Chunkis.LOGGER;

    /**
     * Intercepts chunk deserialization to restore Chunkis delta data.
     *
     * @param world      the server world context
     * @param poiStorage the point of interest storage
     * @param key        the storage key for the chunk
     * @param pos        the chunk position being deserialized
     * @param nbt        the NBT data containing chunk information
     * @param cir        callback containing the deserialized {@link ProtoChunk}
     */
    @Inject(method = "deserialize", at = @At("RETURN"))
    private static void chunkis$onDeserialize(
            ServerWorld world,
            PointOfInterestStorage poiStorage,
            StorageKey key,
            ChunkPos pos,
            NbtCompound nbt,
            CallbackInfoReturnable<ProtoChunk> cir) {

        if (!hasChunkisData(nbt)) {
            return;
        }

        ProtoChunk chunk = cir.getReturnValue();
        if (chunk == null) {
            return;
        }

        restoreChunkDelta(world, pos, chunk);
    }

    /**
     * Checks if the NBT compound contains Chunkis delta data.
     */
    @Unique
    private static boolean hasChunkisData(NbtCompound nbt) {
        return nbt.contains(CisNbtUtil.CHUNKIS_DATA_KEY);
    }

    /**
     * Restores chunk delta from persistent storage and prepares the chunk
     * for regeneration.
     */
    @Unique
    private static void restoreChunkDelta(ServerWorld world, ChunkPos pos, ProtoChunk chunk) {
        ChunkDelta<?, ?> delta = loadDeltaForChunk(pos, world);

        if (delta == null || delta.isEmpty()) {
            return;
        }

        // Trigger migration if needed
        if (delta.needsMigration()) {
            LOGGER.info("Chunkis [DEBUG]: Migrating chunk delta for {} to CIS8 format", pos);
            GlobalChunkTracker.addDelta(pos, delta);
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
     * Attaches a delta to a chunk instance.
     */
    @Unique
    @SuppressWarnings("rawtypes")
    private static void attachDeltaToChunk(ProtoChunk chunk, ChunkDelta delta) {
        if (chunk instanceof ChunkisDeltaDuck deltaDuck) {
            deltaDuck.chunkis$setDelta(delta);
        }
    }

    /**
     * Resets chunk status for regeneration.
     */
    @Unique
    private static void resetChunkStatusForRegeneration(ProtoChunk chunk) {
        chunk.setStatus(ChunkStatus.EMPTY);
    }
}