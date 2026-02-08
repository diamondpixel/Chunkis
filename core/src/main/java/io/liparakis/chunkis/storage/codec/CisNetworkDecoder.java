package io.liparakis.chunkis.storage.codec;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.Palette;
import io.liparakis.chunkis.spi.BlockRegistryAdapter;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.spi.NbtAdapter;
import io.liparakis.chunkis.storage.PropertyPacker;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * High-performance decoder for Chunkis Network (CIS) format chunk delta data.
 * <p>
 * This decoder transforms compressed binary chunk delta data into a
 * {@link ChunkDelta}
 * object, supporting both sparse and dense section encoding modes for optimal
 * bandwidth efficiency.
 *
 * @param <B> Block type
 * @param <S> BlockState type
 * @param <P> Property type
 * @param <N> NBT type
 */
public final class CisNetworkDecoder<B, S, P, N> extends AbstractCisDecoder<S, N> {

    private final BlockRegistryAdapter<B> registryAdapter;
    private final PropertyPacker<B, S, P> propertyPacker;

    /**
     * Constructs a new decoder with pre-allocated buffers.
     */
    public CisNetworkDecoder(
            BlockRegistryAdapter<B> registryAdapter,
            PropertyPacker<B, S, P> propertyPacker,
            BlockStateAdapter<B, S, P> stateAdapter,
            NbtAdapter<N> nbtAdapter,
            S airState) {
        super(stateAdapter, nbtAdapter, airState);
        this.registryAdapter = registryAdapter;
        this.propertyPacker = propertyPacker;
    }

    /**
     * Decodes compressed chunk delta data into a ChunkDelta object.
     *
     * @param data The compressed binary chunk delta data. Must not be null.
     * @return A fully populated ChunkDelta object
     * @throws IOException if data is corrupted, version mismatch, or format invalid
     */
    public ChunkDelta<S, N> decode(byte[] data) throws IOException {
        return decodeInternal(data);
    }

    @Override
    protected int decodeGlobalPalette(byte[] data, int offset, Palette<S> palette) throws IOException {
        int globalPaletteSize = readIntBE(data, offset);
        offset += 4;

        if (globalPaletteSize < 0 || globalPaletteSize > MAX_REASONABLE_PALETTE_SIZE) {
            throw new IOException("Invalid palette size: " + globalPaletteSize);
        }

        List<B> blocks = new ArrayList<>(globalPaletteSize);

        ByteArrayInputStream bais = new ByteArrayInputStream(data, offset, data.length - offset);
        int initialAvailable = bais.available();

        try (DataInputStream dis = new DataInputStream(bais)) {
            for (int i = 0; i < globalPaletteSize; i++) {
                String idStr = dis.readUTF();
                B block = registryAdapter.getBlock(idStr);
                if (block == null) {
                    block = registryAdapter.getAir();
                    // Log warning? Using generic logger?
                }
                blocks.add(block);
            }
        }

        int bytesRead = initialAvailable - bais.available();
        offset += bytesRead;

        int propLength = readIntBE(data, offset);
        offset += 4;

        propertyReader.setData(data, offset, propLength);
        offset += propLength;

        globalPalette = new ArrayList<>(globalPaletteSize);
        for (int i = 0; i < globalPaletteSize; i++) {
            B block = blocks.get(i);
            var metas = propertyPacker.getPropertyMetas(block);
            S state = propertyPacker.readProperties(propertyReader, block, metas);
            globalPalette.add(state);
            palette.getOrAdd(state);
        }

        return offset;
    }
}
