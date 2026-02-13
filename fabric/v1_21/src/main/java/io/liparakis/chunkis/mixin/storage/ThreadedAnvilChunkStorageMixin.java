package io.liparakis.chunkis.mixin.storage;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.storage.CisStorage;
import io.liparakis.chunkis.util.ChunkBlockEntityCapture;
import io.liparakis.chunkis.util.ChunkEntityCapture;
import io.liparakis.chunkis.util.CisNbtUtil;
import io.liparakis.chunkis.util.FabricCisStorageHelper;
import io.liparakis.chunkis.util.GlobalChunkTracker;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Mixin to intercept chunk loading and saving operations, delegating to
 * the Chunkis CIS storage system.
 * <p>
 * This mixin replaces vanilla chunk persistence with the CIS format, which
 * stores only chunk modifications (deltas) rather than full chunk data.
 * <p>
 * <b>Thread Safety:</b> All injected methods execute on the server thread.
 * <p>
 * <b>Performance:</b> Uses lazy initialization and minimizes allocations.
 *
 * @author Liparakis
 * @version 2.0
 */
@Mixin(ServerChunkLoadingManager.class)
public abstract class ThreadedAnvilChunkStorageMixin {

    @Shadow
    @Final
    ServerWorld world;

    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger("Chunkis");

    @Unique
    private static final int GAME_DATA_VERSION = net.minecraft.SharedConstants.getGameVersion().getSaveVersion()
            .getId();

    /**
     * Ensures CIS storage is properly closed during server shutdown.
     * <p>
     * This prevents resource leaks and ensures all pending writes are flushed.
     *
     * @param ci callback info
     */
    @Inject(method = "close", at = @At("HEAD"))
    private void chunkis$onClose(CallbackInfo ci) {
        FabricCisStorageHelper.closeStorage(world);
    }

