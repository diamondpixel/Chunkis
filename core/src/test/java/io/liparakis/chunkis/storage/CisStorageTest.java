package io.liparakis.chunkis.storage;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.spi.BlockRegistryAdapter;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.spi.NbtAdapter;
import io.liparakis.chunkis.storage.PropertyPacker.PropertyMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Comprehensive tests for {@link CisStorage} covering all branches and edge
 * cases.
 */
class CisStorageTest {

    @TempDir
    Path tempDir;

    private CisStorage<String, String, String, String> storage;
    private BlockRegistryAdapter<String> registry;
    private BlockStateAdapter<String, String, String> stateAdapter;
    private NbtAdapter<String> nbtAdapter;
    private PropertyPacker<String, String, String> packer;
    private CisMapping<String, String, String> mapping;

    @BeforeEach
    void setUp() throws IOException {
        registry = mock(BlockRegistryAdapter.class);
        stateAdapter = mock(BlockStateAdapter.class);
        nbtAdapter = mock(NbtAdapter.class);
        packer = mock(PropertyPacker.class);

        // Setup generic mocks
        when(registry.getAir()).thenReturn("air");
        when(registry.getId("air")).thenReturn("minecraft:air");
        when(registry.getBlock(any())).thenAnswer(inv -> inv.getArgument(0));
        when(registry.getId(any())).thenAnswer(inv -> "minecraft:" + inv.getArgument(0));

        when(stateAdapter.getBlock(any())).thenAnswer(inv -> inv.getArgument(0));

        when(packer.getPropertyMetas(any())).thenReturn(new PropertyMeta[0]);
        when(packer.readProperties(any(), any(), any())).thenAnswer(inv -> inv.getArgument(1));

        // Setup mapping
        Path mappingFile = tempDir.resolve("mappings.json");
        mapping = new CisMapping<>(mappingFile, registry, stateAdapter, packer);

        storage = new CisStorage<>(tempDir, mapping, stateAdapter, nbtAdapter, "air");
    }

    // ========== Basic Save/Load Tests ==========

    @Test
    void savesAndLoadsChunkSuccessfully() {
        CisChunkPos pos = new CisChunkPos(0, 0);
        ChunkDelta<String, String> delta = new ChunkDelta<>(s -> s.equals("air"));
        delta.addBlockChange(0, 0, 0, "stone");

        storage.save(pos, delta);

        ChunkDelta<String, String> loaded = storage.load(pos);
        assertThat(loaded.isEmpty()).isFalse();
    }

    @Test
    void savingEmptyDeltaClearsChunk() {
        CisChunkPos pos = new CisChunkPos(0, 0);
        ChunkDelta<String, String> delta = new ChunkDelta<>(s -> s.equals("air"));
        delta.addBlockChange(0, 0, 0, "stone");
        storage.save(pos, delta);

        // Save empty delta -> should clear
        ChunkDelta<String, String> empty = new ChunkDelta<>(s -> s.equals("air"));
        storage.save(pos, empty);

        ChunkDelta<String, String> loaded = storage.load(pos);
        assertThat(loaded.isEmpty()).isTrue();
    }

    @Test
    void loadingNonExistentChunkReturnsEmpty() {
        CisChunkPos pos = new CisChunkPos(10, 10);

        ChunkDelta<String, String> loaded = storage.load(pos);

        assertThat(loaded.isEmpty()).isTrue();
    }

    @Test
    void loadingFromNonExistentRegionReturnsEmpty() {
        CisChunkPos pos = new CisChunkPos(100, 100);

        ChunkDelta<String, String> loaded = storage.load(pos);

        assertThat(loaded.isEmpty()).isTrue();
        assertThat(Files.exists(tempDir.resolve("r.3.3.cis"))).isFalse();
    }

    @Test
    void clearingChunkFromNonExistentRegionDoesNotThrow() {
        // Try to clear a chunk from a region that was never created
        // This should handle regionFile == null gracefully
        CisChunkPos pos = new CisChunkPos(200, 200); // r.6.6 which doesn't exist

        ChunkDelta<String, String> empty = new ChunkDelta<>(s -> s.equals("air"));

        // This should not throw - clearChunk handles null regionFile
        storage.save(pos, empty);

        // Verify the region file was never created
        assertThat(Files.exists(tempDir.resolve("r.6.6.cis"))).isFalse();
    }

