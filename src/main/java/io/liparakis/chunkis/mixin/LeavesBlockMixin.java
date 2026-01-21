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

@Mixin(LeavesBlock.class)
public class LeavesBlockMixin {
    @Inject(method = "scheduledTick", at = @At("HEAD"))
    private void chunkis$beforeLeafTick(BlockState state, ServerWorld world, BlockPos pos, Random random,
            CallbackInfo ci) {
        LeafTickContext.set(true);
    }

    @Inject(method = "scheduledTick", at = @At("RETURN"))
    private void chunkis$afterLeafTick(BlockState state, ServerWorld world, BlockPos pos, Random random,
            CallbackInfo ci) {
        LeafTickContext.set(false);
    }
}
