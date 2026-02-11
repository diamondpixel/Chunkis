package io.liparakis.chunkis.mixin.world;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.SpawnHelper;
import net.minecraft.world.ServerWorldAccess;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin for {@link SpawnHelper} to prevent double-spawning of entities during
 * chunk generation/population.
 * <p>
 * Problem: When Chunkis restores a chunk, it sets the status to EMPTY to force
 * generation.
 * This causes {@link SpawnHelper#populateEntities} to run again, spawning a
 * fresh set of entities
 * on top of the ones restored from the CIS delta.
 * <p>
 * Solution: Intercept {@code populateEntities} and cancel it if the chunk being
 * populated
 * has a valid Chunkis delta (meaning it's a restored chunk, not a new one).
 *
 * @author Liparakis
 * @version 1.0
 */
@Mixin(SpawnHelper.class)
public class SpawnHelperMixin {

    /**
     * Intercepts the entity population phase of chunk generation.
     * <p>
     * Note: This only affects the initial generation of passive mobs (cows, sheep,
     * etc.).
     * It does NOT affect natural spawning of monsters (zombies, etc.) which is
     * handled
     * strictly by {@link net.minecraft.world.SpawnHelper}.
     */
    @Inject(method = "populateEntities", at = @At("HEAD"), cancellable = true)
    private static void chunkis$onPopulateEntities(
            ServerWorldAccess world,
            RegistryEntry<Biome> biome,
            ChunkPos chunkPos,
            net.minecraft.util.math.random.Random random,
            CallbackInfo ci) {

        // Logic fix: Do NOT check for ServerWorld.
        // During generation, 'world' is often a ChunkRegion (which is ServerWorldAccess
        // but NOT ServerWorld).
        // Using ServerWorldAccess directly ensures we catch both cases.

        // Retrieve the chunk being populated
        Chunk chunk = world.getChunk(chunkPos.x, chunkPos.z);

        if (chunk == null)
            return;

        // Check if this chunk is managed by Chunkis
        if (chunk instanceof ChunkisDeltaDuck duck) {
            ChunkDelta<?, ?> delta = duck.chunkis$getDelta();

            // If it has a delta, it means we are restoring it.
            // Therefore, we should NOT spawn fresh entities.
            if (delta != null && !delta.isEmpty()) {
                ci.cancel();

                if (io.liparakis.chunkis.Chunkis.LOGGER.isDebugEnabled()) {
                    io.liparakis.chunkis.Chunkis.LOGGER.debug(
                            "Cancelled vanilla entity population for restorative chunk {}",
                            chunkPos);
                }
            }
        }
    }
}
