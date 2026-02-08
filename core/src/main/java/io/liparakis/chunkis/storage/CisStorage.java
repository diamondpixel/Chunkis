package io.liparakis.chunkis.storage;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.spi.NbtAdapter;
import io.liparakis.chunkis.storage.codec.CisDecoder;
import io.liparakis.chunkis.storage.codec.CisEncoder;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * High-performance region-based storage system for Chunkis chunk deltas.
 * Features:
 * - Region file format (32x32 chunks per file)
 * - LRU caching of open region files
 * - Thread-safe compression/decompression
 * - Automatic directory structure management
 *
 * @param <B> Block type
 * @param <S> BlockState type
 * @param <P> Property type
 * @param <N> NBT type
 */
public final class CisStorage<B, S, P, N> {
    private static final int REGION_SHIFT = 5;

    /**
     * The root directory where .cis region files are stored.
     */
    private final Path storageDir;

    /**
     * The global block mapping used for this storage instance.
     */
    private final CisMapping<B, S, P> mapping;
    private final BlockStateAdapter<B, S, P> stateAdapter;
    private final NbtAdapter<N> nbtAdapter;
    private final S airState;

    /**
     * LRU cache of open region files.
     */
    private final Object2ObjectLinkedOpenHashMap<RegionKey, RegionFile> regionCache;

    /**
     * Lock for managing access to the region cache.
     */
    private final ReadWriteLock cacheLock = new ReentrantReadWriteLock();

    /**
     * Thread-local compression state to allow concurrent save/load without
     * re-allocation.
     */
    private final ThreadLocal<CompressionContext> compressionContext = ThreadLocal.withInitial(CompressionContext::new);

    /**
     * Creates a new CisStorage instance.
     */
    public CisStorage(Path storageDir, CisMapping<B, S, P> mapping, BlockStateAdapter<B, S, P> stateAdapter,
            NbtAdapter<N> nbtAdapter, S airState) {
        this.storageDir = storageDir;
        this.mapping = mapping;
        this.stateAdapter = stateAdapter;
        this.nbtAdapter = nbtAdapter;
        this.airState = airState;
        this.regionCache = new Object2ObjectLinkedOpenHashMap<>(CisConstants.MAX_CACHED_REGIONS);
    }

    /**
     * Saves a chunk delta to storage.
     * If the delta is empty, the chunk is cleared instead.
     *
     * @param pos   the chunk position
     * @param delta the chunk delta to save
     */
    public void save(CisChunkPos pos, ChunkDelta<S, N> delta) {
        if (delta.isEmpty()) {
            clearChunk(pos);
            return;
        }

        try {
            byte[] rawData = new CisEncoder<>(mapping, stateAdapter, nbtAdapter, airState).encode(delta);
            mapping.flush();

            byte[] compressedData = compressionContext.get().compress(rawData);

            RegionFile regionFile = getRegionFile(pos, true);
            Objects.requireNonNull(regionFile).write(pos, compressedData);

            delta.markSaved();
        } catch (IOException e) {
            io.liparakis.chunkis.Chunkis.LOGGER.error("Failed to save CIS chunk {}", pos, e);
        }
    }

    /**
     * Loads a chunk delta from storage.
     *
     * @param pos the chunk position
     * @return the loaded chunk delta, or an empty delta if not found or on error
     */
    public ChunkDelta<S, N> load(CisChunkPos pos) {
        try {
            RegionFile regionFile = getRegionFile(pos, false);
            if (regionFile == null) {
                return new ChunkDelta<>();
            }
            byte[] compressedData = regionFile.read(pos);

            if (compressedData == null) {
                return new ChunkDelta<>();
            }

            byte[] decompressed = compressionContext.get().decompress(compressedData);

            if (decompressed.length < 8) {
                io.liparakis.chunkis.Chunkis.LOGGER.warn(
                        "Decompressed data too small for chunk {}: {} bytes (compressed: {} bytes) - likely corrupted, deleting",
                        pos, decompressed.length, compressedData.length);
                clearChunk(pos);
                return new ChunkDelta<>();
            }

            return new CisDecoder<>(mapping, stateAdapter, nbtAdapter, airState).decode(decompressed);
        } catch (IOException e) {
            io.liparakis.chunkis.Chunkis.LOGGER.error(
                    "Failed to decode CIS chunk at {} - clearing corrupted data. Error: {}",
                    pos, e.getMessage());
            clearChunk(pos);
            return new ChunkDelta<>();
        } catch (Exception e) {
            io.liparakis.chunkis.Chunkis.LOGGER.error(
                    "Unexpected error loading CIS chunk at {} - clearing data", pos, e);
            clearChunk(pos);
            return new ChunkDelta<>();
        }
    }

    /**
     * Closes all cached region files and cleans up resources.
     */
    public void close() {
        cacheLock.writeLock().lock();
        try {
            for (RegionFile regionFile : regionCache.values()) {
                regionFile.compact(); // Safe synchronized compaction
                regionFile.close();
            }
            regionCache.clear();
        } finally {
            cacheLock.writeLock().unlock();
        }
        compressionContext.remove();
    }

    /**
     * Clears a chunk from storage (writes null data).
     *
     * @param pos the chunk position to clear
     */
    private void clearChunk(CisChunkPos pos) {
        try {
            RegionFile regionFile = getRegionFile(pos, false);
            if (regionFile != null) {
                regionFile.write(pos, null);
            }
        } catch (IOException e) {
            io.liparakis.chunkis.Chunkis.LOGGER.warn("Failed to clear chunk {}", pos, e);
        }
    }

    /**
     * Gets or loads a region file, using LRU caching.
     *
     * @param pos the chunk position used to determine the region
     * @return the region file handler
     * @throws IOException if the file cannot be opened
     */
    private RegionFile getRegionFile(CisChunkPos pos, boolean create) throws IOException {
        RegionKey key = getRegionKey(pos);

        cacheLock.readLock().lock();
        try {
            RegionFile existing = regionCache.get(key);
            if (existing != null) {
                return existing;
            }
        } finally {
            cacheLock.readLock().unlock();
        }

        cacheLock.writeLock().lock();
        try {
            // Double check lock pattern
            RegionFile existing = regionCache.get(key);
            if (existing != null) {
                return existing;
            }

            if (!create) {
                Path regionPath = storageDir.resolve(String.format("r.%d.%d.cis", key.x(), key.z()));
                if (!Files.exists(regionPath)) {
                    return null;
                }
            }

            if (regionCache.size() >= CisConstants.MAX_CACHED_REGIONS) {
                evictOldestRegion();
            }

            RegionFile newFile = new RegionFile(storageDir, key.x(), key.z());
            regionCache.put(key, newFile);
            return newFile;
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    /**
     * Gets the region key for a chunk position.
     * Regions are 32x32 chunks.
     *
     * @param pos the chunk position
     * @return the corresponding region coordinates
     */
    private static RegionKey getRegionKey(CisChunkPos pos) {
        return new RegionKey(pos.x() >> REGION_SHIFT, pos.z() >> REGION_SHIFT);
    }

    /**
     * Evicts the oldest region file from the cache.
     * Triggers background compaction.
     */
    private void evictOldestRegion() {
        RegionFile oldest = regionCache.removeFirst();
        if (oldest != null) {
            oldest.close();
        }
    }
}
