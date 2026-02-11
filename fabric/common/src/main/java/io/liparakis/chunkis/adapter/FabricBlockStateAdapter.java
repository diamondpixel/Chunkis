package io.liparakis.chunkis.adapter;

import io.liparakis.chunkis.spi.BlockStateAdapter;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.state.property.Property;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance Fabric implementation of BlockStateAdapter with aggressive
 * caching.
 *
 * <p>
 * This adapter optimizes for Minecraft's immutable block state system where:
 * <ul>
 * <li>Block properties are defined at registration time and never change</li>
 * <li>Property values are finite and immutable</li>
 * <li>Block states are queried millions of times per second</li>
 * </ul>
 *
 * <p>
 * Thread-safe for concurrent access.
 *
 * @author Liparakis
 * @version 1.0
 */
public final class FabricBlockStateAdapter implements BlockStateAdapter<Block, BlockState, Property<?>> {

    // Caches for immutable block metadata
    private final Map<Block, List<Property<?>>> blockPropertiesCache = new ConcurrentHashMap<>(256);
    private final Map<Property<?>, List<Object>> propertyValuesCache = new ConcurrentHashMap<>(512);
    private final Map<Property<?>, Map<Object, Integer>> valueIndexCache = new ConcurrentHashMap<>(512);

    // Shared immutable empty list to avoid allocation
    private static final List<Property<?>> EMPTY_PROPERTIES = Collections.emptyList();
    private static final List<Object> EMPTY_VALUES = Collections.emptyList();

    // Reusable comparator instance
    private static final Comparator<Object> VALUE_COMPARATOR = Comparator.comparing(Object::toString);

    /**
     * Extracts the block from a block state.
     *
     * @param state the block state
     * @return the block
     * @throws NullPointerException if state is null
     */
    @Override
    public Block getBlock(BlockState state) {
        Objects.requireNonNull(state, "BlockState cannot be null");
        return state.getBlock();
    }

    /**
     * Returns an immutable list of properties for the given block with caching.
     *
     * <p>
     * Block properties are defined at registration and never change,
     * making them ideal for caching.
     *
     * @param block the block
     * @return unmodifiable list of properties
     * @throws NullPointerException if block is null
     */
    @Override
    public List<Property<?>> getProperties(Block block) {
        Objects.requireNonNull(block, "Block cannot be null");

        return blockPropertiesCache.computeIfAbsent(block, b -> {
            Collection<Property<?>> properties = b.getStateManager().getProperties();

            // Fast path for blocks without properties (common case)
            if (properties.isEmpty()) {
                return EMPTY_PROPERTIES;
            }

            // Create immutable copy to prevent external modification
            return List.copyOf(properties);
        });
    }

    /**
     * Returns the name of a property.
     *
     * @param property the property
     * @return the property name
     * @throws NullPointerException if property is null
     */
    @Override
    public String getPropertyName(Property<?> property) {
        Objects.requireNonNull(property, "Property cannot be null");
        return property.getName();
    }

    /**
     * Returns an immutable list of possible values for a property with caching.
     *
     * @param property the property
     * @return unmodifiable list of values
     * @throws NullPointerException if property is null
     */
    @Override
    public List<Object> getPropertyValues(Property<?> property) {
        Objects.requireNonNull(property, "Property cannot be null");

        return propertyValuesCache.computeIfAbsent(property, p -> {
            // Use reflection to get values to handle signature changes (Collection vs List)
            // and mapping differences across versions
            Collection<?> values;
            try {
                values = getPropertyValuesReflectively(p);
            } catch (Exception e) {
                // Fallback to direct call if reflection fails (unlikely, but safe)
                // This might throw NoSuchMethodError if signature mismatch exists,
                // but we tried reflection first.
                values = p.getValues();
            }

            if (values.isEmpty()) {
                return EMPTY_VALUES;
            }

            // Store as List<Object> for API compatibility
            return List.copyOf(values);
        });
    }

    // Cache the method lookup
    private static final java.lang.reflect.Method GET_VALUES_METHOD;
    static {
        java.lang.reflect.Method m = null;
        try {
            // Try finding by name first (dev env)
            m = Property.class.getMethod("getValues");
        } catch (NoSuchMethodException e) {
            // Fallback: find by signature (returns Collection/List, no args)
            // This handles remapped names (intermediary/prod) and return type changes
            for (java.lang.reflect.Method method : Property.class.getMethods()) {
                if (Collection.class.isAssignableFrom(method.getReturnType()) &&
                        method.getParameterCount() == 0 &&
                        !method.getReturnType().equals(Class.class) && // exclude getType
                        !method.getReturnType().equals(String.class) && // exclude getName
                        !method.getReturnType().equals(Optional.class) // exclude parse
                ) {
                    m = method;
                    break;
                }
            }
        }
        GET_VALUES_METHOD = m;
    }

