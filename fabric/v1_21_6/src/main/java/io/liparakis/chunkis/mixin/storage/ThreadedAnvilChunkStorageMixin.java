package io.liparakis.chunkis.mixin.storage;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.storage.CisStorage;
import io.liparakis.chunkis.util.ChunkBlockEntityCapture;
import io.liparakis.chunkis.util.ChunkEntityCapture;
import io.liparakis.chunkis.util.CisNbtUtil;
import io.liparakis.chunkis.util.FabricCisStorageHelper;
import io.liparakis.chunkis.util.GlobalChunkTracker;
import com.mojang.datafixers.DataFixer;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.WorldGenerationProgressListener;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.OptionalChunk;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureTemplateManager;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.thread.ThreadExecutor;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkProvider;
import net.minecraft.world.chunk.ChunkStatusChangeListener;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.level.storage.LevelStorage;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Mixin for {@link ServerChunkLoadingManager} to integrate Chunkis delta
 * storage.
 *
 * <p>
 * Intercepts Minecraft's chunk loading and saving pipeline to use the CIS
 * (Chunk Information Storage) format instead of vanilla NBT. This enables:
 * <ul>
 * <li>Delta-based chunk modifications (storing only player changes)</li>
 * <li>Efficient block entity and entity capture during save</li>
 * <li>Integration with {@link GlobalChunkTracker} for reliable dirty
 * tracking</li>
 * <li>Thread-safe entity capture with proactive pre-capture (v1.21.6+)</li>
 * </ul>
 *
 * <p>
 * <b>Thread Safety (v1.21.6):</b> Save operations may execute on background
 * threads.
 * To prevent entity data loss, this mixin now prioritizes pre-captured entity
 * data
 * from the WorldChunk unload hook and NEVER queries chunk entities from
 * background
 * threads.
 *
 * <p>
 * <b>Performance:</b> Uses lazy initialization for CisStorage and creates
 * NBT wrappers only when deltas exist, minimizing allocation overhead.
 *
 * @author Liparakis
 * @version 2.1
 */
@Mixin(ServerChunkLoadingManager.class)
public abstract class ThreadedAnvilChunkStorageMixin {

    @Unique
    private static final Logger LOGGER = Chunkis.LOGGER;

    /**
     * Cached game data version to avoid repeated version lookups during NBT
     * creation.
     */
    @Unique
    private static final int GAME_DATA_VERSION = SharedConstants.getGameVersion().dataVersion().id();

    /**
     * The server world instance for this chunk loading manager.
     * Captured during construction for use in storage operations.
     */
    @Unique
    private ServerWorld chunkis$world;

    /**
     * Captures the ServerWorld instance during chunk manager construction.
     *
     * <p>
     * This world reference is used for all subsequent CisStorage operations
     * and entity/block entity captures during chunk saves.
     *
     * @param world                           the server world being managed
     * @param session                         the level storage session
     * @param dataFixer                       the data fixer for version migration
     * @param structureTemplateManager        structure template manager
     * @param executor                        async task executor
     * @param mainThreadExecutor              server thread executor
     * @param chunkProvider                   chunk data provider
     * @param chunkGenerator                  world generator
     * @param worldGenerationProgressListener progress listener
     * @param chunkStatusChangeListener       status change listener
     * @param persistenceStateManagerFactory  persistence manager factory
     * @param chunkTicketManager              ticket manager
     * @param viewDistance                    server view distance
     * @param syncChunkWrites                 whether to sync chunk writes
     * @param ci                              callback info
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void chunkis$onInit(
            ServerWorld world,
            LevelStorage.Session session,
            DataFixer dataFixer,
            StructureTemplateManager structureTemplateManager,
            Executor executor,
            ThreadExecutor<?> mainThreadExecutor,
            ChunkProvider chunkProvider,
            ChunkGenerator chunkGenerator,
            WorldGenerationProgressListener worldGenerationProgressListener,
            ChunkStatusChangeListener chunkStatusChangeListener,
            Supplier<?> persistenceStateManagerFactory,
            net.minecraft.server.world.ChunkTicketManager chunkTicketManager,
            int viewDistance,
            boolean syncChunkWrites,
            CallbackInfo ci) {
        this.chunkis$world = world;
    }

    /**
     * Ensures CisStorage is properly closed during server shutdown.
     *
     * <p>
     * Flushes any pending writes and releases file handles to prevent
     * data corruption or resource leaks.
     *
     * @param ci callback info
     */
    @Inject(method = "close", at = @At("HEAD"))
    private void chunkis$onClose(CallbackInfo ci) {
        FabricCisStorageHelper.closeStorage(chunkis$world);
    }

