package io.liparakis.chunkis.mixin;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.ChunkisDeltaDuck;
import net.minecraft.world.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ProtoChunk.class)
public class ProtoChunkMixin implements ChunkisDeltaDuck {

    @Unique
    private final ChunkDelta chunkis$delta = new ChunkDelta();

    @Override
    public ChunkDelta chunkis$getDelta() {
        return chunkis$delta;
    }
}
