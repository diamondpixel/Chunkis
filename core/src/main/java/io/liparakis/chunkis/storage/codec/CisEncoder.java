package io.liparakis.chunkis.storage.codec;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.spi.NbtAdapter;
import io.liparakis.chunkis.storage.CisAdapter;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Encoder for Chunkis CIS format (V7) with paletted section storage and dynamic
 * property bit-packing.
 *
 * @param <S> The BlockState type
 * @param <N> The NBT type
 */
public final class CisEncoder<S, N> extends AbstractCisEncoder<S, N> {

    /**
     * Thread-local context to minimize allocations during encoding.
     */
    @SuppressWarnings("rawtypes")
    private static final ThreadLocal<EncoderContext> CONTEXT = ThreadLocal.withInitial(EncoderContext::new);

    private final CisAdapter<S> cisAdapter;

    /**
     * Constructs a new CisEncoder.
     */
    public CisEncoder(CisAdapter<S> cisAdapter, BlockStateAdapter<?, S, ?> stateAdapter, NbtAdapter<N> nbtAdapter,
            S airState) {
        super(stateAdapter, nbtAdapter, airState);
        this.cisAdapter = cisAdapter;
    }

    /**
     * Encodes a ChunkDelta into the Chunkis V7 binary format.
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
            dos.writeShort(cisAdapter.getBlockId(state));
            cisAdapter.writeStateProperties(ctx.bitWriter, state);
            ctx.globalIdMap.put(state, i);
        }

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
