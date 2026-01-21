package io.liparakis.chunkis.mixin;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.ChunkisDeltaDuck;
import io.liparakis.chunkis.storage.CisStorage;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(WorldChunk.class)
public class WorldChunkMixin {

    @Unique
    private static final Map<ServerWorld, CisStorage> storageCache = new ConcurrentHashMap<>();

    @Unique
    private CisStorage chunkis$cachedStorage;

    @Unique
    private CisStorage chunkis$getOrCreateStorage(ServerWorld world) {
        if (chunkis$cachedStorage != null) {
            return chunkis$cachedStorage;
        }

        if (world == null) {
            return null;
        }

        chunkis$cachedStorage = storageCache.computeIfAbsent(world, CisStorage::new);
        return chunkis$cachedStorage;
    }

    @Inject(method = "setLoadedToWorld(Z)V", at = @At("HEAD"))
    private void chunkis$onSetLoadedToWorld(boolean loaded, CallbackInfo ci) {
        if (loaded) {
            return; // Chunk is being loaded, nothing to save
        }

        // Chunk is being unloaded, save the delta
        if (!(this instanceof ChunkisDeltaDuck duck)) {
            return;
        }

        ChunkDelta delta = duck.chunkis$getDelta();
        if (delta == null) {
            return;
        }

        WorldChunk self = (WorldChunk) (Object) this;
        ServerWorld world = (ServerWorld) self.getWorld();

        CisStorage storage = chunkis$getOrCreateStorage(world);
        if (storage == null) {
            return;
        }

        ChunkPos pos = self.getPos();
        storage.save(pos, delta);
    }
}