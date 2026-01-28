package io.liparakis.chunkis.storage;

import io.liparakis.chunkis.core.ChunkDelta;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public final class CisStorage {
    private final Path storageDir;
    private final CisMapping mapping;
    private final Object2ObjectLinkedOpenHashMap<RegionKey, RegionFile> regionCache;
    private final ReadWriteLock cacheLock = new ReentrantReadWriteLock();
    private final ThreadLocal<CompressionContext> compressionContext = ThreadLocal.withInitial(CompressionContext::new);

    public CisStorage(ServerWorld world) {
        this.storageDir = getOrCreateDimensionStorage(world);
        this.mapping = loadMapping(storageDir);
        // Initialize LRU cache
        this.regionCache = new Object2ObjectLinkedOpenHashMap<>(CisConstants.MAX_CACHED_REGIONS);
    }

    // --- Core API ---

    public void save(ChunkPos pos, ChunkDelta delta) {
        if (delta.isEmpty()) {
            clearChunk(pos);
            return;
        }
        try {
            byte[] rawData = CisEncoder.encode(delta, mapping);

            // Ensure mappings are flushed AFTER encoding (which might register new IDs)
            mapping.flush();

            byte[] compressedData = compressionContext.get().compress(rawData);

            RegionFile rf = getRegionFile(pos);
            rf.write(pos, compressedData);

            delta.markSaved();
        } catch (IOException e) {
            io.liparakis.chunkis.ChunkisMod.LOGGER.error("Failed to save CIS chunk {}", pos, e);
        }
    }

    public ChunkDelta load(ChunkPos pos) {
        try {
            RegionFile rf = getRegionFile(pos);
            byte[] compressedData = rf.read(pos);
            if (compressedData == null)
                return new ChunkDelta();

            byte[] decompressed = compressionContext.get().decompress(compressedData);
            return new CisDecoder().decode(decompressed, mapping);
        } catch (Exception e) {
            io.liparakis.chunkis.ChunkisMod.LOGGER.error("Failed to load/decode CIS chunk at " + pos, e);
            return new ChunkDelta();
        }
    }

    public void flush() {
        cacheLock.readLock().lock();
        try {
            for (RegionFile rf : regionCache.values()) {
                rf.flush();
            }
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    public void close() {
        cacheLock.writeLock().lock();
        try {
            for (RegionFile rf : regionCache.values()) {
                rf.close();
            }
            regionCache.clear();
        } finally {
            cacheLock.writeLock().unlock();
        }
        compressionContext.remove();
    }

    private void clearChunk(ChunkPos pos) {
        try {
            getRegionFile(pos).write(pos, null);
        } catch (IOException ignored) {
        }
    }

    // --- Internal Logic ---

    private RegionFile getRegionFile(ChunkPos pos) throws IOException {
        int rX = pos.x >> 5;
        int rZ = pos.z >> 5;
        RegionKey key = new RegionKey(rX, rZ);

        cacheLock.readLock().lock();
        try {
            RegionFile existing = regionCache.get(key);
            if (existing != null)
                return existing;
        } finally {
            cacheLock.readLock().unlock();
        }

        cacheLock.writeLock().lock();
        try {
            RegionFile existing = regionCache.get(key);
            if (existing != null)
                return existing;

            if (regionCache.size() >= CisConstants.MAX_CACHED_REGIONS) {
                regionCache.removeFirst().close();
            }

            RegionFile newFile = new RegionFile(storageDir, rX, rZ);
            regionCache.put(key, newFile);
            return newFile;
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    // --- Restored RegionKey ---

    private record RegionKey(int x, int z) {
        @Override
        public int hashCode() {
            return 31 * x + z;
        }
    }

    // --- Restored CompressionContext ---

    private static final class CompressionContext {
        private final Deflater deflater = new Deflater(CisConstants.COMPRESSION_LEVEL);
        private final Inflater inflater = new Inflater();
        private final byte[] buffer = new byte[8192];
        private final ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);

        byte[] compress(byte[] data) {
            deflater.reset();
            deflater.setInput(data);
            deflater.finish();
            bos.reset();
            while (!deflater.finished()) {
                int n = deflater.deflate(buffer);
                bos.write(buffer, 0, n);
            }
            return bos.toByteArray();
        }

        byte[] decompress(byte[] data) throws Exception {
            inflater.reset();
            inflater.setInput(data);
            bos.reset();
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && inflater.needsInput())
                    break;
                bos.write(buffer, 0, n);
            }
            return bos.toByteArray();
        }
    }

    // --- Optimized RegionFile ---

    private static final class RegionFile {
        private final FileChannel channel;
        private final int[] offsets = new int[1024];
        private final int[] lengths = new int[1024];
        private final ByteBuffer headerScratch = ByteBuffer.allocateDirect(8);
        private boolean dirty = false;

        RegionFile(Path dir, int rX, int rZ) throws IOException {
            Path path = dir.resolve("r." + rX + "." + rZ + ".cis");
            this.channel = FileChannel.open(path,
                    StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE);

            if (channel.size() < 8192) {
                channel.write(ByteBuffer.allocate(8192), 0);
            } else {
                ByteBuffer header = ByteBuffer.allocate(8192);
                channel.read(header, 0);
                header.flip();
                for (int i = 0; i < 1024; i++) {
                    offsets[i] = header.getInt();
                    lengths[i] = header.getInt();
                }
            }
        }

        synchronized byte[] read(ChunkPos pos) throws IOException {
            int idx = getIndex(pos);
            if (offsets[idx] == 0)
                return null;

            ByteBuffer buf = ByteBuffer.allocate(lengths[idx]);
            channel.read(buf, offsets[idx]);
            return buf.array();
        }

        synchronized void write(ChunkPos pos, byte[] data) throws IOException {
            int idx = getIndex(pos);
            int len = (data == null) ? 0 : data.length;
            int offset = (len <= lengths[idx] && offsets[idx] != 0) ? offsets[idx] : (int) channel.size();

            if (len > 0) {
                channel.write(ByteBuffer.wrap(data), offset);
            }

            offsets[idx] = (len == 0) ? 0 : offset;
            lengths[idx] = len;

            headerScratch.clear();
            headerScratch.putInt(offsets[idx]).putInt(lengths[idx]).flip();
            channel.write(headerScratch, (long) idx * 8);
            dirty = true;
        }

        private int getIndex(ChunkPos pos) {
            return (pos.x & 31) + (pos.z & 31) * 32;
        }

        void flush() {
            if (dirty) {
                try {
                    channel.force(false);
                    dirty = false;
                } catch (IOException ignored) {
                }
            }
        }

        void close() {
            flush();
            try {
                channel.close();
            } catch (IOException ignored) {
            }
        }
    }

    // --- Restored Directory Logic ---

    private static Path getOrCreateDimensionStorage(ServerWorld world) {
        Path baseDir = world.getServer().getSavePath(WorldSavePath.ROOT);
        String dimId = world.getRegistryKey().getValue().getPath();
        if (!"overworld".equals(dimId)) {
            baseDir = baseDir.resolve("dimensions").resolve(world.getRegistryKey().getValue().getNamespace())
                    .resolve(dimId);
        }
        Path path = baseDir.resolve("chunkis").resolve("regions");
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return path;
    }

    private CisMapping loadMapping(Path storageDir) {
        Path mappingFile = storageDir.getParent().resolve("global_ids.json");
        try {
            return new CisMapping(mappingFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}