    /**
     * Reflectively calls {@code Property.getValues()}.
     * <p>
     * This handles cases where the method signature might vary between Minecraft
     * versions
     * or mappings (e.g., returning List vs Collection).
     *
     * @param property The property to retrieve values from.
     * @return A collection of allowed values.
     * @throws Exception If reflection fails.
     */
    private static Collection<?> getPropertyValuesReflectively(Property<?> property) throws Exception {
        if (GET_VALUES_METHOD != null) {
            return (Collection<?>) GET_VALUES_METHOD.invoke(property);
        }
        return property.getValues();
    }

    /**
     * Gets the index of the current value of a property in the block state.
     *
     * <p>
     * Optimized with O(1) index lookup using cached value-to-index mapping.
     *
     * @param state    the block state
     * @param property the property to query
     * @return the index of the current value, or -1 if not found
     * @throws NullPointerException if state or property is null
     */
    @Override
    public int getValueIndex(BlockState state, Property<?> property) {
        Objects.requireNonNull(state, "BlockState cannot be null");
        Objects.requireNonNull(property, "Property cannot be null");

        // Get current value from state
        Comparable<?> currentValue = state.get(property);

        // Use cached index mapping for O(1) lookup
        Map<Object, Integer> indexMap = getOrCreateIndexMap(property);
        return indexMap.getOrDefault(currentValue, -1);
    }

    /**
     * Creates a new block state with the specified property value.
     *
     * <p>
     * Optimized with direct indexed access instead of creating ArrayList
     * and performing linear search.
     *
     * @param state      the original block state
     * @param property   the property to modify
     * @param valueIndex the index of the desired value
     * @return new block state with the property set, or original state if index is
     *         invalid
     * @throws NullPointerException if state or property is null
     */
    @Override
    public BlockState withProperty(BlockState state, Property<?> property, int valueIndex) {
        Objects.requireNonNull(state, "BlockState cannot be null");
        Objects.requireNonNull(property, "Property cannot be null");

        // Get cached values list
        List<Object> values = getPropertyValues(property);

        // Bounds check
        if (valueIndex < 0 || valueIndex >= values.size()) {
            return state;
        }

        // Direct indexed access - no ArrayList creation or indexOf search
        Object value = values.get(valueIndex);

        // Type-safe cast: value comes from property.getValues()
        @SuppressWarnings("rawtypes")
        Property rawProperty = property;

        return state.with(rawProperty, (Comparable) value);
    }

    /**
     * Returns the default state for a block.
     *
     * @param block the block
     * @return the default block state
     * @throws NullPointerException if block is null
     */
    @Override
    public BlockState getDefaultState(Block block) {
        Objects.requireNonNull(block, "Block cannot be null");
        return block.getDefaultState();
    }

    /**
     * Checks if a block state represents air.
     *
     * @param state the block state
     * @return true if the state is air
     * @throws NullPointerException if state is null
     */
    @Override
    public boolean isAir(BlockState state) {
        Objects.requireNonNull(state, "BlockState cannot be null");
        return state.isAir();
    }

    /**
     * Returns a comparator for property values.
     *
     * <p>
     * Uses string representation for deterministic, stable sorting
     * across different property value types (enums, integers, booleans).
     *
     * @return comparator based on string representation
     */
    @Override
    public Comparator<Object> getValueComparator() {
        return VALUE_COMPARATOR;
    }

    /**
     * Gets or creates a value-to-index mapping for O(1) lookups.
     * <p>
     * This cache allows us to find the index of a property value in constant time,
     * avoiding linear scans of the values list.
     * <p>
     * Package-private for testing visibility.
     *
     * @param property The property to create an index map for.
     * @return A map from value object to its integer index.
     */
    Map<Object, Integer> getOrCreateIndexMap(Property<?> property) {
        return valueIndexCache.computeIfAbsent(property, p -> {
            List<Object> values = getPropertyValues(p);
            // Create map with initial capacity to avoid resizing
            Map<Object, Integer> indexMap = new HashMap<>(values.size());

            // Populate the map: Value -> Index
            for (int i = 0; i < values.size(); i++) {
                indexMap.put(values.get(i), i);
            }

            return Collections.unmodifiableMap(indexMap);
        });
    }
}