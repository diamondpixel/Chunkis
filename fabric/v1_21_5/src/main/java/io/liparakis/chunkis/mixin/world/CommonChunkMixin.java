package io.liparakis.chunkis.mixin.world;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Objects;

/**
 * Mixin for the base {@link Chunk} class to provide {@link ChunkDelta}
 * capability to all chunk types.
 *
 * @author Liparakis
 * @version 1.0
 */
@Mixin(Chunk.class)
public abstract class CommonChunkMixin implements ChunkisDeltaDuck {

    @Unique
    private volatile ChunkDelta chunkis$delta = new ChunkDelta();

    @Override
    public ChunkDelta chunkis$getDelta() {
        return chunkis$delta;
    }

    @Override
    public void chunkis$setDelta(ChunkDelta delta) {
        this.chunkis$delta = Objects.requireNonNull(delta, "ChunkDelta cannot be null");
    }

    @Inject(method = "needsSaving", at = @At("RETURN"), cancellable = true)
    private void chunkis$onNeedsSaving(CallbackInfoReturnable<Boolean> cir) {
        if (shouldOverrideSavingFlag(cir.getReturnValueZ())) {
            logSavingOverride();
            cir.setReturnValue(true);
        }
    }

    @Unique
    private boolean shouldOverrideSavingFlag(boolean currentlySaving) {
        return !currentlySaving && chunkis$delta.isDirty();
    }

    @Unique
    private void logSavingOverride() {
        io.liparakis.chunkis.Chunkis.LOGGER.debug(
                "Chunkis: needsSaving overridden to true for chunk (Hash: {})",
                System.identityHashCode(this)
        );
    }
}