package io.liparakis.chunkis.mixin.world;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.storage.CisConstants;
import io.liparakis.chunkis.util.ChunkRestorer;
import io.liparakis.chunkis.util.GlobalChunkTracker;
import io.liparakis.chunkis.util.LeafTickContext;
import io.liparakis.chunkis.util.VanillaChunkSnapshot;
import net.minecraft.block.BlockState;
import net.minecraft.block.LeavesBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin for {@link WorldChunk} that implements player modification tracking
 * and restoration.
 * <p>
 * This mixin provides two core functionalities:
 * <ol>
 * <li>Tracks block changes made by players/non-generation systems</li>
 * <li>Restores previously saved modifications when chunks are loaded</li>
 * </ol>
 * <p>
 * The tracking system uses {@link VanillaChunkSnapshot} to differentiate
 * between generated blocks and player modifications, ensuring only actual
 * changes are persisted to the delta.
 * <p>
 * <b>Note:</b> This mixin relies on {@link CommonChunkMixin} being applied
 * to the base {@link net.minecraft.world.chunk.Chunk} class for delta storage.
 *
 * @author Liparakis
 * @version 1.0
 */
@Mixin(WorldChunk.class)
public class WorldChunkMixin {

    @Unique
    private VanillaChunkSnapshot chunkis$vanillaSnapshot;

    @Unique
    private volatile boolean chunkis$isRestoring = false;

    /**
     * Intercepts block state changes to track player modifications.
     * <p>
     * This method filters out generation-time changes, natural decay, and
     * cross-thread modifications, capturing only intentional player edits
     * to the delta.
     * <p>
     * <b>Performance Note:</b> This is on the hot path (called for every block
     * change).
     * Early exits minimize overhead for filtered cases.
     *
     * @param pos   the block position being modified
     * @param state the new block state
     * @param moved whether the block was moved
     * @param cir   callback containing the previous block state
     */
    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void chunkis$onSetBlockState(
            BlockPos pos,
            BlockState state,
            boolean moved,
            CallbackInfoReturnable<BlockState> cir) {

        WorldChunk self = getWorldChunk();

        if (!shouldTrackBlockChange(self, state)) {
            return;
        }

        updateDeltaForBlockChange(pos, state);
    }

    /**
     * Intercepts WorldChunk construction from ProtoChunk to restore saved
     * modifications.
     * <p>
     * When a chunk is promoted from ProtoChunk to WorldChunk (after generation
     * completes),
     * this method:
     * <ol>
     * <li>Captures a snapshot of the vanilla-generated state</li>
     * <li>Restores block changes from the delta</li>
     * <li>Optimizes the delta by removing redundant entries</li>
     * </ol>
     * <p>
     * The restoration flag prevents the restored blocks from being re-tracked as
     * new changes.
     *
     * @param world        the server world
     * @param proto        the ProtoChunk being promoted
     * @param entityLoader the entity loader for the chunk
     * @param ci           callback info
     */
    @Inject(method = "<init>(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/world/chunk/ProtoChunk;Lnet/minecraft/world/chunk/WorldChunk$EntityLoader;)V", at = @At("RETURN"))
    private void chunkis$onConstructFromProto(
            ServerWorld world,
            ProtoChunk proto,
            WorldChunk.EntityLoader entityLoader,
            CallbackInfo ci) {

        WorldChunk self = getWorldChunk();
        chunkis$vanillaSnapshot = new VanillaChunkSnapshot(proto);

        if (!(proto instanceof ChunkisDeltaDuck deltaDuck)) {
            return;
        }

        ChunkDelta protoDelta = deltaDuck.chunkis$getDelta();

        if (protoDelta == null || protoDelta.isEmpty()) {
            return;
        }

        restoreChunkFromDelta(world, self, proto, protoDelta);
    }

    /**
     * Determines if a block change should be tracked in the delta.
     * <p>
     * Block changes are ignored if:
     * <ul>
     * <li>The chunk is client-side</li>
     * <li>Restoration is in progress</li>
     * <li>The chunk is not fully generated</li>
     * <li>No vanilla snapshot exists</li>
     * <li>The change is from natural leaf decay</li>
     * <li>The change is from a different thread</li>
     * </ul>
     *
     * @param chunk the chunk being modified
     * @param state the new block state
     * @return {@code true} if the change should be tracked
     */
    @Unique
    private boolean shouldTrackBlockChange(WorldChunk chunk, BlockState state) {
        if (chunk.getWorld().isClient()) {
            return false;
        }

        if (chunkis$isRestoring) {
            return false;
        }

        if (!ChunkStatus.FULL.equals(chunk.getStatus())) {
            return false;
        }

        if (chunkis$vanillaSnapshot == null) {
            return false;
        }

        if (isNaturalLeafDecay(state)) {
            return false;
        }

        return isOnServerThread(chunk);
    }

