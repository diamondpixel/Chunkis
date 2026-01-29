package io.liparakis.chunkis.mixin.storage;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.storage.CisStorage;
import io.liparakis.chunkis.util.ChunkBlockEntityCapture;
import io.liparakis.chunkis.util.ChunkEntityCapture;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.server.world.OptionalChunk;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Mixin to intercept chunk loading/saving and delegate to CIS storage system.
 * <p>
 * Thread Safety: All methods execute on the server thread context.
 * Performance: Minimizes allocations and uses lazy initialization.
 */
@Mixin(ServerChunkLoadingManager.class)
public abstract class ThreadedAnvilChunkStorageMixin {

    @Shadow
    @Final
    ServerWorld world;

    /**
     * The game's current data version, used to ensure NBT compatibility.
     */
    @Unique
    private static final int GAME_DATA_VERSION = SharedConstants.getGameVersion().getSaveVersion().getId();
    /**
     * The underlying storage manager for .cis files.
     */
    @Unique
    private CisStorage cisStorage;

    /**
     * Helper for capturing and restoring entity states in chunks.
     */
    @Unique
    private ChunkEntityCapture entityCapture;

    /**
     * Helper for capturing and restoring block entity states in chunks.
     */
    @Unique
    private ChunkBlockEntityCapture blockEntityCapture;

    /**
     * Ensures storage is closed when chunk manager shuts down.
     */
    @Inject(method = "close", at = @At("HEAD"))
    private void chunkis$onClose(CallbackInfo ci) {
        if (cisStorage != null) {
            cisStorage.close();
            cisStorage = null;
        }
    }

    /**
     * Intercepts chunk NBT loading to provide CIS-stored chunks.
     * Only creates NBT wrapper if delta exists, avoiding unnecessary allocations.
     */
    @Inject(method = "getUpdatedChunkNbt(Lnet/minecraft/util/math/ChunkPos;)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"), cancellable = true)
    private void chunkis$onGetUpdatedChunkNbt(
            ChunkPos pos,
            CallbackInfoReturnable<CompletableFuture<Optional<NbtCompound>>> cir) {

        ChunkDelta delta = chunkis$getOrCreateStorage().load(pos);

        NbtCompound nbt = chunkis$createChunkNbt(pos, delta);
        cir.setReturnValue(CompletableFuture.completedFuture(Optional.of(nbt)));
    }

    /**
     * Intercepts chunk saving to use CIS storage instead of vanilla format.
     * Captures block entities and entities, then saves if dirty.
     */
    @Inject(method = "save(Lnet/minecraft/server/world/ChunkHolder;)Z", at = @At("HEAD"), cancellable = true)
    private void chunkis$onSave(ChunkHolder chunkHolder, CallbackInfoReturnable<Boolean> cir) {
        // Attempt to get chunk from saving future (standard way to get ProtoChunk or
        // WorldChunk during save)
        var future = chunkHolder.getSavingFuture();

        @SuppressWarnings("unchecked")
        OptionalChunk<Chunk> optionalChunk = (OptionalChunk<Chunk>) future.getNow(null);

        Chunk chunk = (optionalChunk != null) ? optionalChunk.orElse(null) : null;

        if (chunk == null) {
            // Fallback to WorldChunk if future is not ready/available (mostly for fully
            // loaded chunks)
            chunk = chunkHolder.getWorldChunk();
        }

        if (!(chunk instanceof ChunkisDeltaDuck duck)) {
            return; // Not our chunk, let vanilla handle it
        }

        ChunkDelta delta = duck.chunkis$getDelta();

        // Only attempt capture on WorldChunks where entities/block entities are active
        if (chunk instanceof WorldChunk worldChunk && delta != null) {
            chunkis$getOrCreateBlockEntityCapture().captureAll(worldChunk, delta);
            chunkis$getOrCreateEntityCapture().captureAll(worldChunk, delta, world);
        }

        // Save only if delta exists and is dirty
        if (delta != null && delta.isDirty()) {
            ChunkPos pos = chunk.getPos();
            chunkis$getOrCreateStorage().save(pos, delta);

            if (io.liparakis.chunkis.ChunkisMod.LOGGER.isDebugEnabled()) {
                io.liparakis.chunkis.ChunkisMod.LOGGER.debug("Saved CIS chunk {}", pos);
            }
        }

        // CRITICAL: Mark chunk as saved so key doesn't get re-added to dirty queue
        chunk.setNeedsSaving(false);

        cir.setReturnValue(true); // Suppress vanilla save
    }

    // ===== Helper Methods =====

    /**
     * Lazy initialization of CisStorage.
     * Thread-safe as all access is on server thread.
     */
    @Unique
    private CisStorage chunkis$getOrCreateStorage() {
        if (cisStorage == null) {
            cisStorage = new CisStorage(world);
        }
        return cisStorage;
    }

    /**
     * Lazy initialization of entity capture helper.
     */
    @Unique
    private ChunkEntityCapture chunkis$getOrCreateEntityCapture() {
        if (entityCapture == null) {
            entityCapture = new ChunkEntityCapture();
        }
        return entityCapture;
    }

    /**
     * Lazy initialization of block entity capture helper.
     */
    @Unique
    private ChunkBlockEntityCapture chunkis$getOrCreateBlockEntityCapture() {
        if (blockEntityCapture == null) {
            blockEntityCapture = new ChunkBlockEntityCapture();
        }
        return blockEntityCapture;
    }

    /**
     * Creates minimal NBT structure for CIS chunk loading.
     * Reuses static version constant to avoid repeated lookups.
     */
    @Unique
    private NbtCompound chunkis$createChunkNbt(ChunkPos pos, ChunkDelta delta) {
        NbtCompound nbt = new NbtCompound();
        nbt.putInt("DataVersion", GAME_DATA_VERSION);
        nbt.putString("Status", "minecraft:empty");
        nbt.putInt("xPos", pos.x);
        nbt.putInt("zPos", pos.z);

        NbtCompound chunkisData = new NbtCompound();
        delta.writeNbt(chunkisData);
        nbt.put("ChunkisData", chunkisData);

        return nbt;
    }
}