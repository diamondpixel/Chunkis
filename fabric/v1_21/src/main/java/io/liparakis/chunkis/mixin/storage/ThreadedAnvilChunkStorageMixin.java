package io.liparakis.chunkis.mixin.storage;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.storage.CisStorage;
import io.liparakis.chunkis.util.ChunkBlockEntityCapture;
import io.liparakis.chunkis.util.ChunkEntityCapture;
import io.liparakis.chunkis.util.CisNbtUtil;
import io.liparakis.chunkis.util.FabricCisStorageHelper;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.OptionalChunk;
import net.minecraft.server.world.ServerChunkLoadingManager;
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
 *
 * @author Liparakis
 * @version 1.0
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

    /*
     * The underlying storage manager for .cis files.
     */
    /**
     * The underlying storage manager for .cis files.
     */
    @Inject(method = "close", at = @At("HEAD"))
    private void chunkis$onClose(CallbackInfo ci) {
        // Ensure CIS storage is properly closed when the server shuts down
        FabricCisStorageHelper.closeStorage(world);
    }

    /**
     * Intercepts chunk NBT loading to provide CIS-stored chunks.
     */
    @Inject(method = "getUpdatedChunkNbt(Lnet/minecraft/util/math/ChunkPos;)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"), cancellable = true)
    private void chunkis$onGetUpdatedChunkNbt(
            ChunkPos pos,
            CallbackInfoReturnable<CompletableFuture<Optional<NbtCompound>>> cir) {

        CisChunkPos cisPos = new CisChunkPos(pos.x, pos.z);
        ChunkDelta delta = chunkis$getOrCreateStorage().load(cisPos);

        NbtCompound nbt = chunkis$createChunkNbt(pos, delta);
        cir.setReturnValue(CompletableFuture.completedFuture(Optional.of(nbt)));
    }

    /**
     * Lazy initialization of CisStorage.
     * <p>
     * Thread-safe as all access is on server thread.
     *
     * @return The active CisStorage instance for this world.
     */
    @Unique
    @SuppressWarnings("rawtypes")
    private CisStorage chunkis$getOrCreateStorage() {
        return FabricCisStorageHelper.getStorage(world);
    }

    /**
     * Intercepts chunk saving to use CIS storage instead of anilla format.
     * Captures block entities and entities, then saves if dirty.
     */

    @Inject(method = "save(Lnet/minecraft/server/world/ChunkHolder;)Z", at = @At("HEAD"), cancellable = true)
    private void chunkis$onSave(ChunkHolder chunkHolder, CallbackInfoReturnable<Boolean> cir) {

        var future = chunkHolder.getSavingFuture();

        OptionalChunk<Chunk> optionalChunk = (OptionalChunk<Chunk>) future.getNow(null);

        Chunk chunk = (optionalChunk != null) ? optionalChunk.orElse(null) : null;

        if (chunk == null) {
            chunk = chunkHolder.getWorldChunk();
        }

        if (!(chunk instanceof ChunkisDeltaDuck duck)) {
            return; // Not our chunk, let vanilla handle it
        }

        ChunkDelta delta = duck.chunkis$getDelta();

        // Only attempt capture on WorldChunks where entities/block entities are active
        if (chunk instanceof WorldChunk worldChunk && delta != null) {
            ChunkBlockEntityCapture.captureAll(worldChunk, delta);
            ChunkEntityCapture.captureAll(worldChunk, delta, world);
        }

        // Save only if delta exists and is dirty
        if (delta != null && delta.isDirty()) {
            ChunkPos pos = chunk.getPos();
            CisChunkPos cisPos = new CisChunkPos(pos.x, pos.z);
            chunkis$getOrCreateStorage().save(cisPos, delta);

            if (io.liparakis.chunkis.Chunkis.LOGGER.isDebugEnabled()) {
                io.liparakis.chunkis.Chunkis.LOGGER.debug("Saved CIS chunk {}", pos);
            }
        }

        chunk.setNeedsSaving(false);
        cir.setReturnValue(true);
    }

    // ===== Helper Methods =====

    /**
     * Creates minimal NBT structure for CIS chunk loading.
     * Reuses static version constant to avoid repeated lookups.
     */
    @Unique
    private NbtCompound chunkis$createChunkNbt(ChunkPos pos, ChunkDelta delta) {
        NbtCompound nbt = CisNbtUtil.createBaseNbt(pos, GAME_DATA_VERSION);
        CisNbtUtil.putDelta(nbt, delta);
        return nbt;
    }
}
