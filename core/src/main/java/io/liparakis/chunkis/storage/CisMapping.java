package io.liparakis.chunkis.storage;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.liparakis.chunkis.spi.BlockRegistryAdapter;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.storage.PropertyPacker.PropertyMeta;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Optimized mapping system for block translation with lossless property-based
 * serialization.
 * Uses identity-based comparison for maximum performance and ensures
 * deterministic bitstream generation.
 * Thread-safe with read-write locking for concurrent access.
 *
 * @param <B> Block type
 * @param <S> BlockState type
 * @param <P> Property type
 */
public final class CisMapping<B, S, P> implements CisAdapter<S> {
    private static final Gson GSON = new Gson();

    /**
     * Constant representing a block that is not yet mapped.
     */
    private static final int MISSING_BLOCK_ID = -1;

    private final BlockRegistryAdapter<B> registry;
    private final BlockStateAdapter<B, S, P> stateAdapter;
    private final PropertyPacker<B, S, P> packer;

    /**
     * Map for fast lookup of block IDs from block instances.
     */
    private final Reference2IntMap<B> blockToId = new Reference2IntOpenHashMap<>();

    /**
     * Map for fast lookup of blocks from their persistent IDs.
     */
    private final Int2ObjectMap<B> idToBlock = new Int2ObjectOpenHashMap<>();

    /**
     * Path where the JSON mapping file is stored.
     */
    private final Path mappingFilePath;

    /**
     * Read-write lock to ensure thread-safe operations during concurrent chunk
     * saving.
     */
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();

    /**
     * Lock used specifically for flushing data to disk.
     */
    private final Object flushLock = new Object();

    /**
     * The next available block ID to assign.
     */
    private int nextId = 0;

    /**
     * The number of mappings currently saved on disk.
     */
    private int savedCount = 0;

    /**
     * Creates a new CisMapping and loads existing mappings from file if present.
     *
     * @param mappingFile  the path to the mapping file
     * @param registry     the block registry adapter
     * @param stateAdapter the block state adapter
     * @param packer       the property packer instance
     * @throws IOException if loading fails
     */
    public CisMapping(Path mappingFile, BlockRegistryAdapter<B> registry, BlockStateAdapter<B, S, P> stateAdapter,
            PropertyPacker<B, S, P> packer) throws IOException {
        this.mappingFilePath = mappingFile;
        this.registry = registry;
        this.stateAdapter = stateAdapter;
        this.packer = packer;

        blockToId.defaultReturnValue(MISSING_BLOCK_ID);

        if (mappingFile.toFile().exists()) {
            loadMappings();
        }

        ensureAirMapped();
    }

    /**
     * Loads existing block mappings from the mapping file.
     */
    private void loadMappings() throws IOException {
        try (FileReader reader = new FileReader(mappingFilePath.toFile())) {
            Map<String, Integer> map = GSON.fromJson(reader, new TypeToken<Map<String, Integer>>() {
            }.getType());

            if (map == null)
                return;

            B air = registry.getAir();
            String airId = registry.getId(air); // "minecraft:air"

            for (Map.Entry<String, Integer> entry : map.entrySet()) {
                String idStr = entry.getKey();
                B block = registry.getBlock(idStr);

                // If block is default (failed generic parse) or explicitly air check
                if (block == air && !idStr.equals(airId)) {
                    // Skip if registry returned default/air for a non-air ID (meaning modded block
                    // missing)
                    continue;
                }

                // If registry returns null for missing blocks, we need to handle that.
                // Assuming adapter returns a fallback or null.
                // If it returns null:
                if (block == null)
                    continue;

                int blockId = entry.getValue();
                registerBlockInternal(block, blockId);

                if (blockId >= nextId) {
                    nextId = blockId + 1;
                }
            }
        }
    }

    /**
     * Ensures air is always mapped to prevent desync issues.
     */
    private void ensureAirMapped() {
        B air = registry.getAir();
        if (blockToId.getInt(air) == MISSING_BLOCK_ID) {
            registerBlockInternal(air, nextId);
            nextId++;
            flush();
        }
    }

