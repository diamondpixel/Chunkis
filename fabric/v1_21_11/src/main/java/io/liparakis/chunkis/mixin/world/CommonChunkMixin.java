package io.liparakis.chunkis.mixin.world;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Mixin for the base {@link Chunk} class to provide {@link ChunkDelta}
 * capability
 * to all chunk types (ProtoChunk, WorldChunk, etc.).
 *
 * @author Liparakis
 * @version 1.0
 */
@Mixin(Chunk.class)
public abstract class CommonChunkMixin implements ChunkisDeltaDuck {

    /**
     * Delta tracking modifications.
     * Initialized for every chunk instance.
     */
    @Unique
    private ChunkDelta chunkis$delta = new ChunkDelta();

    @Override
    public ChunkDelta chunkis$getDelta() {
        return chunkis$delta;
    }

    @Override
    public void chunkis$setDelta(ChunkDelta delta) {
        this.chunkis$delta = delta;
    }
}
