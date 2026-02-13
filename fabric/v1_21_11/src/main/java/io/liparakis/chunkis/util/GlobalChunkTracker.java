package io.liparakis.chunkis.util;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global registry for tracking dirty chunk deltas that require persistence.
 *
 * <p>
 * Uses a combination of a dirty delta map (for active chunks) and a
 * size-limited
 * LRU cache (for recently unloaded chunks) to ensure deltas survive the
 * critical
 * unload-reload gap during player disconnects.
 *
 * @author io.liparakis
 * @since 1.0
 */
public class GlobalChunkTracker {
    /**
     * Maximum number of deltas to keep in memory for unloaded chunks
     * before they are allowed to be garbage collected.
     */
    private static final int MAX_CACHE_SIZE = 10000;

    /**
     * Map of chunk position keys to deltas.
     * Stores strong references to ensure persistence during unload/load gaps.
     */
    private static final Map<Long, ChunkDelta<?, ?>> dirtyDeltas = new ConcurrentHashMap<>();

    /**
     * Cache for recently unloaded deltas to prevent memory leaks while
     * ensuring they survive the critical re-join window.
     */
    private static final Map<Long, ChunkDelta<?, ?>> unloadCache = new LinkedHashMap<Long, ChunkDelta<?, ?>>(
            MAX_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, ChunkDelta<?, ?>> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };

    private static final org.slf4j.Logger LOGGER = io.liparakis.chunkis.Chunkis.LOGGER;

    /**
     * Marks a chunk's delta as dirty and ensures it's cached.
     */
    public static void markDirty(WorldChunk chunk) {
        if (!(chunk instanceof ChunkisDeltaDuck deltaDuck))
            return;

        ChunkDelta<?, ?> delta = deltaDuck.chunkis$getDelta();
        if (delta == null || delta.isEmpty())
            return;

        long chunkKey = chunk.getPos().toLong();
        dirtyDeltas.put(chunkKey, delta);

        synchronized (unloadCache) {
            unloadCache.put(chunkKey, delta);
        }
    }

    /**
     * Explicitly adds a delta to the tracker and marks it as dirty for persistence.
     */
    public static void addDelta(ChunkPos pos, ChunkDelta<?, ?> delta) {
        long chunkKey = pos.toLong();
        delta.markDirty();
        dirtyDeltas.put(chunkKey, delta);

        synchronized (unloadCache) {
            unloadCache.put(chunkKey, delta);
        }
    }

    /**
     * Retrieves a tracked delta by position.
     */
    public static ChunkDelta<?, ?> getDelta(ChunkPos position) {
        long chunkKey = position.toLong();

        ChunkDelta<?, ?> delta = dirtyDeltas.get(chunkKey);

        if (delta == null) {
            synchronized (unloadCache) {
                delta = unloadCache.get(chunkKey);
            }
        }

        if (LOGGER.isDebugEnabled() && delta != null) {
            LOGGER.debug("Chunkis: Retrieved delta from tracker for {} (Size: {} blocks)",
                    position, delta.getBlockInstructions().size());
        }

        return delta;
    }

    /**
     * Removes a delta from tracking once it has been successfully saved to disk.
     */
    public static void markSaved(ChunkPos position) {
        long chunkKey = position.toLong();
        dirtyDeltas.remove(chunkKey);
        synchronized (unloadCache) {
            unloadCache.remove(chunkKey);
        }
    }
}