    /**
     * Intercepts chunk NBT loading to provide CIS-backed chunk data.
     * <p>
     * Instead of loading from vanilla .mca files, this method loads the
     * delta from CIS storage and creates synthetic NBT for chunk deserialization.
     *
     * @param pos the chunk position to load
     * @param cir callback containing the result future
     */
    @SuppressWarnings("unchecked")
    @Inject(method = "getUpdatedChunkNbt(Lnet/minecraft/util/math/ChunkPos;)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"), cancellable = true)
    private void chunkis$onGetUpdatedChunkNbt(
            ChunkPos pos,
            CallbackInfoReturnable<CompletableFuture<Optional<NbtCompound>>> cir) {

        var cisPos = toCisChunkPos(pos);
        ChunkDelta<BlockState, NbtCompound> delta = (ChunkDelta<BlockState, NbtCompound>) (Object) getStorage()
                .load(cisPos);
        NbtCompound nbt = createChunkNbt(pos, delta);

        cir.setReturnValue(CompletableFuture.completedFuture(Optional.of(nbt)));
    }

    /**
     * Intercepts chunk saving to use CIS storage instead of vanilla format.
     * <p>
     * This method:
     * <ol>
     * <li>Retrieves the chunk from the holder</li>
     * <li>Captures entities and block entities into the delta</li>
     * <li>Saves the delta to CIS storage if dirty</li>
     * </ol>
     *
     * @param chunkHolder the holder containing the chunk to save
     * @param cir         callback containing the save success result
     */
    @SuppressWarnings("unchecked")
    @Inject(method = "save(Lnet/minecraft/server/world/ChunkHolder;)Z", at = @At("HEAD"), cancellable = true)
    private void chunkis$onSave(
            ChunkHolder chunkHolder,
            CallbackInfoReturnable<Boolean> cir) {

        ChunkPos pos = chunkHolder.getPos();
        Chunk chunk = selectChunkForSaving(chunkHolder);

        // First try to get delta from the global tracker (widest availability)
        ChunkDelta<BlockState, NbtCompound> delta = (ChunkDelta<BlockState, NbtCompound>) (Object) GlobalChunkTracker
                .getDelta(pos);

        // Fallback to the chunk's own delta if tracker is missing it
        if (delta == null && chunk instanceof ChunkisDeltaDuck deltaDuck) {
            delta = (ChunkDelta<BlockState, NbtCompound>) (Object) deltaDuck.chunkis$getDelta();
        }

        if (delta == null) {
            return; // Let vanilla handle it
        }

        // Only capture from live chunk objects
        if (chunk instanceof WorldChunk worldChunk) {
            captureChunkData(worldChunk, (ChunkDelta<BlockState, NbtCompound>) (Object) delta);
        }

        if (delta.isDirty()) {
            LOGGER.info("Chunkis [DEBUG]: Persisting DIRTY delta for {} (Blocks: {})", pos,
                    delta.getBlockInstructions().size());
            persistDelta(pos, (ChunkDelta<BlockState, NbtCompound>) (Object) delta);
            GlobalChunkTracker.markSaved(pos);
        } else {
            LOGGER.info("Chunkis [DEBUG]: Skipping save for CLEAN delta at {}", pos);
        }

        if (chunk != null) {
            chunk.setNeedsSaving(false);
        }
        cir.setReturnValue(true);
    }

    /**
     * Selects the most appropriate chunk instance for saving using priority logic.
     */
    @Unique
    private Chunk selectChunkForSaving(ChunkHolder chunkHolder) {
        // Active world chunk
        Chunk chunk = chunkHolder.getWorldChunk();
        if (chunk != null) {
            return chunk;
        }

        // Future chunk from async loading
        return extractChunkFromSavingFuture(chunkHolder);
    }

    /**
     * Extracts chunk from the saving future if available.
     *
     * @param holder the chunk holder
     * @return the chunk from the future, or {@code null}
     */
    @Unique
    private Chunk extractChunkFromSavingFuture(ChunkHolder holder) {
        var future = holder.getSavingFuture();
        Object result = future.getNow(null);

        if (result instanceof Optional<?> optionalChunk) {
            return (Chunk) optionalChunk.orElse(null);
        }

        return null;
    }

    /**
     * Captures block entities and entities from a WorldChunk into its delta.
     */
    @Unique
    private void captureChunkData(WorldChunk worldChunk, ChunkDelta<BlockState, NbtCompound> delta) {
        if (delta == null) {
            return;
        }

        try {
            ChunkBlockEntityCapture.captureAll(worldChunk, delta);
            ChunkEntityCapture.captureAll(worldChunk, delta, world);
        } catch (Exception error) {
            io.liparakis.chunkis.Chunkis.LOGGER.error("Failed to capture data for chunk {}", worldChunk.getPos(),
                    error);
        }
    }

    /**
     * Determines if a delta should be saved to storage.
     *
     * @param delta the delta to check
     * @return {@code true} if the delta exists and is dirty
     */
    @Unique
    private boolean shouldSaveDelta(ChunkDelta<BlockState, NbtCompound> delta) {
        return delta != null && delta.isDirty();
    }

    /**
     * Saves a delta to CIS storage and logs the operation.
     *
     * @param chunkPos the chunk position
     * @param delta    the delta to save
     */
    @Unique
    private void persistDelta(ChunkPos chunkPos, ChunkDelta<BlockState, NbtCompound> delta) {
        var cisPos = toCisChunkPos(chunkPos);
        getStorage().save(cisPos, delta);
        logDeltaSave(chunkPos);
    }

    /**
     * Logs delta save operation at debug level.
     *
     * @param pos the chunk position that was saved
     */
    @Unique
    private void logDeltaSave(ChunkPos pos) {
        if (io.liparakis.chunkis.Chunkis.LOGGER.isDebugEnabled()) {
            io.liparakis.chunkis.Chunkis.LOGGER.debug("Saved CIS chunk {}", pos);
        }
    }

    /**
     * Converts a Minecraft ChunkPos to a CIS ChunkPos.
     *
     * @param pos the Minecraft chunk position
     * @return the CIS chunk position
     */
    @Unique
    private CisChunkPos toCisChunkPos(ChunkPos pos) {
        return new CisChunkPos(pos.x, pos.z);
    }

    /**
     * Lazily retrieves the CIS storage instance for this world.
     * <p>
     * Thread-safe as all access occurs on the server thread.
     *
     * @return the active CIS storage instance
     */
    @Unique
    private CisStorage<Block, BlockState, Property<?>, NbtCompound> getStorage() {
        return FabricCisStorageHelper.getStorage(world);
    }

    /**
     * Creates minimal NBT structure for CIS chunk loading.
     * <p>
     * Embeds the delta data into a base NBT compound with the current
     * game data version for compatibility.
     *
     * @param pos   the chunk position
     * @param delta the chunk delta (may be null)
     * @return the NBT compound for chunk deserialization
     */
    @Unique
    private NbtCompound createChunkNbt(ChunkPos pos, ChunkDelta<BlockState, NbtCompound> delta) {
        NbtCompound nbt = CisNbtUtil.createBaseNbt(pos, GAME_DATA_VERSION);
        CisNbtUtil.putDelta(nbt, delta);
        return nbt;
    }
}