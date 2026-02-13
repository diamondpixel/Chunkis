package io.liparakis.chunkis.mixin.world;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.ServerWorldAccess;
import net.minecraft.world.SpawnHelper;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin for {@link SpawnHelper} to prevent duplicate entity spawning
 * during chunk restoration.
 * <p>
 * When Chunkis restores a chunk from delta storage, it resets the chunk status
 * to trigger generation. This would normally cause {@code populateEntities} to
 * spawn fresh passive mobs on top of entities already restored from the delta,
 * resulting in duplicate entities.
 * <p>
 * This mixin cancels entity population for chunks that have valid delta data,
 * ensuring only the restored entities are present.
 * <p>
 * <b>Scope:</b> Only affects initial passive mob spawning (cows, sheep, etc.).
 * Natural hostile mob spawning is unaffected.
 *
 * @author Liparakis
 * @version 1.0
 */
@Mixin(SpawnHelper.class)
public class SpawnHelperMixin {

    /**
     * Intercepts entity population to prevent duplicate spawning on restored chunks.
     * <p>
     * Chunks with valid Chunkis deltas are skipped, as they already contain
     * restored entities. Fresh chunks without deltas proceed with normal spawning.
     * <p>
     * <b>Important:</b> The {@code world} parameter is {@link ServerWorldAccess},
     * not {@code ServerWorld}. During generation, this is often a {@code ChunkRegion},
     * so casting to {@code ServerWorld} would fail.
     *
     * @param world the world access context (often ChunkRegion during generation)
     * @param biome the biome for spawn logic
     * @param chunkPos the chunk position being populated
     * @param random the random generator for spawning
     * @param ci callback info to cancel population
     */
    @Inject(method = "populateEntities", at = @At("HEAD"), cancellable = true)
    private static void chunkis$onPopulateEntities(
            ServerWorldAccess world,
            RegistryEntry<Biome> biome,
            ChunkPos chunkPos,
            net.minecraft.util.math.random.Random random,
            CallbackInfo ci) {

        Chunk chunk = getChunkAt(world, chunkPos);

        if (chunk == null) {
            return;
        }

        if (shouldCancelSpawning(chunk, chunkPos, ci)) {
            logSpawnCancellation(chunkPos);
        }
    }

    /**
     * Retrieves the chunk at the specified position.
     *
     * @param world the world access
     * @param pos the chunk position
     * @return the chunk, or {@code null} if not loaded
     */
    @Unique
    private static Chunk getChunkAt(ServerWorldAccess world, ChunkPos pos) {
        return world.getChunk(pos.x, pos.z);
    }

    /**
     * Determines if entity spawning should be cancelled for this chunk.
     * <p>
     * Spawning is cancelled if the chunk has a non-empty Chunkis delta,
     * indicating it's a restored chunk with existing entities.
     *
     * @param chunk the chunk being populated
     * @param pos the chunk position (for logging)
     * @param ci callback info to cancel if needed
     * @return {@code true} if spawning was cancelled
     */
    @Unique
    private static boolean shouldCancelSpawning(Chunk chunk, ChunkPos pos, CallbackInfo ci) {
        if (!(chunk instanceof ChunkisDeltaDuck deltaDuck)) {
            return false;
        }

        ChunkDelta<?, ?> delta = deltaDuck.chunkis$getDelta();

        if (delta == null || delta.isEmpty()) {
            return false;
        }

        ci.cancel();
        return true;
    }

    /**
     * Logs entity population cancellation at debug level.
     *
     * @param pos the chunk position
     */
    @Unique
    private static void logSpawnCancellation(ChunkPos pos) {
        if (io.liparakis.chunkis.Chunkis.LOGGER.isDebugEnabled()) {
            io.liparakis.chunkis.Chunkis.LOGGER.debug(
                    "Cancelled vanilla entity population for restored chunk {}",
                    pos
            );
        }
    }
}