package io.liparakis.chunkis.api;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.storage.CisStorage;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.world.chunk.Chunk;

import java.util.Optional;

/**
 * Public API for interacting with the Chunkis mod storage system.
 * <p>
 * This interface provides methods to access the underlying {@link CisStorage}
 * for a world, as well as helper methods for working with Chunkis-managed
 * chunks.
 * </p>
 *
 * @author Liparakis
 * @version 1.0
 */
public interface ChunkisApi {

    /**
     * Gets the singleton instance of the Chunkis API.
     *
     * @return the API instance
     * @throws IllegalStateException if the API is not yet initialized
     */
    static ChunkisApi getInstance() {
        return io.liparakis.chunkis.api.impl.ChunkisApiImpl.getInstance();
    }

    /**
     * Gets the Chunkis storage manager for the specified server world.
     *
     * @param world the world to get storage for
     * @return the storage manager
     */
    CisStorage<Block, BlockState, Property<?>, NbtCompound> getStorage(ServerWorld world);

    /**
     * Gets the chunk delta data associated with the given chunk, if it exists.
     *
     * @param chunk the chunk to inspect
     * @return an Optional containing the delta if present, or empty otherwise
     */
    Optional<ChunkDelta<BlockState, NbtCompound>> getDelta(Chunk chunk);

    /**
     * Checks if the given chunk contains any Chunkis-managed delta data.
     *
     * @param chunk the chunk to check
     * @return true if the chunk has a non-empty delta, false otherwise
     */
    boolean hasChunkisData(Chunk chunk);
}
