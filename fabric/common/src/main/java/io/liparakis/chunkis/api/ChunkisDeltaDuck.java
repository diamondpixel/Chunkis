package io.liparakis.chunkis.api;

import io.liparakis.chunkis.core.ChunkDelta;

/**
 * Interface that allows accessing the {@link ChunkDelta} associated with a
 * world chunk.
 * 
 * @author Liparakis
 * @version 1.0
 */
public interface ChunkisDeltaDuck {
    ChunkDelta chunkis$getDelta();

    void chunkis$setDelta(ChunkDelta delta);
}
