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
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.WorldGenerationProgressListener;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ChunkTicketManager;
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
 * Mixin for {@link ServerChunkLoadingManager} to integrate Chunkis delta storage.
 *
 * <p>Intercepts Minecraft's chunk loading and saving pipeline to use the CIS
 * (Chunk Information Storage) format instead of vanilla NBT. This enables:
 * <ul>
 *   <li>Delta-based chunk modifications (storing only player changes)</li>
 *   <li>Efficient block entity and entity capture during save</li>
 *   <li>Integration with {@link GlobalChunkTracker} for reliable dirty tracking</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> All injection points execute on the server thread,
 * ensuring single-threaded access to CisStorage and chunk data structures.
 *
 * <p><b>Performance:</b> Uses lazy initialization for CisStorage and creates
 * NBT wrappers only when deltas exist, minimizing allocation overhead.
 *
 * @author Liparakis
 * @version 2.0
 */
@Mixin(ServerChunkLoadingManager.class)
public abstract class ThreadedAnvilChunkStorageMixin {

    @Unique
    private static final Logger LOGGER = Chunkis.LOGGER;

    /**
     * Cached game data version to avoid repeated version lookups during NBT creation.
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
     * <p>This world reference is used for all subsequent CisStorage operations
     * and entity/block entity captures during chunk saves.
     *
     * @param world                            the server world being managed
     * @param session                          the level storage session
     * @param dataFixer                        the data fixer for version migration
     * @param structureTemplateManager         structure template manager
     * @param executor                         async task executor
     * @param mainThreadExecutor               server thread executor
     * @param chunkProvider                    chunk data provider
     * @param chunkGenerator                   world generator
     * @param worldGenerationProgressListener  progress listener
     * @param chunkStatusChangeListener        status change listener
     * @param persistenceStateManagerFactory   persistence manager factory
     * @param chunkTicketManager               ticket manager
     * @param viewDistance                     server view distance
     * @param syncChunkWrites                  whether to sync chunk writes
     * @param ci                               callback info
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
            ChunkTicketManager chunkTicketManager,
            int viewDistance,
            boolean syncChunkWrites,
            CallbackInfo ci) {
        this.chunkis$world = world;
    }

    /**
     * Ensures CisStorage is properly closed during server shutdown.
     *
     * <p>Flushes any pending writes and releases file handles to prevent
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
     * <p>Loads the delta from CisStorage and wraps it in minimal NBT structure.
     * Only creates NBT wrapper if delta exists, avoiding unnecessary allocations
     * for unmodified chunks.
     *
     * @param position the chunk position to load
     * @param cir      callback containing the CompletableFuture of Optional NBT
     */
    @Inject(
            method = "getUpdatedChunkNbt(Lnet/minecraft/util/math/ChunkPos;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void chunkis$onGetUpdatedChunkNbt(
            ChunkPos position,
            CallbackInfoReturnable<CompletableFuture<Optional<NbtCompound>>> cir) {

        var cisPosition = new CisChunkPos(position.x, position.z);
        var delta = getStorage().load(cisPosition);
        var nbt = createChunkNbt(position, delta);

        cir.setReturnValue(CompletableFuture.completedFuture(Optional.of(nbt)));
    }

    /**
     * Intercepts chunk saving to use CIS storage instead of vanilla NBT format.
     *
     * <p>Implements a priority-based chunk selection strategy:
     * <ol>
     *   <li>GlobalChunkTracker (most recent in-memory state)</li>
     *   <li>ChunkHolder's world chunk (active loaded chunk)</li>
     *   <li>ChunkHolder's future chunk (async loading result)</li>
     * </ol>
     *
     * <p>For WorldChunks, captures block entities and entities before saving.
     * Only persists if the delta is dirty (has unsaved modifications).
     *
     * @param chunkHolder the chunk holder managing this chunk
     * @param currentTime the current world time
     * @param cir         callback containing save result
     */
    @Inject(
            method = "save(Lnet/minecraft/server/world/ChunkHolder;J)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private void chunkis$onSave(
            ChunkHolder chunkHolder,
            long currentTime,
            CallbackInfoReturnable<Boolean> cir) {

        var chunk = selectChunkForSaving(chunkHolder);

        if (!(chunk instanceof ChunkisDeltaDuck deltaProvider)) {
            return; // Let vanilla handle non-CIS chunks
        }

        var delta = deltaProvider.chunkis$getDelta();
        if (delta == null) {
            return;
        }

        captureChunkData(chunk, delta);

        if (delta.isDirty()) {
            persistDelta(chunk.getPos(), delta);
        }

        cir.setReturnValue(true);
    }

