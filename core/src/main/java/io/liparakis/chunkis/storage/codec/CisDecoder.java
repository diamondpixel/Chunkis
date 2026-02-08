package io.liparakis.chunkis.storage.codec;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.Palette;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.spi.NbtAdapter;
import io.liparakis.chunkis.storage.CisAdapter;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Decoder for Chunkis CIS format (V7) with support for paletted sections and
 * dynamic property packing.
 *
 * @param <S> The BlockState type
 * @param <N> The NBT type
 */
public final class CisDecoder<S, N> extends AbstractCisDecoder<S, N> {

    private final CisAdapter<S> cisAdapter;

    /**
     * Constructs a new CisDecoder.
     */
    public CisDecoder(CisAdapter<S> cisAdapter, BlockStateAdapter<?, S, ?> stateAdapter, NbtAdapter<N> nbtAdapter,
            S airState) {
        super(stateAdapter, nbtAdapter, airState);
        this.cisAdapter = cisAdapter;
    }

    /**
     * Decodes a byte array into a ChunkDelta.
     *
     * @param data the encoded CIS format data
     * @return the decoded ChunkDelta
     * @throws IOException if the data is invalid or corrupted
     */
    public ChunkDelta<S, N> decode(byte[] data) throws IOException {
        return decodeInternal(data);
    }

    @Override
    protected int decodeGlobalPalette(byte[] data, int offset, Palette<S> palette) throws IOException {
        if (offset + 4 > data.length) {
            throw new IOException("Truncated data: cannot read global palette size");
        }

        int globalPaletteSize = readIntBE(data, offset);
        offset += 4;

        if (globalPaletteSize < 0 || globalPaletteSize > MAX_REASONABLE_PALETTE_SIZE) {
            throw new IOException(String.format(
                    "Invalid palette size: %d", globalPaletteSize));
        }

        int requiredBytes = globalPaletteSize * 2 + 4;
        if (offset + requiredBytes > data.length) {
            throw new IOException("Truncated data in palette");
        }

        int[] blockIds = new int[globalPaletteSize];
        for (int i = 0; i < globalPaletteSize; i++) {
            blockIds[i] = readShortBE(data, offset) & 0xFFFF;
            offset += 2;
        }

        int propLength = readIntBE(data, offset);
        offset += 4;

        if (offset + propLength > data.length) {
            throw new IOException("Truncated data in properties");
        }

        propertyReader.setData(data, offset, propLength);
        offset += propLength;

        globalPalette = new ArrayList<>(globalPaletteSize);
        for (int i = 0; i < globalPaletteSize; i++) {
            S state = cisAdapter.readStateProperties(propertyReader, blockIds[i]);
            globalPalette.add(state);
            palette.getOrAdd(state);
        }

        return offset;
    }
}
