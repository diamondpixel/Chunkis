package io.liparakis.chunkis.storage;

import io.liparakis.chunkis.core.ChunkDelta;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.ChunkPos;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * High-performance region-based storage system for Chunkis chunk deltas.
 * Features:
 * - Region file format (32x32 chunks per file)
 * - LRU caching of open region files
 * - Thread-safe compression/decompression
 * - Automatic directory structure management
 */
public final class CisStorage {
    private static final int REGION_SHIFT = 5;
    private static final int REGION_MASK = 31;
    private static final int CHUNKS_PER_REGION = 1024;
    private static final int HEADER_SIZE = 8192;
    private static final int HEADER_ENTRY_SIZE = 8;
    private static final int COMPRESSION_BUFFER_SIZE = 8192;

    /**
     * The root directory where .cis region files are stored.
     */
    private final Path storageDir;

    /**
     * The global block mapping used for this storage instance.
     */
    private final CisMapping mapping;

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
     * Creates a new CisStorage instance for the given world.
     *
     * @param world the server world to store chunks for
     */
    public CisStorage(ServerWorld world) {
        this.storageDir = getOrCreateDimensionStorage(world);
        this.mapping = loadMapping(storageDir);
        this.regionCache = new Object2ObjectLinkedOpenHashMap<>(CisConstants.MAX_CACHED_REGIONS);

    }

    /**
     * Saves a chunk delta to storage.
     * If the delta is empty, the chunk is cleared instead.
     *
     * @param pos   the chunk position
     * @param delta the chunk delta to save
     */
    public void save(ChunkPos pos, ChunkDelta delta) {
        if (delta.isEmpty()) {
            clearChunk(pos);
            return;
        }

        try {
            byte[] rawData = CisEncoder.encode(delta, mapping);
            mapping.flush();

            byte[] compressedData = compressionContext.get().compress(rawData);

            RegionFile regionFile = getRegionFile(pos);
            regionFile.write(pos, compressedData);

            delta.markSaved();
        } catch (IOException e) {
            io.liparakis.chunkis.ChunkisMod.LOGGER.error("Failed to save CIS chunk {}", pos, e);
        }
    }

    /**
     * Loads a chunk delta from storage.
     *
     * @param pos the chunk position
     * @return the loaded chunk delta, or an empty delta if not found or on error
     */
    public ChunkDelta load(ChunkPos pos) {
        try {
            RegionFile regionFile = getRegionFile(pos);
            byte[] compressedData = regionFile.read(pos);

            if (compressedData == null) {
                return new ChunkDelta();
            }

            byte[] decompressed = compressionContext.get().decompress(compressedData);

            if (decompressed.length < 8) {
                io.liparakis.chunkis.ChunkisMod.LOGGER.warn(
                        "Decompressed data too small for chunk {}: {} bytes (compressed: {} bytes) - likely corrupted, deleting",
                        pos, decompressed.length, compressedData.length);
                clearChunk(pos);
                return new ChunkDelta();
            }

            return new CisDecoder().decode(decompressed, mapping);
        } catch (IOException e) {
            io.liparakis.chunkis.ChunkisMod.LOGGER.error(
                    "Failed to decode CIS chunk at {} - clearing corrupted data. Error: {}",
                    pos, e.getMessage());
            clearChunk(pos);
            return new ChunkDelta();
        } catch (Exception e) {
            io.liparakis.chunkis.ChunkisMod.LOGGER.error(
                    "Unexpected error loading CIS chunk at {} - clearing data", pos, e);
            clearChunk(pos);
            return new ChunkDelta();
        }
    }