    /**
     * Gets the block ID for a given block state, registering it if necessary.
     * Thread-safe with optimistic read-lock strategy.
     *
     * @param state the block state
     * @return the block ID
     */
    public int getBlockId(S state) {
        B block = stateAdapter.getBlock(state);

        rwLock.readLock().lock();
        try {
            int id = blockToId.getInt(block);
            if (id != MISSING_BLOCK_ID) {
                return id;
            }
        } finally {
            rwLock.readLock().unlock();
        }

        rwLock.writeLock().lock();
        try {
            int id = blockToId.getInt(block);
            if (id != MISSING_BLOCK_ID) {
                return id;
            }

            id = nextId++;
            registerBlockInternal(block, id);

            return id;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Internal method to register a block without locking.
     * <p>
     * Caller MUST hold the write lock before calling this.
     *
     * @param block the block to register
     * @param id    the ID to assign
     */
    private void registerBlockInternal(B block, int id) {
        blockToId.put(block, id);
        idToBlock.put(id, block);
    }

    /**
     * Flushes new mappings to disk if there are unsaved changes.
     * Uses double-checked locking to minimize I/O.
     */
    public void flush() {
        if (blockToId.size() <= savedCount) {
            return;
        }

        Map<String, Integer> snapshot = createMappingSnapshot();

        synchronized (flushLock) {
            if (snapshot.size() <= savedCount) {
                return;
            }

            try (FileWriter writer = new FileWriter(mappingFilePath.toFile())) {
                GSON.toJson(snapshot, writer);
                savedCount = snapshot.size();
            } catch (Exception e) {
                System.err.println("Chunkis: Failed to save mappings: " + e.getMessage());
            }
        }
    }

    /**
     * Creates a snapshot of current mappings in a format suitable for JSON
     * serialization.
     *
     * @return a map of block identifier strings to their allocated IDs
     */
    private Map<String, Integer> createMappingSnapshot() {
        Map<String, Integer> snapshot = new HashMap<>();

        rwLock.readLock().lock();
        try {
            if (blockToId.size() <= savedCount) {
                return snapshot;
            }

            for (Int2ObjectMap.Entry<B> entry : idToBlock.int2ObjectEntrySet()) {
                String id = registry.getId(entry.getValue());
                snapshot.put(id, entry.getIntKey());
            }
        } finally {
            rwLock.readLock().unlock();
        }

        return snapshot;
    }

    /**
     * Writes all property values of a BlockState to the BitWriter.
     * Each property uses the minimum bits required for its value range.
     *
     * @param writer the BitWriter to write to
     * @param state  the BlockState to serialize
     */
    public void writeStateProperties(BitUtils.BitWriter writer, S state) {
        B block = stateAdapter.getBlock(state);
        PropertyMeta<P>[] metas = packer.getPropertyMetas(block);
        packer.writeProperties(writer, state, metas);
    }

    /**
     * Reads property values from BitReader and reconstructs the BlockState.
     *
     * @param reader  the BitReader to read from
     * @param blockId the block ID
     * @return the reconstructed BlockState
     * @throws IOException if the block ID is unknown
     */
    public S readStateProperties(BitUtils.BitReader reader, int blockId) throws IOException {
        B block = getBlockInternal(blockId);

        if (block == null) {
            throw new IOException("Unknown Block ID " + blockId + " - stream desync detected");
        }

        PropertyMeta<P>[] metas = packer.getPropertyMetas(block);
        return packer.readProperties(reader, block, metas);
    }

    /**
     * Gets the block instance for a given ID.
     *
     * @param id the block ID
     * @return the block instance, or {@code null} if not mapped
     */
    private B getBlockInternal(int id) {
        rwLock.readLock().lock();
        try {
            return idToBlock.get(id);
        } finally {
            rwLock.readLock().unlock();
        }
    }
}
