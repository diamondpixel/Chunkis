package io.liparakis.chunkis.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.block.LeavesBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.liparakis.chunkis.core.LeafTickContext;

/**
 * Mixin for {@link LeavesBlock} to track when leaf decay or updates are
 * happening.
 * <p>
 * This is used by Chunkis to differentiate between player-initiated block
 * changes
 * and automated leaf-related changes (like decay), allowing the system to
 * potentially ignore or handle them differently during delta capture.
 */
@Mixin(LeavesBlock.class)
public class LeavesBlockMixin {
    /**
     * Injects at the start of a scheduled tick to indicate that a leaf-related
     * update is in progress.
     */
    @Inject(method = "scheduledTick", at = @At("HEAD"))
    private void chunkis$beforeLeafTick(BlockState state, ServerWorld world, BlockPos pos, Random random,
            CallbackInfo ci) {
        LeafTickContext.set(true);
    }

    /**
     * Injects at the end of a scheduled tick to reset the leaf update context.
     */
    @Inject(method = "scheduledTick", at = @At("RETURN"))
    private void chunkis$afterLeafTick(BlockState state, ServerWorld world, BlockPos pos, Random random,
            CallbackInfo ci) {
        LeafTickContext.set(false);
    }
}
