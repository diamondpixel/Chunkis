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
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Optimized mapping system for block translation.
 * V5: Uses lossless property-based serialization instead of fixed 8-bit
 * packing.
 * Ensures deterministic bitstream generation.
 */
public class CisMapping {
    private static final Gson GSON = new Gson();

    // Map Block references directly to IDs (Identity-based comparison is fastest)
    private final Reference2IntMap<Block> blockToId = new Reference2IntOpenHashMap<>();
    private final Int2ObjectMap<Block> idToBlock = new Int2ObjectOpenHashMap<>();

    // Cache property metadata per block ID for fast encode/decode
    private final Int2ObjectMap<PropertyMeta[]> propertyMetaCache = new Int2ObjectOpenHashMap<>();

    private final Path mappingFilePath;
    private int nextId = 0;
    private final Object lock = new Object();
    private final java.util.concurrent.locks.ReadWriteLock rwl = new java.util.concurrent.locks.ReentrantReadWriteLock();

    public CisMapping(Path mappingFile) throws IOException {
        this.mappingFilePath = mappingFile;
        blockToId.defaultReturnValue(-1); // Use -1 to detect missing mappings

        if (mappingFile.toFile().exists()) {
            try (FileReader reader = new FileReader(mappingFile.toFile())) {
                Map<String, Integer> map = GSON.fromJson(reader, new TypeToken<Map<String, Integer>>() {
                }.getType());
                if (map != null) {
                    for (Map.Entry<String, Integer> entry : map.entrySet()) {
                        Identifier id = Identifier.tryParse(entry.getKey());
                        if (id != null) {
                            Block block = Registries.BLOCK.get(id);
                            if (block != Blocks.AIR || entry.getKey().equals("minecraft:air")) {
                                int val = entry.getValue();
                                blockToId.put(block, val);
                                idToBlock.put(val, block);
                                cachePropertyMeta(block, val);
                                if (val >= nextId)
                                    nextId = val + 1;
                            }
                        }
                    }
                }
            }
        }

        io.liparakis.chunkis.ChunkisMod.LOGGER.info("Chunkis Debug: Loaded global mappings: {} entries",
                blockToId.size());

        io.liparakis.chunkis.ChunkisMod.LOGGER.info("Chunkis Debug: Loaded global mappings: {} entries",
                blockToId.size());

        // Ensure AIR is always mapped.
        // Use nextId to avoid overwriting existing mappings (like Stone=0) which causes
        // bitstream desync.
        if (blockToId.getInt(Blocks.AIR) == -1) {
            io.liparakis.chunkis.ChunkisMod.LOGGER
                    .info("Chunkis Debug: Air missing from mappings, registering at ID {}", nextId);
            registerBlock(Blocks.AIR, nextId);
            nextId++;
            // Force save since we modified the map on load
            flush();
        }
    }

    /**
     * Pre-calculates property metadata for a block to enable fast bit-packing.
     * Sorts properties by name to ensure deterministic bitstream order consistently
     * across runs/JVMs.
     */
    private void cachePropertyMeta(Block block, int id) {
        Collection<Property<?>> props = block.getStateManager().getProperties();
        if (props.isEmpty()) {
            propertyMetaCache.put(id, new PropertyMeta[0]);
            return;
        }

        // SORT PROPERTIES BY NAME TO ENSURE DETERMINISTIC ORDER
        List<Property<?>> sortedProps = new ArrayList<>(props);
        sortedProps.sort((p1, p2) -> p1.getName().compareTo(p2.getName()));

        List<PropertyMeta> metas = new ArrayList<>(sortedProps.size());
        for (Property<?> prop : sortedProps) {
            metas.add(new PropertyMeta(prop));
        }
        propertyMetaCache.put(id, metas.toArray(new PropertyMeta[0]));
    }

    public int getBlockId(BlockState state) {
        Block block = state.getBlock();

        // Fast path with Read Lock
        rwl.readLock().lock();
        try {
            int id = blockToId.getInt(block);
            if (id != -1) {
                return id;
            }
        } finally {
            rwl.readLock().unlock();
        }

        // Write Lock for registration
        rwl.writeLock().lock();
        try {
            // Double-check under write lock
            int id = blockToId.getInt(block);
            if (id != -1)
                return id;

            id = nextId++;
            io.liparakis.chunkis.ChunkisMod.LOGGER.info("Chunkis Debug: Registering new block {} with ID {}", block,
                    id);
            registerBlock(block, id);
            // Removed synchronous save() to prevent WorldGen hang

            return id;
        } finally {
            rwl.writeLock().unlock();
        }
    }

    private void registerBlock(Block block, int id) {
        blockToId.put(block, id);
        idToBlock.put(id, block);
        cachePropertyMeta(block, id);
    }

    private int savedCount = 0;

