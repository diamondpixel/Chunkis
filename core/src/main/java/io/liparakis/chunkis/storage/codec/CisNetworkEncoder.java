package io.liparakis.chunkis.storage.codec;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.spi.BlockRegistryAdapter;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.spi.NbtAdapter;
import io.liparakis.chunkis.storage.PropertyPacker;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * High-performance encoder for Chunkis Network (CIS) format chunk delta data.
 * <p>
 * This encoder transforms {@link ChunkDelta} objects into compressed binary
 * format, optimizing for both bandwidth efficiency and encoding speed.
 * </p>
 *
 * @param <B> Block type
 * @param <S> BlockState type
 * @param <P> Property type
 * @param <N> NBT type
 */
public final class CisNetworkEncoder<B, S, P, N> extends AbstractCisEncoder<S, N> {

    /**
     * Thread-local encoder context for allocation-free encoding.
     */
    @SuppressWarnings("rawtypes")
    private static final ThreadLocal<EncoderContext> CONTEXT = ThreadLocal.withInitial(EncoderContext::new);

    private final BlockRegistryAdapter<B> registryAdapter;
    private final PropertyPacker<B, S, P> propertyPacker;
    // We also need access to block from state, which stateAdapter provides.
    // AbstractCisEncoder has protected stateAdapter, but we need it cast to <B,S,P>
    // or use getter?
    // Actually propertyPacker might handle packing if we give it state?
    // Let's check PropertyPacker API.
    // It has pack(S state, BitWriter writer).
    // So we don't need to manually get properties or block.

    /**
     * Constructs a new CisNetworkEncoder.
     */
    public CisNetworkEncoder(
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
     * Encodes a chunk delta into compressed binary format.
     */
    public byte[] encode(ChunkDelta<S, N> delta) throws IOException {
        return encodeInternal(delta);
    }

    @Override
    protected void writeGlobalPalette(
            DataOutputStream dos,
            EncoderContext<S> ctx,
            List<S> usedStates) throws IOException {

        ctx.globalIdMap.defaultReturnValue(-1);
        dos.writeInt(usedStates.size());
        ctx.bitWriter.reset();

        for (int i = 0; i < usedStates.size(); i++) {
            S state = usedStates.get(i);

            // Write block identifier as string
            B block = ((BlockStateAdapter<B, S, P>) stateAdapter).getBlock(state);
            String blockId = registryAdapter.getId(block);
            dos.writeUTF(blockId);

            // Write properties
            var metas = propertyPacker.getPropertyMetas(block);
            propertyPacker.writeProperties(ctx.bitWriter, state, metas);

            // Build reverse mapping for quick state→index lookups
            ctx.globalIdMap.put(state, i);
        }

        // Write property data as separate section
        byte[] palettePropertyData = ctx.bitWriter.toByteArray();
        dos.writeInt(palettePropertyData.length);
        dos.write(palettePropertyData);
    }

    @SuppressWarnings("unchecked")
    @Override
    protected EncoderContext<S> getContext() {
        return CONTEXT.get();
    }
}