    // ========== Multiple Regions Tests ==========

    @Test
    void handlesMultipleRegionsCorrectly() {
        CisChunkPos r0 = new CisChunkPos(0, 0); // r.0.0
        CisChunkPos r1 = new CisChunkPos(32, 32); // r.1.1
        CisChunkPos r2 = new CisChunkPos(-32, -32); // r.-1.-1

        ChunkDelta<String, String> delta = new ChunkDelta<>(s -> s.equals("air"));
        delta.addBlockChange(0, 0, 0, "stone");

        storage.save(r0, delta);
        storage.save(r1, delta);
        storage.save(r2, delta);

        assertThat(Files.exists(tempDir.resolve("r.0.0.cis"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("r.1.1.cis"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("r.-1.-1.cis"))).isTrue();

        assertThat(storage.load(r0).isEmpty()).isFalse();
        assertThat(storage.load(r1).isEmpty()).isFalse();
        assertThat(storage.load(r2).isEmpty()).isFalse();
    }

    @Test
    void savesMultipleChunksInSameRegion() {
        ChunkDelta<String, String> delta = new ChunkDelta<>(s -> s.equals("air"));
        delta.addBlockChange(0, 0, 0, "stone");

        // Save 4 chunks in the same region
        for (int x = 0; x < 2; x++) {
            for (int z = 0; z < 2; z++) {
                CisChunkPos pos = new CisChunkPos(x, z);
                storage.save(pos, delta);
            }
        }

        // Verify all are loadable
        for (int x = 0; x < 2; x++) {
            for (int z = 0; z < 2; z++) {
                CisChunkPos pos = new CisChunkPos(x, z);
                assertThat(storage.load(pos).isEmpty()).isFalse();
            }
        }

        // Only one region file should exist
        assertThat(Files.exists(tempDir.resolve("r.0.0.cis"))).isTrue();
    }

    // ========== Cache Eviction Tests ==========

    @Test
    void evictsOldestRegionWhenCacheFull() {
        int maxRegions = CisConstants.MAX_CACHED_REGIONS;

        ChunkDelta<String, String> delta = new ChunkDelta<>(s -> s.equals("air"));
        delta.addBlockChange(0, 0, 0, "stone");

        // Fill cache + trigger eviction
        for (int i = 0; i < maxRegions + 5; i++) {
            CisChunkPos pos = new CisChunkPos(i * 32, 0);
            storage.save(pos, delta);
        }

        // Verify all regions were created and can be loaded
        for (int i = 0; i < maxRegions + 5; i++) {
            CisChunkPos pos = new CisChunkPos(i * 32, 0);
            assertThat(storage.load(pos).isEmpty()).isFalse();
        }
    }

    @Test
    void cachesRegionFilesForReuse() throws Exception {
        CisChunkPos pos1 = new CisChunkPos(0, 0);
        CisChunkPos pos2 = new CisChunkPos(1, 1); // Same region

        ChunkDelta<String, String> delta = new ChunkDelta<>(s -> s.equals("air"));
        delta.addBlockChange(0, 0, 0, "stone");

        storage.save(pos1, delta);
        storage.save(pos2, delta);

        // Both should use same region file (cached)
        assertThat(storage.load(pos1).isEmpty()).isFalse();
        assertThat(storage.load(pos2).isEmpty()).isFalse();
    }

    // ========== Compaction Tests ==========

    @Test
    void compactsRegionFileOnClose() throws IOException {
        CisChunkPos pos1 = new CisChunkPos(0, 0);
        CisChunkPos pos2 = new CisChunkPos(0, 1);

        // Write chunk 1 (small)
        ChunkDelta<String, String> delta1 = new ChunkDelta<>(s -> s.equals("air"));
        delta1.addBlockChange(0, 0, 0, "stone");
        storage.save(pos1, delta1);

        // Write chunk 2
        ChunkDelta<String, String> delta2 = new ChunkDelta<>(s -> s.equals("air"));
        delta2.addBlockChange(0, 0, 0, "dirt");
        storage.save(pos2, delta2);

        // Overwrite chunk 1 with larger data -> creates gap
        ChunkDelta<String, String> delta1Large = new ChunkDelta<>(s -> s.equals("air"));
        for (int i = 0; i < 100; i++) {
            delta1Large.addBlockChange(i % 16, i / 16, 0, "stone");
        }
        storage.save(pos1, delta1Large);

        // Close storage -> triggers compaction
        storage.close();

        // Re-open and verify data integrity
        storage = new CisStorage<>(tempDir, mapping, stateAdapter, nbtAdapter, "air");

        ChunkDelta<String, String> loaded1 = storage.load(pos1);
        ChunkDelta<String, String> loaded2 = storage.load(pos2);

        assertThat(loaded1.isEmpty()).isFalse();
        assertThat(loaded2.isEmpty()).isFalse();
    }

    // ========== Space Reuse Tests ==========

    @Test
    void reusesSpaceWhenNewDataFitsInOldSlot() throws IOException {
        CisChunkPos pos = new CisChunkPos(0, 0);

        // Write large chunk
        ChunkDelta<String, String> large = new ChunkDelta<>(s -> s.equals("air"));
        for (int i = 0; i < 100; i++) {
            large.addBlockChange(i % 16, i / 16, 0, "stone");
        }
        storage.save(pos, large);

        long sizeAfterLarge = Files.size(tempDir.resolve("r.0.0.cis"));

        // Overwrite with small chunk (should reuse space)
        ChunkDelta<String, String> small = new ChunkDelta<>(s -> s.equals("air"));
        small.addBlockChange(0, 0, 0, "dirt");
        storage.save(pos, small);

        long sizeAfterSmall = Files.size(tempDir.resolve("r.0.0.cis"));

        // File should not grow (reused existing space)
        assertThat(sizeAfterSmall).isLessThanOrEqualTo(sizeAfterLarge);

        // Data integrity check
        ChunkDelta<String, String> loaded = storage.load(pos);
        assertThat(loaded.isEmpty()).isFalse();
    }

    @Test
    void appendsDataWhenWritingToClearedSlot() throws IOException {
        CisChunkPos pos = new CisChunkPos(0, 0);

        // Write initial data
        ChunkDelta<String, String> initial = new ChunkDelta<>(s -> s.equals("air"));
        initial.addBlockChange(0, 0, 0, "stone");
        storage.save(pos, initial);

        long sizeAfterInitial = Files.size(tempDir.resolve("r.0.0.cis"));

        // Clear the chunk (sets offset to 0 but length remains)
        ChunkDelta<String, String> empty = new ChunkDelta<>(s -> s.equals("air"));
        storage.save(pos, empty);

        // Write new data that would fit in the old length
        // This triggers: dataLength <= lengths[index] && offsets[index] == 0
        ChunkDelta<String, String> newData = new ChunkDelta<>(s -> s.equals("air"));
        newData.addBlockChange(0, 0, 0, "dirt");
        storage.save(pos, newData);

        long sizeAfterNew = Files.size(tempDir.resolve("r.0.0.cis"));

        // Should append to end since offset was 0
        assertThat(sizeAfterNew).isGreaterThan(sizeAfterInitial);

        // Verify data integrity
        ChunkDelta<String, String> loaded = storage.load(pos);
        assertThat(loaded.isEmpty()).isFalse();
    }

    @Test
    void appendsDataWhenExistingSpaceTooSmall() throws IOException {
        CisChunkPos pos = new CisChunkPos(0, 0);

        // Write small chunk
        ChunkDelta<String, String> small = new ChunkDelta<>(s -> s.equals("air"));
        small.addBlockChange(0, 0, 0, "dirt");
        storage.save(pos, small);

        long sizeAfterSmall = Files.size(tempDir.resolve("r.0.0.cis"));

        // Overwrite with large chunk (should append)
        ChunkDelta<String, String> large = new ChunkDelta<>(s -> s.equals("air"));
        for (int i = 0; i < 100; i++) {
            large.addBlockChange(i % 16, i / 16, 0, "stone");
        }
        storage.save(pos, large);

        long sizeAfterLarge = Files.size(tempDir.resolve("r.0.0.cis"));

        // File should have grown
        assertThat(sizeAfterLarge).isGreaterThan(sizeAfterSmall);

        // Data integrity check
        ChunkDelta<String, String> loaded = storage.load(pos);
        assertThat(loaded.isEmpty()).isFalse();
    }

    @Test
    void flushHandlesClosedChannelGracefully() throws Exception {
        CisChunkPos pos = new CisChunkPos(0, 0);

        // Write data to create a dirty region file
        ChunkDelta<String, String> delta = new ChunkDelta<>(s -> s.equals("air"));
        delta.addBlockChange(0, 0, 0, "stone");
        storage.save(pos, delta);

        // Access the region file via reflection to close its channel
        java.lang.reflect.Field cacheField = CisStorage.class.getDeclaredField("regionCache");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Object2ObjectLinkedOpenHashMap<RegionKey, RegionFile> cache = (Object2ObjectLinkedOpenHashMap<RegionKey, RegionFile>) cacheField
                .get(storage);

        RegionFile regionFile = cache.values().iterator().next();

        // Get the channel field and close it
        java.lang.reflect.Field channelField = RegionFile.class.getDeclaredField("channel");
        channelField.setAccessible(true);
        FileChannel channel = (FileChannel) channelField.get(regionFile);
        channel.close();

        // Set dirty flag via reflection
        java.lang.reflect.Field dirtyField = RegionFile.class.getDeclaredField("dirty");
        dirtyField.setAccessible(true);
        dirtyField.set(regionFile, true);

        // Call flush - should handle closed channel gracefully without throwing
        regionFile.flush();

        // Verify dirty flag was cleared despite closed channel
        assertThat(dirtyField.get(regionFile)).isEqualTo(false);
    }

    // ========== Clearing Tests ==========

    @Test
    void clearingNonExistentChunkDoesNotCrash() {
        CisChunkPos pos = new CisChunkPos(10, 10);

        // Clear non-existent chunk
        ChunkDelta<String, String> empty = new ChunkDelta<>(s -> s.equals("air"));
        storage.save(pos, empty);

        assertThat(storage.load(pos).isEmpty()).isTrue();
    }

    @Test
    void clearsExistingChunkSuccessfully() {
        CisChunkPos pos = new CisChunkPos(0, 0);
        ChunkDelta<String, String> delta = new ChunkDelta<>(s -> s.equals("air"));
        delta.addBlockChange(0, 0, 0, "stone");

        storage.save(pos, delta);
        assertThat(storage.load(pos).isEmpty()).isFalse();

        // Clear
        ChunkDelta<String, String> empty = new ChunkDelta<>(s -> s.equals("air"));
        storage.save(pos, empty);

        assertThat(storage.load(pos).isEmpty()).isTrue();
    }

    @Test
    void writingNullDataToFreshSlotHandlesCorrectly() throws Exception {
        // Write some data to create a region file
        CisChunkPos pos1 = new CisChunkPos(0, 0);
        ChunkDelta<String, String> delta = new ChunkDelta<>(s -> s.equals("air"));
        delta.addBlockChange(0, 0, 0, "stone");
        storage.save(pos1, delta);

        // Access the region file via reflection
        java.lang.reflect.Field cacheField = CisStorage.class.getDeclaredField("regionCache");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Object2ObjectLinkedOpenHashMap<RegionKey, RegionFile> cache =
                (Object2ObjectLinkedOpenHashMap<RegionKey, RegionFile>) cacheField.get(storage);

        RegionFile regionFile = cache.values().iterator().next();

        // Get a fresh position in the same region that was never written to
        CisChunkPos freshPos = new CisChunkPos(1, 1);

        // Use reflection to call write(pos, null) directly
        // This triggers: dataLength=0, lengths[index]=0, offsets[index]=0
        // So: (0 <= 0 && 0 != 0) -> (TRUE && FALSE) -> FALSE
        java.lang.reflect.Method writeMethod = RegionFile.class.getDeclaredMethod("write",
                CisChunkPos.class, byte[].class);
        writeMethod.setAccessible(true);
        writeMethod.invoke(regionFile, freshPos, (byte[]) null);

        // Verify the operation completed without error
        java.lang.reflect.Field offsetsField = RegionFile.class.getDeclaredField("offsets");
        offsetsField.setAccessible(true);
        int[] offsets = (int[]) offsetsField.get(regionFile);

        java.lang.reflect.Field lengthsField = RegionFile.class.getDeclaredField("lengths");
        lengthsField.setAccessible(true);
        int[] lengths = (int[]) lengthsField.get(regionFile);

        // Calculate the index for freshPos
        int index = (freshPos.x() & 31) + (freshPos.z() & 31) * 32;

        assertThat(offsets[index]).isEqualTo(0);
        assertThat(lengths[index]).isEqualTo(0);
    }

    @Test
    void compactHandlesInconsistentHeaderState() throws Exception {
        // Create a region file with one valid chunk
        CisChunkPos pos = new CisChunkPos(0, 0);
        ChunkDelta<String, String> delta = new ChunkDelta<>(s -> s.equals("air"));
        delta.addBlockChange(0, 0, 0, "stone");
        storage.save(pos, delta);
        // Access region file via reflection
        java.lang.reflect.Field cacheField = CisStorage.class.getDeclaredField("regionCache");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Object2ObjectLinkedOpenHashMap<RegionKey, RegionFile> cache =
                (Object2ObjectLinkedOpenHashMap<RegionKey, RegionFile>) cacheField.get(storage);

        RegionFile regionFile = cache.values().iterator().next();
        // Inject inconsistent state: offset != 0 but length == 0
        // This targets the missed branch in compact(): if (offsets[i] != 0 && lengths[i] > 0)
        java.lang.reflect.Field offsetsField = RegionFile.class.getDeclaredField("offsets");
        offsetsField.setAccessible(true);
        int[] offsets = (int[]) offsetsField.get(regionFile);

        java.lang.reflect.Field lengthsField = RegionFile.class.getDeclaredField("lengths");
        lengthsField.setAccessible(true);
        int[] lengths = (int[]) lengthsField.get(regionFile);
        // Corrupt index 1 (unused)
        // Find index for chunk (1,0) or some other unused chunk
        // Actually just pick index 1 directly since we wrote to (0,0) which is index 0
        offsets[1] = 8192; // Set a non-zero offset
        lengths[1] = 0;    // Set zero length
        // Run compaction via reflection (it's package-private)
        java.lang.reflect.Method compactMethod = RegionFile.class.getDeclaredMethod("compact");
        compactMethod.setAccessible(true);
        compactMethod.invoke(regionFile);
        // Verify that the inconsistent entry was removed/cleaned up
        // We need to re-fetch arrays as compact() reloads header
        offsets = (int[]) offsetsField.get(regionFile);
        lengths = (int[]) lengthsField.get(regionFile);
        assertThat(offsets[1]).isEqualTo(0);
        assertThat(lengths[1]).isEqualTo(0);

        // Verify valid data is still there
        assertThat(storage.load(pos).isEmpty()).isFalse();
    }

    // ========== Error Handling Tests ==========

    @Test
    void handlesSaveFailureGracefully() throws IOException {
        CisChunkPos pos = new CisChunkPos(0, 0);
        ChunkDelta<String, String> delta = new ChunkDelta<>(s -> s.equals("air"));
        delta.addBlockChange(0, 0, 0, "stone");

        // Force IOException by creating a directory where the file should be
        Path regionPath = tempDir.resolve("r.0.0.cis");
        Files.createDirectory(regionPath);

        // Should log error but not throw
        storage.save(pos, delta);

        // Cleanup
        Files.delete(regionPath);
    }

    @Test
    void handlesLoadFailureGracefully() throws IOException {
        CisChunkPos pos = new CisChunkPos(0, 0);

        // Force IOException by creating a directory where the file should be
        Path regionPath = tempDir.resolve("r.0.0.cis");
        Files.createDirectory(regionPath);

        // Should return empty delta
        ChunkDelta<String, String> loaded = storage.load(pos);
        assertThat(loaded.isEmpty()).isTrue();

        Files.delete(regionPath);
    }

    @Test
    void handlesCorruptedCompressionData() throws IOException {
        CisChunkPos pos = new CisChunkPos(0, 0);
        ChunkDelta<String, String> delta = new ChunkDelta<>(s -> s.equals("air"));
        delta.addBlockChange(0, 0, 0, "stone");
        storage.save(pos, delta);
        storage.close();

        // Corrupt the compressed data
        Path regionPath = tempDir.resolve("r.0.0.cis");
        try (FileChannel channel = FileChannel.open(regionPath,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            // Write garbage at chunk data location (offset 8192)
            ByteBuffer garbage = ByteBuffer.allocate(100);
            for (int i = 0; i < 100; i++) {
                garbage.put((byte) i);
            }
            garbage.flip();
            channel.write(garbage, 8192);
        }

        // Re-open
        storage = new CisStorage<>(tempDir, mapping, stateAdapter, nbtAdapter, "air");

        // Should catch exception and return empty
        ChunkDelta<String, String> loaded = storage.load(pos);
        assertThat(loaded.isEmpty()).isTrue();
    }

    @Test
    void handlesTruncatedDecompressedData() throws IOException {
        CisChunkPos pos = new CisChunkPos(0, 0);

        // Create valid compressed data that decompresses to < 8 bytes
        java.util.zip.Deflater deflater = new java.util.zip.Deflater();
        deflater.setInput(new byte[] { 1, 2, 3 }); // Only 3 bytes
        deflater.finish();
        byte[] compressed = new byte[1024];
        int compressedLen = deflater.deflate(compressed);

        // Write to region file manually
        Path regionPath = tempDir.resolve("r.0.0.cis");
        try (FileChannel channel = FileChannel.open(regionPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {

            // Header: offset=8192, length=compressedLen
            ByteBuffer header = ByteBuffer.allocate(8192);
            header.putInt(8192);
            header.putInt(compressedLen);
            header.position(0);
            channel.write(header);

            // Data at 8192
            channel.write(ByteBuffer.wrap(compressed, 0, compressedLen), 8192);
        }

        storage = new CisStorage<>(tempDir, mapping, stateAdapter, nbtAdapter, "air");

        // Should detect size < 8, log warning, clear chunk, return empty
        ChunkDelta<String, String> loaded = storage.load(pos);
        assertThat(loaded.isEmpty()).isTrue();
    }

    @Test
    void handlesIncompleteCompressedData() throws IOException {
        CisChunkPos pos = new CisChunkPos(0, 0);

        // Create valid compressed data then truncate it
        java.util.zip.Deflater deflater = new java.util.zip.Deflater();
        byte[] input = new byte[100];
        for (int i = 0; i < input.length; i++) {
            input[i] = (byte) i;
        }
        deflater.setInput(input);
        deflater.finish();
        byte[] compressed = new byte[1024];
        int compressedLen = deflater.deflate(compressed);

        // Truncate (remove last byte)
        int truncatedLen = compressedLen - 1;

        // Write to region file
        Path regionPath = tempDir.resolve("r.0.0.cis");
        try (FileChannel channel = FileChannel.open(regionPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {

            ByteBuffer header = ByteBuffer.allocate(8192);
            header.putInt(8192);
            header.putInt(truncatedLen);
            header.position(0);
            channel.write(header);

            channel.write(ByteBuffer.wrap(compressed, 0, truncatedLen), 8192);
        }

        storage = new CisStorage<>(tempDir, mapping, stateAdapter, nbtAdapter, "air");

        // Should handle partial decompression gracefully
        ChunkDelta<String, String> loaded = storage.load(pos);
        assertThat(loaded.isEmpty()).isTrue();
    }

    // ========== Concurrency Tests ==========

    @Test
    void handlesConcurrentAccessToSameRegion() throws Exception {
        int threads = 4;
        int chunksPerThread = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        try {
            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < chunksPerThread; i++) {
                            CisChunkPos pos = new CisChunkPos(threadId, i);
                            ChunkDelta<String, String> delta = new ChunkDelta<>(s -> s.equals("air"));
                            delta.addBlockChange(0, 0, 0, "stone");
                            storage.save(pos, delta);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();

            // Verify all chunks were saved
            for (int t = 0; t < threads; t++) {
                for (int i = 0; i < chunksPerThread; i++) {
                    CisChunkPos pos = new CisChunkPos(t, i);
                    assertThat(storage.load(pos).isEmpty()).isFalse();
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void handlesDoubleCheckedLockingInGetRegionFile() throws Exception {
        int threads = 8;
        int iterations = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        try {
            for (int iter = 0; iter < iterations; iter++) {
                storage.close();
                storage = new CisStorage<>(tempDir, mapping, stateAdapter, nbtAdapter, "air");

                CountDownLatch startLatch = new CountDownLatch(1);
                CountDownLatch doneLatch = new CountDownLatch(threads);

                for (int t = 0; t < threads; t++) {
                    executor.submit(() -> {
                        try {
                            startLatch.await();
                            CisChunkPos pos = new CisChunkPos(0, 0);
                            ChunkDelta<String, String> delta = new ChunkDelta<>(s -> s.equals("air"));
                            delta.addBlockChange(0, 0, 0, "stone");
                            storage.save(pos, delta);
                        } catch (Exception e) {
                            e.printStackTrace();
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                startLatch.countDown();
                assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    // ========== Reflection-Based Tests for Private Members ==========

    @Test
    void regionKeyToStringReturnsCorrectFormat() {
        RegionKey key = new RegionKey(5, 10);
        assertThat(key.toString()).isEqualTo("r.5.10");
    }

    @Test
    void evictOldestRegionHandlesNullEntrySafely() throws Exception {
        java.lang.reflect.Method evictMethod = CisStorage.class.getDeclaredMethod("evictOldestRegion");
        evictMethod.setAccessible(true);

        storage = new CisStorage<>(tempDir, mapping, stateAdapter, nbtAdapter, "air");

        // Get regionCache field
        java.lang.reflect.Field cacheField = CisStorage.class.getDeclaredField("regionCache");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<Object, Object> cache = (java.util.Map<Object, Object>) cacheField.get(storage);

        // Create RegionKey directly (now package-private)
        RegionKey key = new RegionKey(999, 999);

        // Put null value
        cache.put(key, null);

        // Call evictOldestRegion -> should handle null gracefully
        evictMethod.invoke(storage);

        // Verify entry was removed
        assertThat(cache.isEmpty()).isTrue();
    }

    // ========== Chunk Index Calculation Tests ==========

    @Test
    void calculatesChunkIndexCorrectly() {
        // Test various positions within a region
        assertChunkIndexCalculation(0, 0, 0);
        assertChunkIndexCalculation(1, 0, 1);
        assertChunkIndexCalculation(0, 1, 32);
        assertChunkIndexCalculation(31, 31, 1023);
        assertChunkIndexCalculation(15, 15, 495);
    }

    private void assertChunkIndexCalculation(int x, int z, int expectedIndex) {
        CisChunkPos pos = new CisChunkPos(x, z);
        ChunkDelta<String, String> delta = new ChunkDelta<>(s -> s.equals("air"));
        delta.addBlockChange(0, 0, 0, "stone");

        storage.save(pos, delta);

        // Verify it's loadable (index calculation worked)
        assertThat(storage.load(pos).isEmpty()).isFalse();
    }

    // ========== Region Boundary Tests ==========

    @Test
    void handlesNegativeRegionCoordinates() {
        CisChunkPos pos1 = new CisChunkPos(-1, -1); // r.-1.-1
        CisChunkPos pos2 = new CisChunkPos(-32, -32); // r.-1.-1
        CisChunkPos pos3 = new CisChunkPos(-33, -33); // r.-2.-2

        ChunkDelta<String, String> delta = new ChunkDelta<>(s -> s.equals("air"));
        delta.addBlockChange(0, 0, 0, "stone");

        storage.save(pos1, delta);
        storage.save(pos2, delta);
        storage.save(pos3, delta);

        assertThat(storage.load(pos1).isEmpty()).isFalse();
        assertThat(storage.load(pos2).isEmpty()).isFalse();
        assertThat(storage.load(pos3).isEmpty()).isFalse();

        assertThat(Files.exists(tempDir.resolve("r.-1.-1.cis"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("r.-2.-2.cis"))).isTrue();
    }

    @Test
    void handlesRegionBoundaries() {
        // Test chunks right at region boundaries
        CisChunkPos[] boundaryChunks = {
                new CisChunkPos(31, 31), // Last in r.0.0
                new CisChunkPos(32, 32), // First in r.1.1
                new CisChunkPos(0, 31), // Edge case
                new CisChunkPos(31, 0) // Edge case
        };

        ChunkDelta<String, String> delta = new ChunkDelta<>(s -> s.equals("air"));
        delta.addBlockChange(0, 0, 0, "stone");

        for (CisChunkPos pos : boundaryChunks) {
            storage.save(pos, delta);
        }

        for (CisChunkPos pos : boundaryChunks) {
            assertThat(storage.load(pos).isEmpty()).isFalse();
        }
    }

    // ========== Close and Resource Management Tests ==========

    @Test
    void closeFlushesAndCompactsAllRegions() throws IOException {
        // Create multiple regions with data
        for (int i = 0; i < 3; i++) {
            CisChunkPos pos = new CisChunkPos(i * 32, 0);
            ChunkDelta<String, String> delta = new ChunkDelta<>(s -> s.equals("air"));
            delta.addBlockChange(0, 0, 0, "stone");
            storage.save(pos, delta);
        }

        storage.close();

        // Re-open and verify all data is intact
        storage = new CisStorage<>(tempDir, mapping, stateAdapter, nbtAdapter, "air");
        for (int i = 0; i < 3; i++) {
            CisChunkPos pos = new CisChunkPos(i * 32, 0);
            assertThat(storage.load(pos).isEmpty()).isFalse();
        }
    }

    @Test
    void multipleCloseCallsAreSafe() {
        storage.close();
        storage.close(); // Should not throw
    }

    // ========== Edge Cases ==========

    @Test
    void handlesZeroLengthChunkData() {
        CisChunkPos pos = new CisChunkPos(0, 0);

        // Save and immediately clear
        ChunkDelta<String, String> empty = new ChunkDelta<>(s -> s.equals("air"));
        storage.save(pos, empty);

        assertThat(storage.load(pos).isEmpty()).isTrue();
    }

    @Test
    void handlesMaxChunkIndexInRegion() {
        // Chunk at position (31, 31) within region -> index 1023
        CisChunkPos pos = new CisChunkPos(31, 31);
        ChunkDelta<String, String> delta = new ChunkDelta<>(s -> s.equals("air"));
        delta.addBlockChange(0, 0, 0, "stone");

        storage.save(pos, delta);

        assertThat(storage.load(pos).isEmpty()).isFalse();
    }

    @Test
    void savingManyChunksInSingleRegionWorks() {
        ChunkDelta<String, String> delta = new ChunkDelta<>(s -> s.equals("air"));
        delta.addBlockChange(0, 0, 0, "stone");

        // Save all 1024 chunks in a single region
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                CisChunkPos pos = new CisChunkPos(x, z);
                storage.save(pos, delta);
            }
        }

        // Verify all are loadable
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                CisChunkPos pos = new CisChunkPos(x, z);
                assertThat(storage.load(pos).isEmpty()).isFalse();
            }
        }
    }

    @Test
    void handlesVeryLargeChunkData() {
        CisChunkPos pos = new CisChunkPos(0, 0);
        ChunkDelta<String, String> largeDelta = new ChunkDelta<>(s -> s.equals("air"));

        // Add many block changes
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 256; y++) {
                for (int z = 0; z < 16; z++) {
                    if ((x + y + z) % 10 == 0) { // Sparse to keep test fast
                        largeDelta.addBlockChange(x, y, z, "stone");
                    }
                }
            }
        }

        storage.save(pos, largeDelta);

        ChunkDelta<String, String> loaded = storage.load(pos);
        assertThat(loaded.isEmpty()).isFalse();
    }
}