    public void flush() {
        // Fast check without lock (volatile read of size? no, size() isn't volatile
        // usually but safe enough for "eventual")
        if (blockToId.size() <= savedCount) {
            return;
        }

        Map<String, Integer> snapshot = new java.util.HashMap<>();
        int currentSize;

        rwl.readLock().lock();
        try {
            if (blockToId.size() <= savedCount)
                return; // Double check

            // Create snapshot to minimize locking time
            for (Int2ObjectMap.Entry<Block> entry : idToBlock.int2ObjectEntrySet()) {
                Identifier id = Registries.BLOCK.getId(entry.getValue());
                snapshot.put(id.toString(), entry.getIntKey());
            }
            currentSize = blockToId.size();
        } finally {
            rwl.readLock().unlock();
        }

        // Write IO outside the lock
        synchronized (lock) {
            if (currentSize <= savedCount)
                return;

            try (java.io.FileWriter writer = new java.io.FileWriter(mappingFilePath.toFile())) {
                GSON.toJson(snapshot, writer);
                savedCount = currentSize;
                io.liparakis.chunkis.ChunkisMod.LOGGER.info("Chunkis Debug: Flushed global_ids.json ({} entries)",
                        currentSize);
            } catch (Exception e) {
                io.liparakis.chunkis.ChunkisMod.LOGGER.error("Chunkis Debug: Failed to save mappings!", e);
            }
        }
    }

    // ==================== V5: Dynamic Property Bit-Packing ====================

    /**
     * Writes all property values of a BlockState to the BitWriter.
     * Each property is written using the minimum bits required for its value range.
     *
     * @param writer BitWriter to write to
     * @param state  BlockState to serialize
     */
    public void writeStateProperties(BitUtils.BitWriter writer, BlockState state) {
        int blockId = getBlockId(state); // Already thread-safe
        PropertyMeta[] metas;

        rwl.readLock().lock();
        try {
            metas = propertyMetaCache.get(blockId);
        } finally {
            rwl.readLock().unlock();
        }

        if (metas == null || metas.length == 0) {
            return; // No properties to write
        }

        for (PropertyMeta meta : metas) {
            int index = meta.getValueIndex(state);
            writer.write(index, meta.bits);
        }
    }

    /**
     * Reads property values from BitReader and reconstructs the BlockState.
     *
     * @param reader  BitReader to read from
     * @param blockId The block ID (already read)
     * @return Reconstructed BlockState with all properties set
     * @throws IOException If the block ID is unknown (desync prevention)
     */
    public BlockState readStateProperties(BitUtils.BitReader reader, int blockId) throws IOException {
        Block block;
        PropertyMeta[] metas;

        rwl.readLock().lock();
        try {
            block = idToBlock.get(blockId);
            metas = propertyMetaCache.get(blockId);
        } finally {
            rwl.readLock().unlock();
        }

        if (block == null) {
            throw new IOException("CRITICAL: Unknown Block ID " + blockId + " in CisDecoder! Stream desync imminent.");
        }

        BlockState state = block.getDefaultState();

        if (metas == null || metas.length == 0) {
            return state;
        }

        for (PropertyMeta meta : metas) {
            int index = (int) reader.read(meta.bits);
            state = meta.applyValue(state, index);
        }

        return state;
    }

    /**
     * Gets the total number of bits required to store all properties for a block.
     */
    public int getPropertyBitCount(int blockId) {
        PropertyMeta[] metas;
        rwl.readLock().lock();
        try {
            metas = propertyMetaCache.get(blockId);
        } finally {
            rwl.readLock().unlock();
        }

        if (metas == null || metas.length == 0) {
            return 0;
        }
        int total = 0;
        for (PropertyMeta meta : metas) {
            total += meta.bits;
        }
        return total;
    }

    /**
     * Gets just the Block for a given ID (without state reconstruction).
     */
    public Block getBlock(int id) {
        rwl.readLock().lock();
        try {
            return idToBlock.get(id);
        } finally {
            rwl.readLock().unlock();
        }
    }

    // ==================== Property Metadata Cache ====================

    /**
     * Stores metadata for a single block property to enable fast bit-packing.
     */
    @SuppressWarnings("rawtypes")
    private static class PropertyMeta {
        final Property property;
        final Object[] values; // Ordered array of possible values
        final int bits; // Bits needed to represent all values

        @SuppressWarnings("unchecked")
        PropertyMeta(Property<?> prop) {
            this.property = prop;
            Collection<?> valueCollection = prop.getValues();

            // SORT VALUES TO ENSURE DETERMINISTIC ORDER
            // Most properties in MC use Comparable values
            List<Object> sortedValues = new ArrayList<>(valueCollection);
            try {
                sortedValues.sort((o1, o2) -> {
                    if (o1 instanceof Comparable && o2 instanceof Comparable) {
                        return ((Comparable) o1).compareTo(o2);
                    }
                    return o1.toString().compareTo(o2.toString()); // Fallback
                });
            } catch (Exception e) {
                // Ignore sort errors, fallback to default order (hope it's stable)
            }

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
            return 0; // Fallback to first value
        }

        /**
         * Applies a value by index to a BlockState.
         */
        @SuppressWarnings("unchecked")
        BlockState applyValue(BlockState state, int index) {
            if (index < 0 || index >= values.length) {
                return state; // Invalid index, keep default
            }
            try {
                return state.with(property, (Comparable) values[index]);
            } catch (Exception e) {
                return state; // Safety fallback
            }
        }
    }
}