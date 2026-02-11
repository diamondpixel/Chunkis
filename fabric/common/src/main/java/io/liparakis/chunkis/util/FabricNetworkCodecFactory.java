package io.liparakis.chunkis.util;

import io.liparakis.chunkis.adapter.FabricBlockRegistryAdapter;
import io.liparakis.chunkis.adapter.FabricBlockStateAdapter;
import io.liparakis.chunkis.adapter.FabricNbtAdapter;
import io.liparakis.chunkis.spi.BlockRegistryAdapter;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.spi.NbtAdapter;
import io.liparakis.chunkis.storage.PropertyPacker;
import io.liparakis.chunkis.storage.codec.CisNetworkDecoder;
import io.liparakis.chunkis.storage.codec.CisNetworkEncoder;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.state.property.Property;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Factory for creating Fabric-specific network encoders and decoders.
 * <p>
 * This factory provides optimized codec instances with configurable creation
 * strategies:
 * </p>
 * <ul>
 * <li><b>Singleton Mode (Default):</b> Reuses single instances if codecs are
 * stateless and thread-safe</li>
 * <li><b>Factory Mode:</b> Creates new instances on each call if codecs are
 * stateful</li>
 * <li><b>Pooled Mode:</b> Reuses instances from a pool for high-throughput
 * scenarios</li>
 * </ul>
 *
 * <p>
 * <b>Thread Safety:</b> This factory is thread-safe. The thread-safety of
 * returned
 * encoder/decoder instances depends on the underlying CisNetworkEncoder/Decoder
 * implementation.
 * </p>
 *
 * <p>
 * <b>Performance Note:</b> If CisNetworkEncoder/Decoder are stateless and
 * thread-safe,
 * using singleton instances (default) provides optimal performance with zero
 * allocation overhead.
 * </p>
 *
 * @author Liparakis
 * @version 1.0
 */
public final class FabricNetworkCodecFactory {

    // Shared adapter instances (immutable, thread-safe)
    private static final BlockRegistryAdapter<Block> REGISTRY_ADAPTER = new FabricBlockRegistryAdapter();
    private static final BlockStateAdapter<Block, BlockState, Property<?>> STATE_ADAPTER = new FabricBlockStateAdapter();
    private static final NbtAdapter<NbtCompound> NBT_ADAPTER = new FabricNbtAdapter();
    private static final PropertyPacker<Block, BlockState, Property<?>> PROPERTY_PACKER = new PropertyPacker<>(
            STATE_ADAPTER);
    private static final BlockState AIR_STATE = Blocks.AIR.getDefaultState();

    // Singleton instances (lazy-initialized if codecs are stateless and
    // thread-safe)
    // Change USE_SINGLETON to false if CisNetworkEncoder/Decoder maintain mutable
    // state
    private static final boolean USE_SINGLETON = true; // Configure based on codec implementation

    private static volatile CisNetworkDecoder<Block, BlockState, Property<?>, NbtCompound> decoderInstance;
    private static volatile CisNetworkEncoder<Block, BlockState, Property<?>, NbtCompound> encoderInstance;

    // Object pools for high-throughput scenarios (disabled by default)
    private static final boolean USE_POOLING = false; // Enable if profiling shows allocation bottleneck
    private static final int POOL_SIZE = 16; // Adjust based on concurrent thread count

    private static final BlockingQueue<CisNetworkDecoder<Block, BlockState, Property<?>, NbtCompound>> decoderPool;
    private static final BlockingQueue<CisNetworkEncoder<Block, BlockState, Property<?>, NbtCompound>> encoderPool;

    static {
        // Initialize pools if enabled
        if (USE_POOLING) {
            decoderPool = new ArrayBlockingQueue<>(POOL_SIZE);
            encoderPool = new ArrayBlockingQueue<>(POOL_SIZE);

            // Pre-populate pools
            for (int i = 0; i < POOL_SIZE; i++) {
                decoderPool.offer(createDecoderInstance());
                encoderPool.offer(createEncoderInstance());
            }
        } else {
            decoderPool = null;
            encoderPool = null;
        }
    }

    // Prevent instantiation
    private FabricNetworkCodecFactory() {
        throw new AssertionError("Utility class - do not instantiate");
    }

