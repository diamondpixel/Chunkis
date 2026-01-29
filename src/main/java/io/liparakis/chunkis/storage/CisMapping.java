package io.liparakis.chunkis.storage;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Optimized mapping system for block translation with lossless property-based
 * serialization.
 * Uses identity-based comparison for maximum performance and ensures
 * deterministic bitstream generation.
 * Thread-safe with read-write locking for concurrent access.
 */
public final class CisMapping {
    private static final Gson GSON = new Gson();

    /**
     * Constant representing a block that is not yet mapped.
     */
    private static final int MISSING_BLOCK_ID = -1;

    /**
     * Map for fast lookup of block IDs from block instances.
     */
    private final Reference2IntMap<Block> blockToId = new Reference2IntOpenHashMap<>();

    /**
     * Map for fast lookup of blocks from their persistent IDs.
     */
    private final Int2ObjectMap<Block> idToBlock = new Int2ObjectOpenHashMap<>();

    /**
     * Cache for property metadata to avoid re-calculating bit-packing information.
     */
    private final Int2ObjectMap<PropertyMeta[]> propertyMetaCache = new Int2ObjectOpenHashMap<>();

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
     * @param mappingFile the path to the mapping file
     * @throws IOException if loading fails
     */
    public CisMapping(Path mappingFile) throws IOException {
        this.mappingFilePath = mappingFile;
        blockToId.defaultReturnValue(MISSING_BLOCK_ID);

        if (mappingFile.toFile().exists()) {
            loadMappings();
        }

        ensureAirMapped();

        // io.liparakis.chunkis.ChunkisMod.LOGGER.info("Chunkis: Loaded {} block
        // mappings", blockToId.size());
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

            for (Map.Entry<String, Integer> entry : map.entrySet()) {
                Identifier id = Identifier.tryParse(entry.getKey());
                if (id == null)
                    continue;

                Block block = Registries.BLOCK.get(id);
                if (block == Blocks.AIR && !entry.getKey().equals("minecraft:air")) {
                    continue;
                }

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
        if (blockToId.getInt(Blocks.AIR) == MISSING_BLOCK_ID) {
            // io.liparakis.chunkis.ChunkisMod.LOGGER.info("Chunkis: Registering AIR at ID
            // {}", nextId);
            registerBlockInternal(Blocks.AIR, nextId);
            nextId++;
            flush();
        }
    }

    /**
     * Caches property metadata for a block to enable fast bit-packing.
     * Properties are sorted by name for deterministic ordering.
     *
     * @param block the block to cache metadata for
     * @param id    the block's ID
     */
    private void cachePropertyMeta(Block block, int id) {
        Collection<Property<?>> props = block.getStateManager().getProperties();

        if (props.isEmpty()) {
            propertyMetaCache.put(id, new PropertyMeta[0]);
            return;
        }

        List<Property<?>> sortedProps = new ArrayList<>(props);
        sortedProps.sort(Comparator.comparing(Property::getName));

        PropertyMeta[] metas = sortedProps.stream()
                .map(PropertyMeta::new)
                .toArray(PropertyMeta[]::new);

        propertyMetaCache.put(id, metas);
    }

    /**
     * Gets the block ID for a given block state, registering it if necessary.
     * Thread-safe with optimistic read-lock strategy.
     *
     * @param state the block state
     * @return the block ID
     */
    public int getBlockId(BlockState state) {
        Block block = state.getBlock();

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
            // io.liparakis.chunkis.ChunkisMod.LOGGER.info("Chunkis: Registering block {}
            // with ID {}", block, id);
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
    private void registerBlockInternal(Block block, int id) {
        blockToId.put(block, id);
        idToBlock.put(id, block);
        cachePropertyMeta(block, id);
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
                // io.liparakis.chunkis.ChunkisMod.LOGGER.info("Chunkis: Flushed {} mappings to
                // disk", savedCount);
            } catch (Exception e) {
                io.liparakis.chunkis.ChunkisMod.LOGGER.error("Chunkis: Failed to save mappings", e);
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

            for (Int2ObjectMap.Entry<Block> entry : idToBlock.int2ObjectEntrySet()) {
                Identifier id = Registries.BLOCK.getId(entry.getValue());
                snapshot.put(id.toString(), entry.getIntKey());
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
    public void writeStateProperties(BitUtils.BitWriter writer, BlockState state) {
        int blockId = getBlockId(state);
        PropertyMeta[] metas = getPropertyMetas(blockId);

        if (metas == null) {
            return;
        }

        for (PropertyMeta meta : metas) {
            writer.write(meta.getValueIndex(state), meta.bits);
        }
    }

    /**
     * Reads property values from BitReader and reconstructs the BlockState.
     *
     * @param reader  the BitReader to read from
     * @param blockId the block ID
     * @return the reconstructed BlockState
     * @throws IOException if the block ID is unknown
     */
    public BlockState readStateProperties(BitUtils.BitReader reader, int blockId) throws IOException {
        Block block = getBlockInternal(blockId);

        if (block == null) {
            throw new IOException("Unknown Block ID " + blockId + " - stream desync detected");
        }

        PropertyMeta[] metas = getPropertyMetas(blockId);
        BlockState state = block.getDefaultState();

        if (metas == null) {
            return state;
        }

        for (PropertyMeta meta : metas) {
            int index = (int) reader.read(meta.bits);
            state = meta.applyValue(state, index);
        }

        return state;
    }

    /**
     * Gets property metadata for a given block ID.
     *
     * @param blockId the ID of the block
     * @return an array of property metadata, or {@code null} if none exists
     */
    private PropertyMeta[] getPropertyMetas(int blockId) {
        rwLock.readLock().lock();
        try {
            return propertyMetaCache.get(blockId);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Gets the block instance for a given ID.
     *
     * @param id the block ID
     * @return the block instance, or {@code null} if not mapped
     */
    private Block getBlockInternal(int id) {
        rwLock.readLock().lock();
        try {
            return idToBlock.get(id);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Stores metadata for a single block property to enable fast bit-packing.
     * Values are sorted for deterministic ordering across runs and JVMs.
     */
    @SuppressWarnings("rawtypes")
    private static final class PropertyMeta {
        /**
         * The standard Minecraft block property.
         */
        final Property property;

        /**
         * The sorted array of all possible values for this property.
         */
        final Object[] values;

        /**
         * The number of bits required to pack an index from the values array.
         */
        final int bits;

        /**
         * Creates property metadata for a given property.
         *
         * @param prop the property to metadata-ize
         */
        @SuppressWarnings("unchecked")
        PropertyMeta(Property<?> prop) {
            this.property = prop;
            Collection<?> valueCollection = prop.getValues();

            List<Object> sortedValues = new ArrayList<>(valueCollection);
            sortedValues.sort((o1, o2) -> {
                if (o1 instanceof Comparable && o2 instanceof Comparable) {
                    try {
                        return ((Comparable) o1).compareTo(o2);
                    } catch (Exception e) {
                        return o1.toString().compareTo(o2.toString());
                    }
                }
                return o1.toString().compareTo(o2.toString());
            });

            this.values = sortedValues.toArray();
            this.bits = Math.max(1, 32 - Integer.numberOfLeadingZeros(values.length - 1));
        }

        /**
         * Gets the index of a state's value for this property.
         */
        @SuppressWarnings("unchecked")
        int getValueIndex(BlockState state) {
            Object value = state.get(property);
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(value)) {
                    return i;
                }
            }
            return 0;
        }

        /**
         * Applies a value by index to a BlockState.
         */
        @SuppressWarnings("unchecked")
        BlockState applyValue(BlockState state, int index) {
            if (index < 0 || index >= values.length) {
                return state;
            }

            try {
                return state.with(property, (Comparable) values[index]);
            } catch (Exception e) {
                return state;
            }
        }
    }
}