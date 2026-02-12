package io.liparakis.chunkis.spi;

import java.util.Comparator;
import java.util.List;

/**
 * Adapter interface for interacting with BlockStates and Properties
 * without direct dependencies on the Minecraft code.
 *
 * @param <B> The Block (or Block ID/Type) type
 * @param <S> The BlockState type
 * @param <P> The Property type
 */
public interface BlockStateAdapter<B, S, P> {

    /** Gets the default state for the given block. */
    S getDefaultState(B block);

    /** Gets the block corresponding to the given state. */
    B getBlock(S state);

    /** Gets all properties defined for the given block. */
    List<P> getProperties(B block);

    /** Gets the name of the property. */
    String getPropertyName(P property);

    /** Gets all allowed values for the property. */
    List<Object> getPropertyValues(P property);

    /** Gets the index of the current value of the property in the state. */
    int getValueIndex(S state, P property);

    /**
     * Returns a new state with the property set to the value at the given index.
     */
    S withProperty(S state, P property, int valueIndex);

    /** Returns a comparator for sorting property values deterministically. */
    Comparator<Object> getValueComparator();

    /** Checks if the state is considered air/empty. */
    boolean isAir(S state);
}
