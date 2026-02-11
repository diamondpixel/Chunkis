package io.liparakis.chunkis.util;

import io.liparakis.chunkis.adapter.FabricBlockRegistryAdapter;
import io.liparakis.chunkis.adapter.FabricBlockStateAdapter;
import io.liparakis.chunkis.adapter.FabricNbtAdapter;
import io.liparakis.chunkis.spi.BlockRegistryAdapter;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.spi.NbtAdapter;
import io.liparakis.chunkis.storage.CisMapping;
import io.liparakis.chunkis.storage.CisStorage;
import io.liparakis.chunkis.storage.PropertyPacker;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe helper class for managing Chunkis storage instances per world
 * dimension.
 * <p>
 * This class maintains a cache of storage instances and ensures proper
 * lifecycle management
 * with concurrent access support. Storage instances are created lazily and can
 * be explicitly
 * closed when no longer needed.
 * </p>
 *
 * <p>
 * <b>Thread Safety:</b> All public methods are thread-safe. Storage creation
 * and access
 * are protected by read-write locks to prevent race conditions during close
 * operations.
 * </p>
 *
 * when a world is unloaded to prevent memory leaks.
 * </p>
 *
 * @author Liparakis
 * @version 1.0
 */
public final class FabricCisStorageHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger(FabricCisStorageHelper.class);

    // Directory and file name constants
    private static final String DIMENSIONS_DIR = "dimensions";
    private static final String CHUNKIS_DIR = "chunkis";
    private static final String REGIONS_DIR = "regions";
    private static final String MAPPING_FILE = "global_ids.json";
    private static final String OVERWORLD_ID = "overworld";

    // Storage cache with concurrent access support
    private static final ConcurrentHashMap<RegistryKey<World>, StorageWrapper> storageMap = new ConcurrentHashMap<>();

    // Path cache to avoid repeated directory resolution
    private static final ConcurrentHashMap<RegistryKey<World>, Path> pathCache = new ConcurrentHashMap<>();

    // Adapter instances (reused across all storages for efficiency)
    private static final BlockRegistryAdapter<Block> REGISTRY_ADAPTER = new FabricBlockRegistryAdapter();
    private static final BlockStateAdapter<Block, BlockState, Property<?>> STATE_ADAPTER = new FabricBlockStateAdapter();
    private static final NbtAdapter<NbtCompound> NBT_ADAPTER = new FabricNbtAdapter();
    private static final BlockState DEFAULT_BLOCK_STATE = Blocks.AIR.getDefaultState();

    // Prevent instantiation
    private FabricCisStorageHelper() {
        throw new AssertionError("Utility class - do not instantiate");
    }

    /**
     * Retrieves or creates a CisStorage instance for the given world.
     * <p>
     * This method is thread-safe and uses double-checked locking for optimal
     * performance.
     * The storage instance is cached and reused for subsequent calls with the same
     * world.
     * </p>
     *
     * @param world The server world to get storage for (must not be null)
     * @return The CisStorage instance for this world
     * @throws NullPointerException           if world is null
     * @throws StorageInitializationException if storage creation fails
     */
    public static CisStorage<Block, BlockState, Property<?>, NbtCompound> getStorage(ServerWorld world) {
        Objects.requireNonNull(world, "ServerWorld cannot be null");

        RegistryKey<World> key = world.getRegistryKey();

        // Fast path: storage already exists and is open
        StorageWrapper wrapper = storageMap.get(key);
        if (wrapper != null && wrapper.isOpen()) {
            return wrapper.getStorage();
        }

        // Slow path: need to create or recreate storage
        return storageMap.compute(key, (k, existing) -> {
            // Check if existing storage is still valid
            if (existing != null && existing.isOpen()) {
                return existing;
            }

            // Clean up old storage if it exists
            if (existing != null) {
                existing.close();
            }

            // Create new storage
            try {
                CisStorage<Block, BlockState, Property<?>, NbtCompound> storage = createStorageInternal(world);
                LOGGER.info("Created Chunkis storage for dimension: {}", key.getValue());
                return new StorageWrapper(storage);
            } catch (Exception e) {
                LOGGER.error("Failed to create Chunkis storage for dimension: {}", key.getValue(), e);
                throw new StorageInitializationException(
                        "Failed to initialize Chunkis storage for " + key.getValue(), e);
            }
        }).getStorage();
    }

    /**
     * Closes and removes the storage instance for the given world.
     * <p>
     * This method should be called when a world is unloaded to prevent memory
     * leaks.
     * It is safe to call this method multiple times or for worlds without storage.
     * </p>
     *
     * @param world The server world to close storage for (must not be null)
     * @throws NullPointerException if world is null
     */
    public static void closeStorage(ServerWorld world) {
        Objects.requireNonNull(world, "ServerWorld cannot be null");

        RegistryKey<World> key = world.getRegistryKey();
        StorageWrapper wrapper = storageMap.remove(key);

        if (wrapper != null) {
            try {
                wrapper.close();
                LOGGER.info("Closed Chunkis storage for dimension: {}", key.getValue());
            } catch (Exception e) {
                LOGGER.error("Error closing Chunkis storage for dimension: {}", key.getValue(), e);
            }
        }

        // Also clean up path cache
        pathCache.remove(key);
    }

    /*
     * Creates a new CisStorage instance for the given world.
     * <p>
     * <b>Optimization:</b> Reuses adapter instances across all storages to reduce
     * object creation and memory usage.
     * </p>
     *
     * @param world The server world
     * @return A new CisStorage instance
     * @throws IOException if storage initialization fails
     */
    /**
     * Creates a new CisStorage instance for the given world.
     * <p>
     * <b>Optimization:</b> Reuses adapter instances across all storages to reduce
     * object creation and memory usage.
     * </p>
     *
     * @param world The server world
     * @return A new CisStorage instance
     * @throws IOException if storage initialization fails
     */
    private static CisStorage<Block, BlockState, Property<?>, NbtCompound> createStorageInternal(
            ServerWorld world) throws IOException {

        Path storageDir = getOrCreateDimensionStorage(world);
        Path mappingFile = storageDir.getParent().resolve(MAPPING_FILE);

        // Create property packer for efficient state storage
        PropertyPacker<Block, BlockState, Property<?>> packer = new PropertyPacker<>(STATE_ADAPTER);

        // Load or create mapping file for block ID persistence
        CisMapping<Block, BlockState, Property<?>> mapping = new CisMapping<>(mappingFile, REGISTRY_ADAPTER,
                STATE_ADAPTER, packer);

        return new CisStorage<>(storageDir, mapping, STATE_ADAPTER, NBT_ADAPTER, DEFAULT_BLOCK_STATE);
    }

    /**
     * Gets or creates the storage directory for a world dimension.
     * <p>
     * <b>Optimization:</b> Caches resolved paths to avoid repeated file system
     * operations
     * and string concatenation overhead.
     * </p>
     *
     * @param world The server world
     * @return The path to the storage directory
     * @throws IOException if directory creation fails
     */
    private static Path getOrCreateDimensionStorage(ServerWorld world) throws IOException {
        RegistryKey<World> key = world.getRegistryKey();

        // Check cache first
        Path cachedPath = pathCache.get(key);
        if (cachedPath != null) {
            return cachedPath;
        }

        // Compute path
        Path storageDir = computeStorageDirectory(world);

        // Create directory if it doesn't exist
        Files.createDirectories(storageDir);

        // Cache the path for future use
        pathCache.put(key, storageDir);

        return storageDir;
    }

    /*
     * Computes the storage directory path for a world.
     * <p>
     * <b>Optimization:</b> Uses efficient path construction and avoids unnecessary
     * string allocations.
     * </p>
     *
     * @param world The server world
     * @return The computed storage directory path
     */
    /**
     * Computes the storage directory path for a world.
     * <p>
     * <b>Optimization:</b> Uses efficient path construction and avoids unnecessary
     * string allocations.
     * </p>
     *
     * @param world The server world
     * @return The computed storage directory path
     */
    private static Path computeStorageDirectory(ServerWorld world) {
        Path baseDir = world.getServer().getSavePath(WorldSavePath.ROOT);
        String dimId = world.getRegistryKey().getValue().getPath();

        // Handle non-overworld dimensions by appending namespace/id
        if (!OVERWORLD_ID.equals(dimId)) {
            String namespace = world.getRegistryKey().getValue().getNamespace();
            baseDir = baseDir.resolve(DIMENSIONS_DIR)
                    .resolve(namespace)
                    .resolve(dimId);
        }

        // Append chunkis/regions subdirectories for final path
        return baseDir.resolve(CHUNKIS_DIR).resolve(REGIONS_DIR);
    }

    /**
     * Wrapper class for CisStorage with lifecycle tracking.
     * <p>
     * Provides thread-safe close operation and state tracking to prevent
     * use-after-close errors.
     * </p>
     */
    private static class StorageWrapper {
        private final CisStorage<Block, BlockState, Property<?>, NbtCompound> storage;
        private final ReadWriteLock lock = new ReentrantReadWriteLock();
        private volatile boolean open = true;

        StorageWrapper(CisStorage<Block, BlockState, Property<?>, NbtCompound> storage) {
            this.storage = Objects.requireNonNull(storage, "Storage cannot be null");
        }

        /**
         * Gets the underlying storage instance.
         * <p>
         * Uses read lock to allow concurrent access while preventing access during
         * close.
         * </p>
         *
         * @return The storage instance
         * @throws IllegalStateException if storage is closed
         */
        CisStorage<Block, BlockState, Property<?>, NbtCompound> getStorage() {
            lock.readLock().lock();
            try {
                if (!open) {
                    throw new IllegalStateException("Storage has been closed");
                }
                return storage;
            } finally {
                lock.readLock().unlock();
            }
        }

        /**
         * Checks if the storage is still open.
         *
         * @return true if open, false if closed
         */
        boolean isOpen() {
            return open;
        }

        /**
         * Closes the storage instance.
         * <p>
         * Uses write lock to ensure no concurrent access during close operation.
         * Idempotent - safe to call multiple times.
         * </p>
         */
        void close() {
            lock.writeLock().lock();
            try {
                if (open) {
                    storage.close();
                    open = false;
                }
            } finally {
                lock.writeLock().unlock();
            }
        }
    }

    /**
     * Custom exception for storage initialization failures.
     * <p>
     * Provides better error context than generic RuntimeException.
     * </p>
     */
    public static class StorageInitializationException extends RuntimeException {
        public StorageInitializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}