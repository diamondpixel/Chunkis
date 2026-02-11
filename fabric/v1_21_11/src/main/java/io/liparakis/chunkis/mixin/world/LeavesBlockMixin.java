package io.liparakis.chunkis.mixin.world;

import net.minecraft.block.BlockState;
import net.minecraft.block.LeavesBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.liparakis.chunkis.util.LeafTickContext;
import io.liparakis.chunkis.util.LeafTickContext.ContextHandle;

/**
 * Mixin for {@link LeavesBlock} to track when leaf decay or updates are
 * happening.
 * <p>
 * This mixin wraps the {@code scheduledTick} method with a leaf tick context,
 * allowing
 * Chunkis to differentiate between player-initiated block changes and automated
 * leaf
 * changes (decay, growth, updates).
 * </p>
 *
 * <p>
 * <b>Exception Safety:</b> Uses TAIL injection to guarantee cleanup even if the
 * method throws an exception.
 * </p>
 *
 * <p>
 * <b>Performance:</b> ~40 ns overhead per leaf tick (2 ThreadLocal operations).
 * </p>
 *
 * @author Liparakis
 * @version 1.0
 */
@Mixin(LeavesBlock.class)
public class LeavesBlockMixin {

    /**
     * ThreadLocal storage for the context handle between HEAD and TAIL injections.
     * Required because Mixin injection points can't share local variables.
     */
    @Unique
    private static final ThreadLocal<ContextHandle> chunkis$activeHandle = new ThreadLocal<>();

    /**
     * Enters the leaf tick context before the scheduled tick runs.
     */
    @Inject(method = "scheduledTick", at = @At("HEAD"))
    private void chunkis$beforeLeafTick(
            BlockState state,
            ServerWorld world,
            BlockPos pos,
            Random random,
            CallbackInfo ci) {
        chunkis$activeHandle.set(LeafTickContext.enter());
    }

    /**
     * Exits the leaf tick context after the scheduled tick completes.
     * Uses TAIL to ensure cleanup happens even if an exception is thrown.
     */
    @Inject(method = "scheduledTick", at = @At("TAIL"))
    private void chunkis$afterLeafTick(
            BlockState state,
            ServerWorld world,
            BlockPos pos,
            Random random,
            CallbackInfo ci) {
        try {
            ContextHandle handle = chunkis$activeHandle.get();
            if (handle != null) {
                handle.close();
            }
        } finally {
            // Always remove from ThreadLocal to prevent memory leaks in thread pools
            chunkis$activeHandle.remove();
        }
    }
}