    /**
     * Creates or retrieves a decoder instance based on configured strategy.
     * <p>
     * <b>Singleton Mode:</b> Returns cached instance (zero allocation, optimal
     * performance).<br>
     * <b>Factory Mode:</b> Creates new instance on each call.
     * </p>
     *
     * @return A CisNetworkDecoder instance
     * @implNote If USE_SINGLETON is true, this method is thread-safe via
     *           double-checked locking
     */
    public static CisNetworkDecoder<Block, BlockState, Property<?>, NbtCompound> createDecoder() {
        if (USE_SINGLETON) {
            return getOrCreateDecoderSingleton();
        } else if (USE_POOLING) {
            // For pooling, users should use borrowDecoder() instead, but provide fallback
            CisNetworkDecoder<Block, BlockState, Property<?>, NbtCompound> decoder = decoderPool.poll();
            return decoder != null ? decoder : createDecoderInstance();
        } else {
            return createDecoderInstance();
        }
    }

    /**
     * Creates or retrieves an encoder instance based on configured strategy.
     * <p>
     * <b>Singleton Mode:</b> Returns cached instance (zero allocation, optimal
     * performance).<br>
     * <b>Factory Mode:</b> Creates new instance on each call.
     * </p>
     *
     * @return A CisNetworkEncoder instance
     * @implNote If USE_SINGLETON is true, this method is thread-safe via
     *           double-checked locking
     */
    public static CisNetworkEncoder<Block, BlockState, Property<?>, NbtCompound> createEncoder() {
        if (USE_SINGLETON) {
            return getOrCreateEncoderSingleton();
        } else if (USE_POOLING) {
            // For pooling, users should use borrowEncoder() instead, but provide fallback
            CisNetworkEncoder<Block, BlockState, Property<?>, NbtCompound> encoder = encoderPool.poll();
            return encoder != null ? encoder : createEncoderInstance();
        } else {
            return createEncoderInstance();
        }
    }

    /*
     * Gets the shared decoder singleton instance using double-checked locking.
     * <p>
     * <b>Performance:</b> First call incurs creation cost, subsequent calls are
     * extremely fast (volatile read only).
     * </p>
     */
    /**
     * Gets the shared decoder singleton instance using double-checked locking.
     * <p>
     * <b>Performance:</b> First call incurs creation cost, subsequent calls are
     * extremely fast (volatile read only).
     *
     * @return The singleton decoder instance.
     */
    private static CisNetworkDecoder<Block, BlockState, Property<?>, NbtCompound> getOrCreateDecoderSingleton() {
        CisNetworkDecoder<Block, BlockState, Property<?>, NbtCompound> result = decoderInstance;

        // Fast path: instance already created, return immediately
        if (result != null) {
            return result;
        }

        // Slow path: synchronized creation with double-check
        synchronized (FabricNetworkCodecFactory.class) {
            result = decoderInstance;
            if (result == null) {
                result = createDecoderInstance();
                decoderInstance = result;
            }
            return result;
        }
    }

    /*
     * Gets the shared encoder singleton instance using double-checked locking.
     * <p>
     * <b>Performance:</b> First call incurs creation cost, subsequent calls are
     * extremely fast (volatile read only).
     * </p>
     */
    /**
     * Gets the shared encoder singleton instance using double-checked locking.
     * <p>
     * <b>Performance:</b> First call incurs creation cost, subsequent calls are
     * extremely fast (volatile read only).
     *
     * @return The singleton encoder instance.
     */
    private static CisNetworkEncoder<Block, BlockState, Property<?>, NbtCompound> getOrCreateEncoderSingleton() {
        CisNetworkEncoder<Block, BlockState, Property<?>, NbtCompound> result = encoderInstance;

        // Fast path: instance already created, return immediately
        if (result != null) {
            return result;
        }

        // Slow path: synchronized creation with double-check
        synchronized (FabricNetworkCodecFactory.class) {
            result = encoderInstance;
            if (result == null) {
                result = createEncoderInstance();
                encoderInstance = result;
            }
            return result;
        }
    }

    /**
     * Creates a new decoder instance.
     * <p>
     * <b>Internal Use:</b> This method is used by all creation strategies.
     * </p>
     */
    private static CisNetworkDecoder<Block, BlockState, Property<?>, NbtCompound> createDecoderInstance() {
        return new CisNetworkDecoder<>(
                REGISTRY_ADAPTER,
                PROPERTY_PACKER,
                STATE_ADAPTER,
                NBT_ADAPTER,
                AIR_STATE);
    }

    /**
     * Creates a new encoder instance.
     * <p>
     * <b>Internal Use:</b> This method is used by all creation strategies.
     * </p>
     */
    private static CisNetworkEncoder<Block, BlockState, Property<?>, NbtCompound> createEncoderInstance() {
        return new CisNetworkEncoder<>(
                REGISTRY_ADAPTER,
                PROPERTY_PACKER,
                STATE_ADAPTER,
                NBT_ADAPTER,
                AIR_STATE);
    }
}