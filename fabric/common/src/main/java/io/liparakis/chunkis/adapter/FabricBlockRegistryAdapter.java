package io.liparakis.chunkis.adapter;

import io.liparakis.chunkis.spi.BlockRegistryAdapter;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Fabric implementation of BlockRegistryAdapter with performance optimizations.
 * This adapter provides bidirectional mapping between blocks and their string
 * identifiers
 * with caching to reduce memory allocation and improve lookup performance.
 *
 * <p>
 * Thread-safe for concurrent access.
 *
 * @author Liparakis
 * @version 1.0
 */
public final class FabricBlockRegistryAdapter implements BlockRegistryAdapter<Block> {

    // Bidirectional caches for fast lookups
    // ConcurrentHashMap provides thread-safety without synchronized overhead
    private final Map<Block, String> blockToIdCache = new ConcurrentHashMap<>(256);
    private final Map<String, Identifier> stringToIdentifierCache = new ConcurrentHashMap<>(256);
    private final Map<String, Block> idToBlockCache = new ConcurrentHashMap<>(256);

    // Pre-interned constant for the most common case
    private static final Block AIR_BLOCK = Blocks.AIR;
    private static final String AIR_ID = "minecraft:air";

    /**
     * Maximum cache size to prevent unbounded memory growth.
     * Minecraft typically has < 1000 blocks, so 1024 provides headroom.
     */
    private static final int MAX_CACHE_SIZE = 1024;

    /**
     * Retrieves the string identifier for a given block with caching.
     *
     * @param block the block to identify
     * @return the string identifier (e.g., "minecraft:stone")
     * @throws NullPointerException if block is null
     */
    @Override
    public String getId(Block block) {
        // Fast path for most common case
        if (block == AIR_BLOCK) {
            return AIR_ID;
        }

        // Use computeIfAbsent for atomic cache population
        return blockToIdCache.computeIfAbsent(block, b -> {
            evictIfNeeded(blockToIdCache);
            // Intern string to reduce memory footprint for duplicate IDs
            return Registries.BLOCK.getId(b).toString().intern();
        });
    }

    /**
     * Retrieves a block from its string identifier with caching and validation.
     *
     * @param id the string identifier (e.g., "minecraft:stone")
     * @return the corresponding block, or AIR if the identifier is invalid
     */
    @Override
    public Block getBlock(String id) {
        // Null safety - defensive programming
        if (id == null || id.isEmpty()) {
            return AIR_BLOCK;
        }

        // Fast path for most common case
        if (AIR_ID.equals(id)) {
            return AIR_BLOCK;
        }

        return idToBlockCache.computeIfAbsent(id, this::parseAndRetrieveBlock);
    }

    /**
     * Helper method to parse identifier and retrieve block with caching.
     *
     * @param id The string identifier to parse.
     * @return The resolved Block.
     */
    private Block parseAndRetrieveBlock(String id) {
        evictIfNeeded(idToBlockCache);

        // Cache the parsed identifier to avoid repeated parsing
        Identifier identifier = stringToIdentifierCache.computeIfAbsent(id, idStr -> {
            evictIfNeeded(stringToIdentifierCache);
            Identifier parsed = Identifier.tryParse(idStr);
            // Fallback to AIR if parsing fails to prevent crashes
            return parsed != null ? parsed : Identifier.of("minecraft", "air");
        });

        // Retrieve from registry (already cached by Minecraft internally)
        return Registries.BLOCK.get(identifier);
    }

    /**
     * Returns the AIR block constant.
     *
     * @return the AIR block
     */
    @Override
    public Block getAir() {
        return AIR_BLOCK;
    }

    /**
     * Simple cache size management to prevent unbounded growth.
     * Uses a naive clear strategy when limit is reached.
     * <p>
     * For production, consider using Guava's LoadingCache with LRU eviction
     * or Caffeine cache for more sophisticated eviction policies.
     *
     * @param cache The map to check and potentially clear.
     */
    private void evictIfNeeded(Map<?, ?> cache) {
        // Check if cache exceeds maximum allowed size
        if (cache.size() > MAX_CACHE_SIZE) {
            // Simple strategy: clear when limit reached
            // More sophisticated: implement LRU eviction
            cache.clear();
        }
    }
}