    /**
     * Checks if a block change is from natural leaf decay.
     *
     * @param state the block state
     * @return {@code true} if this is natural leaf decay
     */
    @Unique
    private boolean isNaturalLeafDecay(BlockState state) {
        return state.getBlock() instanceof LeavesBlock && LeafTickContext.isActive();
    }

    /**
     * Verifies the current thread is the server thread.
     * <p>
     * Cross-thread block changes are ignored to prevent race conditions.
     *
     * @param chunk the chunk being modified
     * @return {@code true} if on the server thread
     */
    @Unique
    private boolean isOnServerThread(WorldChunk chunk) {
        if (!(chunk.getWorld() instanceof ServerWorld serverWorld)) {
            return false;
        }

        return serverWorld.getServer().getThread() == Thread.currentThread();
    }

    /**
     * Updates the delta to reflect a block change.
     * <p>
     * If the block matches vanilla state, the delta entry is removed.
     * Otherwise, the change is recorded.
     *
     * @param pos   the block position
     * @param state the new block state
     */
    @Unique
    private void updateDeltaForBlockChange(BlockPos pos, BlockState state) {
        int localX = pos.getX() & CisConstants.COORD_MASK;
        int localY = pos.getY();
        int localZ = pos.getZ() & CisConstants.COORD_MASK;

        BlockState vanillaState = chunkis$vanillaSnapshot.getVanillaState(localX, localY, localZ);
        ChunkDelta delta = getDelta();

        if (isRevertedToVanilla(vanillaState, state)) {
            delta.removeBlockChange(localX, localY, localZ);
        } else {
            delta.addBlockChange(localX, localY, localZ, state);
            GlobalChunkTracker.markDirty(getWorldChunk());
        }
    }

    /**
     * Checks if a block has been reverted to its vanilla state.
     *
     * @param vanillaState the original vanilla state
     * @param currentState the current state
     * @return {@code true} if the block matches vanilla
     */
    @Unique
    private boolean isRevertedToVanilla(BlockState vanillaState, BlockState currentState) {
        return vanillaState != null && vanillaState.equals(currentState);
    }

    /**
     * Restores chunk modifications from a delta.
     * <p>
     * Sets the restoration flag to prevent re-tracking of restored blocks,
     * then delegates to {@link ChunkRestorer} for the actual restoration logic.
     * <p>
     * If optimization occurs during restoration, the delta is marked dirty
     * to ensure it's re-saved with redundant entries removed.
     *
     * @param world      the server world
     * @param chunk      the chunk being restored
     * @param proto      the ProtoChunk source
     * @param protoDelta the delta to restore from
     */
    @Unique
    private void restoreChunkFromDelta(
            ServerWorld world,
            WorldChunk chunk,
            ProtoChunk proto,
            ChunkDelta protoDelta) {

        ChunkDelta selfDelta = getDelta();

        try {
            chunkis$isRestoring = true;

            boolean wasOptimized = ChunkRestorer.restore(
                    world,
                    chunk,
                    protoDelta,
                    selfDelta,
                    chunkis$vanillaSnapshot);

            if (wasOptimized) {
                selfDelta.markDirty();
            }

            GlobalChunkTracker.markDirty(chunk);

        } catch (Exception e) {
            logRestorationError(proto, e);
        } finally {
            chunkis$isRestoring = false;
        }

        protoDelta.markSaved();
    }

    /**
     * Logs chunk restoration errors.
     *
     * @param proto the ProtoChunk that failed to restore
     * @param e     the exception that occurred
     */
    @Unique
    private void logRestorationError(ProtoChunk proto, Exception e) {
        io.liparakis.chunkis.Chunkis.LOGGER.error(
                "Chunkis: Failed to restore chunk {}",
                proto.getPos(),
                e);
    }

    /**
     * Retrieves the delta for this chunk.
     *
     * @return the chunk delta
     */
    @Unique
    private ChunkDelta getDelta() {
        return ((ChunkisDeltaDuck) this).chunkis$getDelta();
    }

    /**
     * Casts this mixin instance to WorldChunk.
     *
     * @return this instance as WorldChunk
     */
    @Unique
    private WorldChunk getWorldChunk() {
        return (WorldChunk) (Object) this;
    }
}