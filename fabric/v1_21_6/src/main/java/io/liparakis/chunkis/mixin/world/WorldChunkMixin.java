package io.liparakis.chunkis.mixin.world;

import io.liparakis.chunkis.Chunkis;
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
import org.slf4j.Logger;

/**
 * Mixin for {@link WorldChunk} implementing player modification tracking and
 * restoration.
 *
 * <p>
 * Provides the core functionality for Chunkis delta-based chunk modification
 * system:
 * <ul>
 * <li>Intercepts block changes to track player modifications in
 * {@link ChunkDelta}</li>
 * <li>Restores saved modifications when chunks are promoted from
 * ProtoChunk</li>
 * <li>Uses {@link VanillaChunkSnapshot} to distinguish player changes from
 * worldgen</li>
 * <li>Integrates with {@link GlobalChunkTracker} for reliable persistence</li>
 * </ul>
 *
 * <p>
 * <b>Thread Safety:</b> All block change tracking is restricted to the server
 * thread
 * to prevent concurrent modification issues. Natural processes (leaf decay) are
 * filtered
 * to avoid polluting the delta with non-player changes.
 *
 * <p>
 * <b>Note:</b> This mixin relies on {@link CommonChunkMixin} applying the
 * {@link ChunkisDeltaDuck} interface to the base chunk class.
 *
 * @author Liparakis
 * @version 1.0
 */
@Mixin(WorldChunk.class)
public class WorldChunkMixin {

    @Unique
    private static final Logger LOGGER = Chunkis.LOGGER;

    /**
     * Immutable snapshot of the chunk's vanilla worldgen state.
     * Used to filter player modifications from generated terrain.
     */
    @Unique
    private VanillaChunkSnapshot chunkis$vanillaSnapshot;

    /**
     * Re-entrancy guard to prevent tracking blocks set during restoration.
     */
    @Unique
    private boolean chunkis$isRestoring = false;

    /**
     * Retrieves the chunk's delta via the ChunkisDeltaDuck interface.
     *
     * @return the chunk's delta instance
     */
    @Unique
    private ChunkDelta chunkis$getDelta() {
        return ((ChunkisDeltaDuck) this).chunkis$getDelta();
    }

    /**
     * Intercepts block state changes to track player modifications.
     *
     * <p>
     * This method implements sophisticated filtering to ensure only genuine player
     * modifications are tracked:
     * <ul>
     * <li>Ignores client-side changes (no delta on client)</li>
     * <li>Ignores changes during restoration (prevents recursion)</li>
     * <li>Ignores changes before chunk is fully generated (FULL status
     * required)</li>
     * <li>Ignores natural leaf decay (tracked via LeafTickContext)</li>
     * <li>Ignores changes from non-server threads (thread safety)</li>
     * </ul>
     *
     * <p>
     * When a block is reverted to its vanilla state, the delta entry is removed.
     * When a block differs from vanilla, it's recorded in the delta and the chunk
     * is marked dirty in GlobalChunkTracker.
     *
     * @param position the block position being changed
     * @param newState the new block state
     * @param flags    the block update flags
     * @param cir      callback info (unused)
     */
    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void chunkis$onSetBlockState(
            BlockPos position,
            BlockState newState,
            int flags,
            CallbackInfoReturnable<BlockState> cir) {

        var chunk = (WorldChunk) (Object) this;

        if (!shouldTrackBlockChange(chunk, position, newState)) {
            return;
        }

        trackBlockChange(chunk, position, newState);
    }

    /**
     * Determines whether a block change should be tracked in the delta.
     *
     * <p>
     * Applies multiple filters to exclude non-player or inappropriate changes.
     *
     * @param chunk    the chunk being modified
     * @param position the block position
     * @param newState the new block state
     * @return true if this change should be tracked, false otherwise
     */
    @Unique
    private boolean shouldTrackBlockChange(WorldChunk chunk, BlockPos position, BlockState newState) {
        // Filter: Client-side changes
        if (chunk.getWorld().isClient()) {
            return false;
        }

        // Filter: Restoration in progress (prevents recursion)
        if (chunkis$isRestoring) {
            LOGGER.debug("Skipping block capture at {} during restoration", position);
            return false;
        }

        // Filter: Chunk not fully generated
        if (!ChunkStatus.FULL.equals(chunk.getStatus())) {
            LOGGER.debug("Skipping block capture at {} due to status: {}", position, chunk.getStatus());
            return false;
        }

        // Filter: No vanilla snapshot (shouldn't happen)
        if (chunkis$vanillaSnapshot == null) {
            LOGGER.error("Skipping block capture at {} due to missing snapshot", position);
            return false;
        }

        // Filter: Natural leaf decay
        if (isNaturalLeafDecay(newState)) {
            return false;
        }

        // Filter: Wrong thread (thread safety)
        return isServerThread(chunk);
    }

    /**
     * Checks if a state change represents natural leaf decay.
     *
     * @param state the block state being placed
     * @return true if this is natural decay, false otherwise
     */
    @Unique
    private boolean isNaturalLeafDecay(BlockState state) {
        return state.getBlock() instanceof LeavesBlock && LeafTickContext.isActive();
    }

    /**
     * Verifies the current thread is the server thread.
     *
     * @param chunk the chunk being modified
     * @return true if on server thread, false otherwise
     */
    @Unique
    private boolean isServerThread(WorldChunk chunk) {
        if (chunk.getWorld() instanceof ServerWorld serverWorld) {
            return serverWorld.getServer().getThread() == Thread.currentThread();
        }
        return false;
    }