    /**
     * Intercepts chunk NBT loading to provide CIS-stored chunk data.
     *
     * <p>
     * Loads the delta from CisStorage and wraps it in minimal NBT structure.
     * Only creates NBT wrapper if delta exists, avoiding unnecessary allocations
     * for unmodified chunks.
     *
     * @param position the chunk position to load
     * @param cir      callback containing the CompletableFuture of Optional NBT
     */
    @SuppressWarnings("unchecked")
    @Inject(method = "getUpdatedChunkNbt(Lnet/minecraft/util/math/ChunkPos;)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"), cancellable = true)
    private void chunkis$onGetUpdatedChunkNbt(
            ChunkPos position,
            CallbackInfoReturnable<CompletableFuture<Optional<NbtCompound>>> cir) {

        var cisPos = new CisChunkPos(position.x, position.z);
        ChunkDelta<BlockState, NbtCompound> delta = (ChunkDelta<BlockState, NbtCompound>) (Object) getStorage()
                .load(cisPos);
        NbtCompound nbt = createChunkNbt(position, delta);

        cir.setReturnValue(CompletableFuture.completedFuture(Optional.of(nbt)));
    }

    /**
     * Intercepts chunk saving to use CIS storage instead of vanilla NBT format.
     *
     * <p>
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
     * @param currentTime the current game time (for autosave scheduling)
     * @param cir         callback containing the save success result
     */
    @Inject(method = "save(Lnet/minecraft/server/world/ChunkHolder;J)Z", at = @At("HEAD"), cancellable = true)
    private void chunkis$onSave(
            ChunkHolder chunkHolder,
            long currentTime,
            CallbackInfoReturnable<Boolean> cir) {

        ChunkPos pos = chunkHolder.getPos();
        Chunk chunk = retrieveChunkFromHolder(chunkHolder);

        // First try to get delta from the global tracker (widest availability)
        ChunkDelta<BlockState, NbtCompound> delta = (ChunkDelta<BlockState, NbtCompound>) (Object) GlobalChunkTracker
                .getDelta(pos);

        // Fallback to the chunk's own delta if tracker is missing it
        if (delta == null && chunk instanceof ChunkisDeltaDuck deltaDuck) {
            delta = deltaDuck.chunkis$getDelta();
        }

        if (delta == null) {
            return; // Let vanilla handle it
        }

        // Only capture from live chunk objects
        if (chunk instanceof WorldChunk worldChunk) {
            captureChunkData(worldChunk, delta);
        }

        if (delta.isDirty()) {
            LOGGER.info("Chunkis [DEBUG]: Persisting DIRTY delta for {} (Blocks: {})", pos,
                    delta.getBlockInstructions().size());
            persistDelta(pos, delta);
            GlobalChunkTracker.markSaved(pos);
        } else {
            LOGGER.info("Chunkis [DEBUG]: Skipping save for CLEAN delta at {}", pos);
        }

        // We ensure vanilla doesn't try to save it again by returning true.
        cir.setReturnValue(true);
    }

    /**
     * Retrieves the chunk from the holder.
     *
     * @param holder the chunk holder
     * @return the chunk, or {@code null} if not available
     */
    @Unique
    private Chunk retrieveChunkFromHolder(ChunkHolder holder) {
        // Active world chunk
        Chunk chunk = holder.getWorldChunk();
        if (chunk != null) {
            return chunk;
        }

        // Future chunk from async loading
        return extractChunkFromSavingFuture(holder);
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

        if (result instanceof OptionalChunk<?> optionalChunk) {
            return (Chunk) optionalChunk.orElse(null);
        }

        return null;
    }

    /**
     * Captures live entity and block entity data into the delta.
     * <p>
     * Only executes for WorldChunks with valid deltas, as these contain
     * the active entity data.
     *
     * @param worldChunk the world chunk to capture from
     * @param delta      the delta to capture into (may be null)
     */
    @Unique
    private void captureChunkData(WorldChunk worldChunk,
            ChunkDelta<BlockState, NbtCompound> delta) {
        if (delta == null) {
            return;
        }

        try {
            ChunkBlockEntityCapture.captureAll(worldChunk, delta);
            ChunkEntityCapture.captureAll(worldChunk, delta, chunkis$world);
        } catch (Exception error) {
            LOGGER.error("Failed to capture data for chunk {}", worldChunk.getPos(),
                    error);
        }
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
     *
     * @return the active CIS storage instance
     */
    @Unique
    @SuppressWarnings("rawtypes")
    private CisStorage getStorage() {
        return FabricCisStorageHelper.getStorage(chunkis$world);
    }

    /**
     * Creates minimal NBT structure for CIS chunk loading.
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