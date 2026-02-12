package io.liparakis.chunkis.spi;

/**
 * Adapter interface for interacting with the Block Registry.
 *
 * @param <B> The Block type
 */
public interface BlockRegistryAdapter<B> {

    /** Gets the string identifier for the given block. */
    String getId(B block);

    /**
     * Gets the block for the given string identifier, or null/default if not found.
     */
    B getBlock(String id);

    /** Gets the "Air" block instance. */
    B getAir();
}
