package io.liparakis.chunkis.util;

import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Global registry for tracking dirty chunks that require persistence.
 *
 * <p>This tracker prevents data loss when {@link net.minecraft.server.world.ChunkHolder}
 * references are updated before the chunk is saved. It maintains weak references to chunks,
 * allowing automatic garbage collection while keeping chunks available during their lifecycle.
 *
 * <p>Thread-safe for concurrent access across multiple world-saving threads.
 *
 * @author io.liparakis
 * @since 1.0
 */
public class GlobalChunkTracker {
    /**
     * Map of chunk position keys to weak chunk references.
     * Key is the long representation of {@link ChunkPos} for O(1) lookups.
     */
    private static final Map<Long, WeakReference<WorldChunk>> dirtyChunks = new ConcurrentHashMap<>();

    private static final org.slf4j.Logger LOGGER = io.liparakis.chunkis.Chunkis.LOGGER;

    /**
     * Marks a chunk as dirty and begins tracking it.
     *
     * <p>Safe to call multiple times for the same chunk. If the chunk is null,
     * this method returns immediately without side effects.
     *
     * @param chunk the chunk to mark as dirty, or null to no-op
     */
    public static void markDirty(WorldChunk chunk) {
        if (chunk == null) return;

        long chunkKey = chunk.getPos().toLong();
        dirtyChunks.put(chunkKey, new WeakReference<>(chunk));
    }

    /**
     * Retrieves a tracked dirty chunk by position.
     *
     * <p>Automatically cleans up stale weak references when the chunk has been
     * garbage collected. Logs retrieval attempts at DEBUG level.
     *
     * @param position the chunk position to lookup
     * @return the tracked chunk instance, or null if not tracked or already collected
     */
    public static WorldChunk getDirtyChunk(ChunkPos position) {
        long chunkKey = position.toLong();
        var weakRef = dirtyChunks.get(chunkKey);

        if (weakRef == null) {
            logRetrievalResult(position, chunkKey, null);
            return null;
        }

        var chunk = weakRef.get();
        if (chunk == null) {
            dirtyChunks.remove(chunkKey); // Cleanup stale reference
        }

        logRetrievalResult(position, chunkKey, chunk);
        return chunk;
    }

    /**
     * Logs chunk retrieval results at DEBUG level with contextual information.
     *
     * @param position the chunk position being retrieved
     * @param chunkKey the long representation of the position
     * @param chunk the retrieved chunk, or null if not found
     */
    private static void logRetrievalResult(ChunkPos position, long chunkKey, WorldChunk chunk) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Chunk retrieval for {}: {} (key: {}, hash: {})",
                    position,
                    chunk != null ? "FOUND" : "MISSING",
                    chunkKey,
                    chunk != null ? System.identityHashCode(chunk) : "N/A");
        }
    }
}