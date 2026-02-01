package io.liparakis.chunkis.mixin.world;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import net.minecraft.world.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Mixin for {@link ProtoChunk} to implement the {@link ChunkisDeltaDuck}
 * interface.
 * <p>
 * This allows each chunk instance to maintain its own {@link ChunkDelta} for
 * tracking block changes and other delta data.
 */
@Mixin(ProtoChunk.class)
public class ProtoChunkMixin implements ChunkisDeltaDuck {

    /**
     * The delta data associated with this chunk.
     */
    @Unique
    private final ChunkDelta chunkis$delta = new ChunkDelta();

    @Override
    public ChunkDelta chunkis$getDelta() {
        return chunkis$delta;
    }
}
