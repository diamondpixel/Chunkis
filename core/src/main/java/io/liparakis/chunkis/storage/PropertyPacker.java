package io.liparakis.chunkis.storage;

import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.storage.BitUtils.BitReader;
import io.liparakis.chunkis.storage.BitUtils.BitWriter;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized utility for property bit-packing and unpacking.
 * <p>
 * This class provides deterministic serialization of BlockState properties
 * using minimal bit widths.
 * </p>
 * <p>
 * Thread-safe: uses a concurrent cache for property metadata.
 * </p>
 *
 * @param <B> Block type
 * @param <S> BlockState type
 * @param <P> Property type
 */
public final class PropertyPacker<B, S, P> {

    private final BlockStateAdapter<B, S, P> adapter;
    private final Map<B, PropertyMeta<P>[]> cache = new ConcurrentHashMap<>(512, 0.75f, 4);

    public PropertyPacker(BlockStateAdapter<B, S, P> adapter) {
        this.adapter = adapter;
    }

    /**
     * Gets or creates property metadata for a block.
     */
    public PropertyMeta<P>[] getPropertyMetas(B block) {
        return cache.computeIfAbsent(block, this::createPropertyMetas);
    }

    private PropertyMeta<P>[] createPropertyMetas(B block) {
        return PropertyMeta.create(adapter, block);
    }

    /**
     * Writes all property values of a BlockState to the BitWriter.
     */
    public void writeProperties(BitWriter writer, S state, PropertyMeta<P>[] metas) {
        for (PropertyMeta<P> meta : metas) {
            int valueIndex = adapter.getValueIndex(state, meta.property);
            writer.write(valueIndex, meta.bits);
        }
    }

    /**
     * Reads property values from BitReader and reconstructs the BlockState.
     */
    public S readProperties(BitReader reader, B block, PropertyMeta<P>[] metas) {
        S state = adapter.getDefaultState(block);
        for (PropertyMeta<P> meta : metas) {
            int index = (int) reader.read(meta.bits);
            state = adapter.withProperty(state, meta.property, index);
        }
        return state;
    }

    /**
         * Stores metadata for a single block property to enable fast bit-packing.
         */
        public record PropertyMeta<P>(P property, int bits) {
            public PropertyMeta(P property, int bits) {
                this.property = property;
                this.bits = Math.max(1, 32 - Integer.numberOfLeadingZeros(bits - 1));
            }

            static <B, S, P> PropertyMeta<P>[] create(BlockStateAdapter<B, S, P> adapter, B block) {
                var props = adapter.getProperties(block);
                if (props.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    PropertyMeta<P>[] empty = new PropertyMeta[0];
                    return empty;
                }

                // sort properties name
                props.sort(Comparator.comparing(adapter::getPropertyName));

                @SuppressWarnings("unchecked")
                PropertyMeta<P>[] metas = new PropertyMeta[props.size()];
                for (int i = 0; i < props.size(); i++) {
                    P prop = props.get(i);
                    int count = adapter.getPropertyValues(prop).size();
                    metas[i] = new PropertyMeta<>(prop, count);
                }
                return metas;
            }
        }
}
