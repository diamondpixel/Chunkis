package io.liparakis.chunkis.spi;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * Adapter interface for NBT I/O operations.
 *
 * @param <N> The NBT type
 */
public interface NbtAdapter<N> {

    /** Writes the NBT compound to the output. */
    void write(N tag, DataOutput output) throws IOException;

    /** Reads an NBT compound from the input. */
    N read(DataInput input) throws IOException;
}
