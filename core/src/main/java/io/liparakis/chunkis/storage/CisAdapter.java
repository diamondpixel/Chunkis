package io.liparakis.chunkis.storage;

import io.liparakis.chunkis.storage.BitUtils.BitReader;
import io.liparakis.chunkis.storage.BitUtils.BitWriter;

import java.io.IOException;

/**
 * Adapter interface to decouple the CIS storage engine from a specific block
 * state implementation.
 * This allows the core encoding/decoding logic to be reused across different
 * Minecraft versions
 * or even outside of Minecraft for standalone tools.
 *
 * @param <S> the type representing a block state (e.g.,
 *            `net.minecraft.block.BlockState`)
 */
public interface CisAdapter<S> {

    /**
     * Gets the unique numeric ID for the given block state's block type.
     * This ID is used for palette indexing during serialization.
     *
     * @param state the block state
     * @return the block's unique ID
     */
    int getBlockId(S state);

    /**
     * Writes the property values of a block state to a bit stream.
     * The number of bits and property order must be deterministic.
     *
     * @param writer the bit writer
     * @param state  the block state to serialize
     */
    void writeStateProperties(BitWriter writer, S state);

    /**
     * Reads property values from a bit stream and reconstructs a block state.
     *
     * @param reader  the bit reader
     * @param blockId the block's unique ID
     * @return the reconstructed block state
     * @throws IOException if deserialization fails
     */
    S readStateProperties(BitReader reader, int blockId) throws IOException;
}