    /**
     * Closes all cached region files and cleans up resources.
     */
    public void close() {
        io.liparakis.chunkis.ChunkisMod.LOGGER.info("Stopping Chunkis Storage - Compacting {} active regions...",
                regionCache.size());

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
    private void clearChunk(ChunkPos pos) {
        try {
            getRegionFile(pos).write(pos, null);
        } catch (IOException e) {
            io.liparakis.chunkis.ChunkisMod.LOGGER.warn("Failed to clear chunk {}", pos, e);
        }
    }

    /**
     * Gets or loads a region file, using LRU caching.
     *
     * @param pos the chunk position used to determine the region
     * @return the region file handler
     * @throws IOException if the file cannot be opened
     */
    private RegionFile getRegionFile(ChunkPos pos) throws IOException {
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

            if (regionCache.size() >= CisConstants.MAX_CACHED_REGIONS) {
                evictOldestRegion();
            }

            RegionFile newFile = new RegionFile(storageDir, key.x, key.z);
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
    private static RegionKey getRegionKey(ChunkPos pos) {
        return new RegionKey(pos.x >> REGION_SHIFT, pos.z >> REGION_SHIFT);
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

    /**
     * Gets or creates the dimension-specific storage directory.
     * Handles overworld, nether, the end, and custom dimensions.
     *
     * @param world the server world
     * @return the path to the region storage directory
     */
    private static Path getOrCreateDimensionStorage(ServerWorld world) {
        Path baseDir = world.getServer().getSavePath(WorldSavePath.ROOT);
        String dimId = world.getRegistryKey().getValue().getPath();

        if (!"overworld".equals(dimId)) {
            baseDir = baseDir.resolve("dimensions")
                    .resolve(world.getRegistryKey().getValue().getNamespace())
                    .resolve(dimId);
        }

        Path storageDir = baseDir.resolve("chunkis").resolve("regions");

        try {
            Files.createDirectories(storageDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create Chunkis storage directory", e);
        }

        return storageDir;
    }

    /**
     * Loads the global block mapping for this dimension.
     *
     * @param storageDir the region storage directory
     * @return the mapping instance
     */
    private CisMapping loadMapping(Path storageDir) {
        Path mappingFile = storageDir.getParent().resolve("global_ids.json");
        try {
            return new CisMapping(mappingFile);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load Chunkis mappings", e);
        }
    }

    /**
     * Region coordinate key for caching.
     *
     * @param x the region's X coordinate
     * @param z the region's Z coordinate
     */
    private record RegionKey(int x, int z) {
        @Override
        public @NotNull String toString() {
            return "r." + x + "." + z;
        }

        @Override
        public int hashCode() {
            return 31 * x + z;
        }
    }

    /**
     * Thread-local compression context to avoid allocations and synchronization.
     */
    private static final class CompressionContext {
        private final Deflater deflater = new Deflater(CisConstants.COMPRESSION_LEVEL);
        private final Inflater inflater = new Inflater();
        private final byte[] buffer = new byte[COMPRESSION_BUFFER_SIZE];
        private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(COMPRESSION_BUFFER_SIZE);

        /**
         * Compresses data using DEFLATE.
         *
         * @param data the raw data
         * @return the compressed data
         */
        byte[] compress(byte[] data) {
            deflater.reset();
            deflater.setInput(data);
            deflater.finish();
            outputStream.reset();

            while (!deflater.finished()) {
                int bytesCompressed = deflater.deflate(buffer);
                outputStream.write(buffer, 0, bytesCompressed);
            }

            return outputStream.toByteArray();
        }

        /**
         * Decompresses data using INFLATE.
         *
         * @param data the compressed data
         * @return the raw data
         * @throws Exception if inflation fails
         */
        byte[] decompress(byte[] data) throws Exception {
            inflater.reset();
            inflater.setInput(data);
            outputStream.reset();

            while (!inflater.finished()) {
                int bytesDecompressed = inflater.inflate(buffer);
                if (bytesDecompressed == 0 && inflater.needsInput()) {
                    break;
                }
                outputStream.write(buffer, 0, bytesDecompressed);
            }

            return outputStream.toByteArray();
        }
    }

    /**
     * Region file handler for 32x32 chunks.
     * Uses a simple offset/length header for chunk locations.
     */
    private static final class RegionFile implements AutoCloseable {
        private final Path path;
        private FileChannel channel;
        final int[] offsets = new int[CHUNKS_PER_REGION]; // Package-private for compactor
        final int[] lengths = new int[CHUNKS_PER_REGION];
        private final ByteBuffer headerBuffer = ByteBuffer.allocateDirect(HEADER_ENTRY_SIZE);
        private boolean dirty = false;

        /**
         * Opens or creates a region file.
         *
         * @param dir     the parent directory
         * @param regionX region X coordinate
         * @param regionZ region Z coordinate
         * @throws IOException if the file cannot be opened
         */
        RegionFile(Path dir, int regionX, int regionZ) throws IOException {
            this.path = dir.resolve(String.format("r.%d.%d.cis", regionX, regionZ));
            this.channel = FileChannel.open(path,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE);

            if (channel.size() < HEADER_SIZE) {
                initializeNewRegion();
            } else {
                loadHeader();
            }
        }

        /**
         * Initializes a new region file with an empty header.
         */
        private void initializeNewRegion() throws IOException {
            channel.write(ByteBuffer.allocate(HEADER_SIZE), 0);
        }

        /**
         * Loads the header from an existing region file.
         */
        private void loadHeader() throws IOException {
            ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
            channel.read(header, 0);
            header.flip();

            for (int i = 0; i < CHUNKS_PER_REGION; i++) {
                offsets[i] = header.getInt();
                lengths[i] = header.getInt();
            }
        }

        /**
         * Reads chunk data from the region file.
         *
         * @param pos the chunk position
         * @return the raw bytes, or {@code null} if the chunk is not present
         * @throws IOException if a read error occurs
         */
        synchronized byte[] read(ChunkPos pos) throws IOException {
            int index = getChunkIndex(pos);

            if (offsets[index] == 0) {
                return null;
            }

            ByteBuffer buffer = ByteBuffer.allocate(lengths[index]);
            channel.read(buffer, offsets[index]);
            return buffer.array();
        }

        /**
         * Writes chunk data to the region file.
         *
         * @param pos  the chunk position
         * @param data the data to write, or {@code null} to clear the chunk
         * @throws IOException if a write error occurs
         */
        synchronized void write(ChunkPos pos, byte[] data) throws IOException {
            int index = getChunkIndex(pos);
            int dataLength = (data == null) ? 0 : data.length;

            int offset = calculateWriteOffset(index, dataLength);

            if (dataLength > 0) {
                channel.write(ByteBuffer.wrap(data), offset);
            }

            updateHeader(index, offset, dataLength);
            dirty = true;
        }

        /**
         * Calculates the offset where data should be written.
         * Reuses existing space if it fits, otherwise appends to the end.
         *
         * @param index      the chunk index
         * @param dataLength the length of the data to be written
         * @return the file offset
         * @throws IOException if a file error occurs
         */
        private int calculateWriteOffset(int index, int dataLength) throws IOException {
            if (dataLength <= lengths[index] && offsets[index] != 0) {
                return offsets[index];
            }
            return (int) channel.size();
        }

        /**
         * Updates the header entry for a chunk in memory and on disk.
         *
         * @param index  the chunk index
         * @param offset the new offset
         * @param length the new length
         * @throws IOException if a write error occurs
         */
        private void updateHeader(int index, int offset, int length) throws IOException {
            offsets[index] = (length == 0) ? 0 : offset;
            lengths[index] = length;

            headerBuffer.clear();
            headerBuffer.putInt(offsets[index]);
            headerBuffer.putInt(lengths[index]);
            headerBuffer.flip();

            channel.write(headerBuffer, (long) index * HEADER_ENTRY_SIZE);
        }

        /**
         * Gets the index of a chunk within the region file header (0-1023).
         *
         * @param pos the chunk position
         * @return the header index
         */
        private static int getChunkIndex(ChunkPos pos) {
            return (pos.x & REGION_MASK) + (pos.z & REGION_MASK) * 32;
        }

        /**
         * Flushes pending writes to disk.
         */
        void flush() {
            if (dirty) {
                try {
                    if (channel.isOpen()) {
                        channel.force(false);
                    }
                    dirty = false;
                } catch (IOException e) {
                    io.liparakis.chunkis.ChunkisMod.LOGGER.warn("Failed to flush region file", e);
                }
            }
        }

        /**
         * Compacts the region file by rewriting it contiguously.
         * This operation is synchronized to prevent concurrent writes.
         */
        synchronized void compact() {
            try {
                flush();
                long initialSize = channel.size();
                Path tempPath = path.resolveSibling(path.getFileName().toString() + ".tmp");

                try (FileChannel dest = FileChannel.open(tempPath, StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE)) {

                    // placeholder header
                    dest.write(ByteBuffer.allocate(HEADER_SIZE));

                    int currentOffset = HEADER_SIZE;
                    ByteBuffer newHeader = ByteBuffer.allocate(HEADER_SIZE);

                    for (int i = 0; i < CHUNKS_PER_REGION; i++) {
                        if (offsets[i] != 0 && lengths[i] > 0) {
                            try {
                                ByteBuffer chunkData = ByteBuffer.allocate(lengths[i]);
                                channel.read(chunkData, offsets[i]);
                                chunkData.flip();
                                dest.write(chunkData);

                                newHeader.putInt(currentOffset);
                                newHeader.putInt(lengths[i]);
                                currentOffset += lengths[i];
                            } catch (IOException e) {
                                newHeader.putInt(0);
                                newHeader.putInt(0);
                            }
                        } else {
                            newHeader.putInt(0);
                            newHeader.putInt(0);
                        }
                    }

                    newHeader.flip();
                    dest.position(0);
                    dest.write(newHeader);
                    dest.force(true);
                }

                // Close current channel to allow swap
                channel.close();

                // Swap files
                Files.move(tempPath, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                // Re-open channel
                channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE,
                        StandardOpenOption.CREATE);

                // Refresh header in memory
                loadHeader();

                long finalSize = channel.size();
                if (finalSize < initialSize) {
                    io.liparakis.chunkis.ChunkisMod.LOGGER.info("Compacted {} ({} -> {} bytes)", path.getFileName(),
                            initialSize, finalSize);
                }

            } catch (IOException e) {
                io.liparakis.chunkis.ChunkisMod.LOGGER.error("Failed to compact region {}", path, e);
                // Try to reopen if we failed closed
                if (!channel.isOpen()) {
                    try {
                        channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE,
                                StandardOpenOption.CREATE);
                    } catch (IOException ex) {
                        io.liparakis.chunkis.ChunkisMod.LOGGER
                                .error("CRITICAL: Failed to reopen region after failed compaction", ex);
                    }
                }
            }
        }

        /**
         * Closes the region file.
         */
        @Override
        public void close() {
            flush();
            try {
                channel.close();
            } catch (IOException e) {
                io.liparakis.chunkis.ChunkisMod.LOGGER.warn("Failed to close region file", e);
            }
        }
    }
}