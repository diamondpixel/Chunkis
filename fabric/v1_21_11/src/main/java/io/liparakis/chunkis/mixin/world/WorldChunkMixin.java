package io.liparakis.chunkis.mixin.world;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.util.LeafTickContext;
import io.liparakis.chunkis.util.VanillaChunkSnapshot;
import io.liparakis.chunkis.storage.CisConstants;
import net.minecraft.block.BlockState;
import net.minecraft.block.LeavesBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin for {@link WorldChunk} that implements player modification tracking and
 * restoration.
 * <p>
 * Core logic:
 * 1. Intercepts block changes to track modifications in {@link ChunkDelta}
 * 2. Restores modifications when a chunk is promoted from ProtoChunk
 * 3. Uses {@link VanillaChunkSnapshot} to optimize filtering
 * </p>
 *
 * This mixin no longer implements `ChunkisDeltaDuck` directly; it relies on
 * {@link CommonChunkMixin} being applied to the base
 * {@link net.minecraft.world.chunk.Chunk}.
 *
 * @author Liparakis
 * @version 1.0
 */
@Mixin(WorldChunk.class)
public class WorldChunkMixin {

    /**
     * Immutable snapshot of the chunk's vanilla/generated state.
     */
    @Unique
    private VanillaChunkSnapshot chunkis$vanillaSnapshot;

    /**
     * Flag indicating whether restoration is currently in progress.
     */
    @Unique
    private boolean chunkis$isRestoring = false;

    /**
     * Helper to get delta from the chunk (via duck interface).
     */
    @Unique
    private ChunkDelta chunkis$getDelta() {
        return ((ChunkisDeltaDuck) this).chunkis$getDelta();
    }

    /**
     * Intercepts block state changes to track player modifications.
     */
    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void chunkis$onSetBlockState(
            BlockPos pos,
            BlockState state,
            int flags,
            CallbackInfoReturnable<BlockState> cir) {

        WorldChunk self = (WorldChunk) (Object) this;

        // Early exit: client-side changes
        if (self.getWorld().isClient()) {
            return;
        }

        // Early exit: restoration in progress
        if (chunkis$isRestoring) {
            return;
        }

        // Early exit: chunk not fully generated
        if (!ChunkStatus.FULL.equals(self.getStatus())) {
            return;
        }

        // Early exit: no vanilla snapshot available
        if (chunkis$vanillaSnapshot == null) {
            return;
        }

        // Early exit: leaf natural decay
        if (state.getBlock() instanceof LeavesBlock && LeafTickContext.isActive()) {
            return;
        }

        // Early exit: wrong thread
        if (self.getWorld() instanceof ServerWorld serverWorld) {
            if (serverWorld.getServer().getThread() != Thread.currentThread()) return;
        }

        int localX = pos.getX() & CisConstants.COORD_MASK;
        int localY = pos.getY();
        int localZ = pos.getZ() & CisConstants.COORD_MASK;

        BlockState vanillaState = chunkis$vanillaSnapshot.getVanillaState(localX, localY, localZ);
        ChunkDelta delta = chunkis$getDelta();

        // Block reverted to vanilla: remove delta entry
        if (vanillaState != null && vanillaState.equals(state)) {
            delta.removeBlockChange(localX, localY, localZ);
            return;
        }

        // Block differs from vanilla: record change
        delta.addBlockChange(localX, localY, localZ, state);
    }

    /**
     * Intercepts WorldChunk construction from ProtoChunk to restore saved
     * modifications.
     */
    @Inject(method = "<init>(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/world/chunk/ProtoChunk;Lnet/minecraft/world/chunk/WorldChunk$EntityLoader;)V", at = @At("RETURN"))
    private void chunkis$onConstructFromProto(
            ServerWorld world,
            ProtoChunk proto,
            WorldChunk.EntityLoader entityLoader,
            CallbackInfo ci) {

        WorldChunk self = (WorldChunk) (Object) this;

        // Capture vanilla snapshot before restoration
        chunkis$vanillaSnapshot = new VanillaChunkSnapshot(proto);

        // Early exit: proto has no delta interface
        if (!(proto instanceof ChunkisDeltaDuck duck)) {
            return;
        }

        ChunkDelta protoDelta = duck.chunkis$getDelta();

        // Early exit: nothing to restore
        if (protoDelta.isEmpty()) {
            return;
        }

        ChunkDelta selfDelta = chunkis$getDelta();

        try {
            chunkis$isRestoring = true;
            boolean wasOptimized = io.liparakis.chunkis.util.ChunkRestorer.restore(
                    world,
                    self,
                    protoDelta,
                    selfDelta,
                    chunkis$vanillaSnapshot);

            if (wasOptimized) {
                selfDelta.markDirty();
            }

        } catch (Exception e) {
            io.liparakis.chunkis.Chunkis.LOGGER.error(
                    "Chunkis: Failed to restore chunk {}",
                    proto.getPos(),
                    e);
        } finally {
            chunkis$isRestoring = false;
        }

        protoDelta.markSaved();
    }
}