    /**
     * Records a block change in the chunk delta.
     *
     * <p>
     * Compares the new state against the vanilla snapshot:
     * <ul>
     * <li>If matching vanilla: removes delta entry (reversion)</li>
     * <li>If different from vanilla: adds/updates delta entry</li>
     * </ul>
     *
     * @param chunk    the chunk being modified
     * @param position the block position
     * @param newState the new block state
     */
    @Unique
    private void trackBlockChange(WorldChunk chunk, BlockPos position, BlockState newState) {
        int localX = position.getX() & CisConstants.COORD_MASK;
        int localY = position.getY();
        int localZ = position.getZ() & CisConstants.COORD_MASK;

        BlockState vanillaState = chunkis$vanillaSnapshot.getVanillaState(localX, localY, localZ);
        ChunkDelta delta = chunkis$getDelta();

        // Block reverted to vanilla: remove from delta
        if (vanillaState != null && vanillaState.equals(newState)) {
            handleBlockReversion(delta, localX, localY, localZ);
            return;
        }

        // Block differs from vanilla: record in delta
        handleBlockModification(chunk, delta, localX, localY, localZ, newState);
    }

    /**
     * Handles a block being reverted to its vanilla state.
     *
     * @param delta  the chunk delta
     * @param localX local X coordinate (0-15)
     * @param localY absolute Y coordinate
     * @param localZ local Z coordinate (0-15)
     */
    @Unique
    private void handleBlockReversion(ChunkDelta delta, int localX, int localY, int localZ) {
        if (delta.hasBlockChange(localX, localY, localZ)) {
            delta.removeBlockChange(localX, localY, localZ);
        }
    }

    /**
     * Handles a block modification that differs from vanilla.
     *
     * @param chunk    the chunk being modified
     * @param delta    the chunk delta
     * @param localX   local X coordinate (0-15)
     * @param localY   absolute Y coordinate
     * @param localZ   local Z coordinate (0-15)
     * @param newState the new block state
     */
    @Unique
    private void handleBlockModification(
            WorldChunk chunk,
            ChunkDelta delta,
            int localX,
            int localY,
            int localZ,
            BlockState newState) {

        delta.addBlockChange(localX, localY, localZ, newState);
        GlobalChunkTracker.markDirty(chunk);
    }

    /**
     * Intercepts WorldChunk construction from ProtoChunk to restore saved
     * modifications.
     *
     * <p>
     * Executes the following restoration workflow:
     * <ol>
     * <li>Captures a vanilla snapshot from the ProtoChunk (worldgen state)</li>
     * <li>Retrieves the delta from the ProtoChunk (loaded from disk/memory)</li>
     * <li>Applies all delta modifications to the WorldChunk</li>
     * <li>Optimizes the delta by removing redundant entries</li>
     * <li>Registers the chunk with GlobalChunkTracker for dirty tracking</li>
     * </ol>
     *
     * <p>
     * The re-entrancy guard {@link #chunkis$isRestoring} prevents modifications
     * made during restoration from being tracked as player changes.
     *
     * @param world        the server world context
     * @param protoChunk   the source ProtoChunk containing worldgen state
     * @param entityLoader the entity loader (unused)
     * @param ci           callback info
     */
    @Inject(method = "<init>(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/world/chunk/ProtoChunk;Lnet/minecraft/world/chunk/WorldChunk$EntityLoader;)V", at = @At("RETURN"))
    private void chunkis$onConstructFromProto(
            ServerWorld world,
            ProtoChunk protoChunk,
            WorldChunk.EntityLoader entityLoader,
            CallbackInfo ci) {

        var chunk = (WorldChunk) (Object) this;

        // Always capture vanilla snapshot
        chunkis$vanillaSnapshot = new VanillaChunkSnapshot(protoChunk);

        // Attempt restoration if delta exists
        if (protoChunk instanceof ChunkisDeltaDuck deltaProvider) {
            restoreDeltaFromProto(world, chunk, deltaProvider);
        }
    }

    /**
     * Restores delta modifications from a ProtoChunk to a WorldChunk.
     *
     * @param world         the server world
     * @param chunk         the target WorldChunk
     * @param deltaProvider the ProtoChunk with delta interface
     */
    @Unique
    private void restoreDeltaFromProto(ServerWorld world, WorldChunk chunk, ChunkisDeltaDuck deltaProvider) {
        ChunkDelta protoDelta = deltaProvider.chunkis$getDelta();

        if (protoDelta.isEmpty()) {
            return;
        }

        ChunkDelta chunkDelta = chunkis$getDelta();

        try {
            chunkis$isRestoring = true;

            boolean wasOptimized = ChunkRestorer.restore(
                    world,
                    chunk,
                    protoDelta,
                    chunkDelta,
                    chunkis$vanillaSnapshot);

            if (wasOptimized) {
                chunkDelta.markDirty();
            }

            protoDelta.markSaved();

            // Register immediately after restore to ensure tracking
            GlobalChunkTracker.markDirty(chunk);

        } catch (Exception e) {
            LOGGER.error("Failed to restore chunk {}", chunk.getPos(), e);
        } finally {
            chunkis$isRestoring = false;
        }
    }
}