    /**
     * Selects the most appropriate chunk instance for saving using priority logic.
     *
     * <p>Priority order:
     * <ol>
     *   <li>GlobalChunkTracker entry (prevents race conditions)</li>
     *   <li>Active world chunk from ChunkHolder</li>
     *   <li>Future chunk from async loading</li>
     * </ol>
     *
     * @param chunkHolder the chunk holder
     * @return the chunk to save, or null if none available
     */
    @Unique
    private Chunk selectChunkForSaving(ChunkHolder chunkHolder) {
        var position = chunkHolder.getPos();

        // Priority 1: GlobalChunkTracker (most recent state)
        var trackedChunk = GlobalChunkTracker.getDirtyChunk(position);
        if (trackedChunk != null) {
            return trackedChunk;
        }

        // Priority 2: Active world chunk
        var worldChunk = chunkHolder.getWorldChunk();
        if (worldChunk != null) {
            return worldChunk;
        }

        // Priority 3: Future chunk from async loading
        var savingFuture = chunkHolder.getSavingFuture();
        var optionalChunk = (OptionalChunk<Chunk>) savingFuture.getNow(null);
        return (optionalChunk != null) ? optionalChunk.orElse(null) : null;
    }

    /**
     * Captures block entities and entities from a WorldChunk into its delta.
     *
     * <p>Only performs capture for active WorldChunks where entities are loaded.
     * Skips capture for ProtoChunks and other chunk types to avoid errors.
     *
     * @param chunk the chunk to capture from
     * @param delta the delta to capture into
     */
    @Unique
    @SuppressWarnings("rawtypes")
    private void captureChunkData(Chunk chunk, ChunkDelta delta) {
        if (!(chunk instanceof WorldChunk worldChunk)) {
            return; // Only capture from fully loaded chunks
        }

        try {
            ChunkBlockEntityCapture.captureAll(worldChunk, delta);
            ChunkEntityCapture.captureAll(worldChunk, delta, chunkis$world);
        } catch (Exception error) {
            LOGGER.error("Failed to capture data for chunk {}", chunk.getPos(), error);
        }
    }

    /**
     * Persists a delta to CIS storage.
     *
     * @param position the chunk position
     * @param delta    the delta to persist
     */
    @Unique
    @SuppressWarnings("rawtypes")
    private void persistDelta(ChunkPos position, ChunkDelta delta) {
        var cisPosition = new CisChunkPos(position.x, position.z);
        getStorage().save(cisPosition, delta);
    }

    /**
     * Gets or lazily initializes the CisStorage instance for this world.
     *
     * <p>Thread-safe due to single-threaded server context. The storage instance
     * is cached by FabricCisStorageHelper per world.
     *
     * @return the CIS storage manager
     */
    @Unique
    @SuppressWarnings("rawtypes")
    private CisStorage getStorage() {
        return FabricCisStorageHelper.getStorage(chunkis$world);
    }

    /**
     * Creates minimal NBT structure for CIS chunk loading compatibility.
     *
     * <p>Wraps the delta in a vanilla-compatible NBT format that Minecraft's
     * chunk loader can deserialize. Uses cached game version to avoid lookups.
     *
     * @param position the chunk position
     * @param delta    the delta to serialize (may be null or empty)
     * @return NBT compound with base chunk structure and delta data
     */
    @Unique
    @SuppressWarnings("rawtypes")
    private NbtCompound createChunkNbt(ChunkPos position, ChunkDelta delta) {
        var nbt = CisNbtUtil.createBaseNbt(position, GAME_DATA_VERSION);
        CisNbtUtil.putDelta(nbt, delta);
        return nbt;
    }
}