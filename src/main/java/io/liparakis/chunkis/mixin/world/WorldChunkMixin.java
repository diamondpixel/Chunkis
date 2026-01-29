package io.liparakis.chunkis.mixin.world;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
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

/**
 * Mixin for {@link WorldChunk} to handle saving delta data when a chunk is
 * unloaded.
 * <p>
 * This ensures that any remaining "dirty" {@link ChunkDelta} data is persisted
 * to the .cis storage when the chunk lifecycle ends in the world.
 */
@Mixin(WorldChunk.class)
public class WorldChunkMixin {

    /**
     * Cache of {@link CisStorage} instances per {@link ServerWorld} to avoid
     * frequent context lookups.
     */
    @Unique
    private static final Map<ServerWorld, CisStorage> storageCache = new ConcurrentHashMap<>();

    /**
     * Cached storage instance for this specific chunk manager.
     */
    @Unique
    private CisStorage chunkis$cachedStorage;

    /**
     * Retrieves or creates the {@link CisStorage} instance for the given world.
     *
     * @param world the server world
     * @return the storage instance, or {@code null} if the world is null
     */
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

    /**
     * Injects into the end of the chunk lifecycle to ensure unsaved deltas are
     * persisted.
     * <p>
     * When a chunk is unloaded ({@code loaded == false}), this method checks if the
     * chunk has a dirty {@link ChunkDelta} and saves it to the CIS storage.
     *
     * @param loaded the new loaded state
     * @param ci     the callback info
     */
    @Inject(method = "setLoadedToWorld(Z)V", at = @At("HEAD"))
    private void chunkis$onSetLoadedToWorld(boolean loaded, CallbackInfo ci) {
        // Only care about unload
        if (loaded) {
            return;
        }

        if (!(this instanceof ChunkisDeltaDuck duck)) {
            return;
        }

        ChunkDelta delta = duck.chunkis$getDelta();
        if (delta == null) {
            return;
        }

        if (delta.isEmpty()) {
            delta.markSaved(); // normalize state
            return;
        }

        if (!delta.isDirty()) {
            return;
        }

        WorldChunk self = (WorldChunk) (Object) this;
        if (!(self.getWorld() instanceof ServerWorld world)) {
            return;
        }

        CisStorage storage = chunkis$getOrCreateStorage(world);
        if (storage == null) {
            return;
        }

        ChunkPos pos = self.getPos();
        storage.save(pos, delta);

        delta.markSaved();
    }
}
