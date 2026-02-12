package io.liparakis.chunkis.api.impl;

import io.liparakis.chunkis.api.ChunkisApi;
import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.storage.CisStorage;
import io.liparakis.chunkis.util.FabricCisStorageHelper;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.world.chunk.Chunk;

import java.util.Optional;

/**
 * Internal implementation of the {@link ChunkisApi} interface.
 *
 * @author Liparakis
 * @version 1.0
 */
public final class ChunkisApiImpl implements ChunkisApi {

    private static final ChunkisApiImpl INSTANCE = new ChunkisApiImpl();

    private ChunkisApiImpl() {
    }

    public static ChunkisApiImpl getInstance() {
        return INSTANCE;
    }

    @Override
    public CisStorage<Block, BlockState, Property<?>, NbtCompound> getStorage(ServerWorld world) {
        return FabricCisStorageHelper.getStorage(world);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<ChunkDelta<BlockState, NbtCompound>> getDelta(Chunk chunk) {
        if (chunk instanceof ChunkisDeltaDuck duck) {
            ChunkDelta<?, ?> rawDelta = duck.chunkis$getDelta();
            if (rawDelta != null) {
                // Safe cast as we know the types used in fabric environment
                return Optional.of((ChunkDelta<BlockState, NbtCompound>) rawDelta);
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean hasChunkisData(Chunk chunk) {
        if (chunk instanceof ChunkisDeltaDuck duck) {
            ChunkDelta<?, ?> delta = duck.chunkis$getDelta();
            return delta != null && !delta.isEmpty();
        }
        return false;
    }
}
