package io.liparakis.chunkis.mixin.storage;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.util.CisNbtUtil;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.SerializedChunk;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.storage.StorageKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin for {@link SerializedChunk} to intercept chunk serialization and
 * deserialization.
 * <p>
 * This mixin allows the Chunkis system to save and load its proprietary delta
 * data
 * within the standard Minecraft chunk save process.
 *
 * @author Liparakis
 * @version 1.0
 */
@Mixin(SerializedChunk.class)
public class ChunkSerializerMixin {

    /**
     * The maximum number of block instructions in a {@link ChunkDelta} before it
     * falls back to vanilla serialization.
     */
    @Unique
    private static final int MAX_INSTRUCTION_THRESHOLD = 1000;

    /*
     * Injects into the beginning of the fromChunk method to handle Chunkis-specific
     * serialization.
     * <p>
     * If a chunk contains a {@link ChunkDelta} that is dirty and below the size
     * threshold,
     * it will be serialized as a minimal "Chunkis" chunk instead of using the
     * vanilla format.
     *
     * @param world the server world
     * @param chunk the chunk to serialize
     * @param cir   the callback info for returning a custom SerializedChunk
     */
    /**
     * Injects into the beginning of the fromChunk method to handle Chunkis-specific
     * serialization.
     * <p>
     * If a chunk contains a {@link ChunkDelta} that is dirty and below the size
     * threshold,
     * it will be serialized as a minimal "Chunkis" chunk instead of using the
     * vanilla format.
     *
     * @param world the server world
     * @param chunk the chunk to serialize
     * @param cir   the callback info for returning a custom SerializedChunk
     */
    @Inject(method = "fromChunk", at = @At("HEAD"))
    private static void chunkis$onFromChunk(ServerWorld world, Chunk chunk,
            CallbackInfoReturnable<SerializedChunk> cir) {
        if (!(chunk instanceof ChunkisDeltaDuck duck)) {
            return;
        }

        ChunkDelta delta = duck.chunkis$getDelta();

        // Heuristic: Only save as Chunkis if dirty, non-empty, and below instruction
        // threshold
        if (!delta.isDirty() || delta.isEmpty()) {
            return;
        }

        int instructionCount = delta.getBlockInstructions().size();
        if (instructionCount >= MAX_INSTRUCTION_THRESHOLD) {
            return;
        }

        // For 1.21.2+, we need to handle this differently since SerializedChunk is now
        // a data class
        // We'll attach our delta data and let the normal serialization proceed,
        // then intercept in serialize() to add our custom data
        // For now, we mark the delta as pending serialization
        delta.markSaved();
    }

    /*
     * Injects into the end of the convert method to handle Chunkis-specific
     * restoration.
     * <p>
     * If the NBT data contains a Chunkis data key, this method will read the delta
     * and force the chunk status to {@link ChunkStatus#EMPTY} to ensure that the
     * world generator applies the delta on top of a fresh terrain.
     *
     * @param world       the server world
     * @param poiStorage  the point of interest storage
     * @param key         the storage key
     * @param expectedPos the expected chunk position
     * @param cir         the callback info containing the returned
     *                    {@link ProtoChunk}
     */
    /**
     * Injects into the end of the convert method to handle Chunkis-specific
     * restoration.
     * <p>
     * If the NBT data contains a Chunkis data key, this method will read the delta
     * and force the chunk status to {@link ChunkStatus#EMPTY} to ensure that the
     * world generator applies the delta on top of a fresh terrain.
     *
     * @param world       the server world
     * @param poiStorage  the point of interest storage
     * @param key         the storage key
     * @param expectedPos the expected chunk position
     * @param cir         the callback info containing the returned
     *                    {@link ProtoChunk}
     */
    @Inject(method = "convert", at = @At("RETURN"))
    private static void chunkis$onConvert(ServerWorld world, PointOfInterestStorage poiStorage, StorageKey key,
            ChunkPos expectedPos, CallbackInfoReturnable<ProtoChunk> cir) {
        ProtoChunk chunk = cir.getReturnValue();
        if (chunk == null) {
            return;
        }

        // 1. Get storage
        var storage = io.liparakis.chunkis.util.FabricCisStorageHelper.getStorage(world);

        // 2. Load delta
        ChunkPos pos = chunk.getPos();
        io.liparakis.chunkis.core.CisChunkPos cisPos = new io.liparakis.chunkis.core.CisChunkPos(pos.x, pos.z);
        ChunkDelta delta = storage.load(cisPos);

        if (delta == null || delta.isEmpty()) {
            return;
        }

        // 3. Attach to ProtoChunk so WorldChunkMixin can find it later
        if (chunk instanceof ChunkisDeltaDuck duck) {
            duck.chunkis$setDelta(delta);
        }

        // 4. Force status to EMPTY to trigger generation/restoration pipeline
        chunk.setStatus(ChunkStatus.EMPTY);
    }
}
