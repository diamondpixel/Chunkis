package io.liparakis.chunkis.storage;

import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.storage.BitUtils.BitReader;
import io.liparakis.chunkis.storage.BitUtils.BitWriter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
 * <p>
 * Performance characteristics:
 * <ul>
 * <li>O(1) amortized cache lookup via ConcurrentHashMap</li>
 * <li>O(n log n) initial metadata creation per block (sorted properties)</li>
 * <li>O(n) property read/write where n = number of properties</li>
 * </ul>
 * </p>
 *
 * @param <B> Block type
 * @param <S> BlockState type
 * @param <P> Property type
 */
public final class PropertyPacker<B, S, P> {

    private static final int DEFAULT_CACHE_CAPACITY = 512;
    private static final float CACHE_LOAD_FACTOR = 0.75f;

    private final BlockStateAdapter<B, S, P> adapter;
    private final Map<B, PropertyMeta<P>[]> cache;

    /**
     * Creates a PropertyPacker with default cache settings.
     * 
     * @param adapter The block state adapter
     */
    public PropertyPacker(BlockStateAdapter<B, S, P> adapter) {
        this(adapter, DEFAULT_CACHE_CAPACITY);
    }

    /**
     * Creates a PropertyPacker with custom initial cache capacity.
     * 
     * @param adapter       The block state adapter
     * @param cacheCapacity Initial cache capacity (should match expected block type
     *                      count)
     */
    public PropertyPacker(BlockStateAdapter<B, S, P> adapter, int cacheCapacity) {
        this.adapter = adapter;
        // Use available processors for concurrency level to reduce thread contention
        int concurrencyLevel = Math.max(4, Runtime.getRuntime().availableProcessors());
        this.cache = new ConcurrentHashMap<>(cacheCapacity, CACHE_LOAD_FACTOR, concurrencyLevel);
    }

    /**
     * Gets or creates property metadata for a block.
     * This method is thread-safe and ensures metadata is computed only once per
     * block.
     * 
     * @param block The block to get metadata for
     * @return Cached property metadata array
     */
    public PropertyMeta<P>[] getPropertyMetas(B block) {
        return cache.computeIfAbsent(block, this::createPropertyMetas);
    }

    private PropertyMeta<P>[] createPropertyMetas(B block) {
        return PropertyMeta.create(adapter, block);
    }

    /**
     * Writes all property values of a BlockState to the BitWriter.
     * 
     * @param writer The bit writer
     * @param state  The block state
     * @param metas  Pre-computed property metadata (from getPropertyMetas)
     */
    public void writeProperties(BitWriter writer, S state, PropertyMeta<P>[] metas) {
        // Enhanced for-loop is optimal here - JIT compiles to efficient code
        for (PropertyMeta<P> meta : metas) {
            int valueIndex = adapter.getValueIndex(state, meta.property);
            writer.write(valueIndex, meta.bits);
        }
    }

    /**
     * Reads property values from BitReader and reconstructs the BlockState.
     * 
     * @param reader The bit reader
     * @param block  The block type
     * @param metas  Pre-computed property metadata (from getPropertyMetas)
     * @return Reconstructed block state
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
     * 
     * @param property The property instance
     * @param bits     Number of bits required to encode all possible values
     * @param <P>      Property type
     */
    public record PropertyMeta<P>(P property, int bits) {

        // Singleton empty array to avoid repeated allocations
        private static final PropertyMeta<?>[] EMPTY_ARRAY = new PropertyMeta<?>[0];

        /**
         * Compact canonical constructor that calculates the minimum number of bits
         * needed to represent the given number of values.
         * 
         * @param property The property
         * @param bits     Number of possible values (will be converted to bit count)
         */
        public PropertyMeta {
            bits = calculateBitsRequired(bits);
        }

        /**
         * Calculates the minimum number of bits needed to represent valueCount distinct
         * values.
         * Examples:
         * - 1 value → 1 bit (special case, minimum)
         * - 2 values → 1 bit (0, 1)
         * - 3 values → 2 bits (0, 1, 2, with 3 unused)
         * - 4 values → 2 bits (0, 1, 2, 3)
         * - 5 values → 3 bits (0-4, with 5-7 unused)
         * 
         * @param valueCount Number of values to encode
         * @return Minimum bits required (at least 1)
         */
        private static int calculateBitsRequired(int valueCount) {
            if (valueCount <= 1) {
                return 1; // Minimum 1 bit even for single value
            }
            // For n values, we need ceil(log2(n)) bits
            // This is equivalent to: 32 - numberOfLeadingZeros(n - 1)
            return 32 - Integer.numberOfLeadingZeros(valueCount - 1);
        }

        /**
         * Creates property metadata array for a block.
         * Properties are sorted by name for deterministic serialization.
         * 
         * @param adapter The block state adapter
         * @param block   The block
         * @return Array of property metadata
         */
        static <B, S, P> PropertyMeta<P>[] create(BlockStateAdapter<B, S, P> adapter, B block) {
            List<P> props = adapter.getProperties(block);

            if (props.isEmpty()) {
                return (PropertyMeta<P>[]) EMPTY_ARRAY;
            }

            // Create mutable copy to avoid UnsupportedOperationException if props is
            // immutable
            ArrayList<P> mutableProps = new ArrayList<>(props);

            // Sort properties by name for deterministic serialization
            mutableProps.sort(Comparator.comparing(adapter::getPropertyName));

            PropertyMeta<P>[] metas = new PropertyMeta[mutableProps.size()];
            for (int i = 0; i < mutableProps.size(); i++) {
                P prop = mutableProps.get(i);
                int valueCount = adapter.getPropertyValues(prop).size();
                metas[i] = new PropertyMeta<>(prop, valueCount);
            }
            return metas;
        }
    }
}
