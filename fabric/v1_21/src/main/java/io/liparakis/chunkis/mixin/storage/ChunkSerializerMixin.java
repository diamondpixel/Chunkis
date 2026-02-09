package io.liparakis.chunkis.mixin.storage;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.util.CisNbtUtil;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.ChunkSerializer;
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
 * Mixin for {@link ChunkSerializer} to intercept chunk serialization and
 * deserialization.
 * <p>
 * This mixin allows the Chunkis system to save and load its proprietary delta
 * data
 * within the standard Minecraft chunk save process.
 */
@Mixin(ChunkSerializer.class)
public class ChunkSerializerMixin {

    /**
     * The maximum number of block instructions in a {@link ChunkDelta} before it
     * falls back to vanilla serialization.
     */
    @Unique
    private static final int MAX_INSTRUCTION_THRESHOLD = 1000;

    /**
     * Injects into the beginning of the serialize method to handle Chunkis-specific
     * serialization.
     * <p>
     * If a chunk contains a {@link ChunkDelta} that is dirty and below the size
     * threshold,
     * it will be serialized as a minimal "Chunkis" chunk instead of using the
     * vanilla format.
     *
     * @param world the server world
     * @param chunk the chunk to serialize
     * @param cir   the callback info for returning a custom NBT compound
     */
    @Inject(method = "serialize", at = @At("HEAD"), cancellable = true)
    private static void chunkis$onSerialize(ServerWorld world, Chunk chunk, CallbackInfoReturnable<NbtCompound> cir) {
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

        NbtCompound nbt = chunkis$createMinimalNbt(world, chunk);

        // Write our custom delta data using centralized helper
        CisNbtUtil.putDelta(nbt, delta);

        cir.setReturnValue(nbt);
        delta.markSaved();
    }

    /**
     * Creates a minimal NBT structure for a chunk that is being saved as a Chunkis
     * delta.
     * <p>
     * This structure includes basic identity information (position, version) but
     * sets
     * the status to {@code EMPTY} to force terrain regeneration on load.
     *
     * @param world the server world
     * @param chunk the chunk being saved
     * @return a minimal {@link NbtCompound} containing chunk metadata
     */
    @Unique
    private static NbtCompound chunkis$createMinimalNbt(ServerWorld world, Chunk chunk) {
        ChunkPos pos = chunk.getPos();
        int dataVersion = net.minecraft.SharedConstants.getGameVersion().getSaveVersion().getId();

        NbtCompound nbt = CisNbtUtil.createBaseNbt(pos, dataVersion);
        nbt.putLong(CisNbtUtil.LAST_UPDATE_KEY, world.getTime());

        return nbt;
    }

    /**
     * Injects into the end of the deserialize method to handle Chunkis-specific
     * restoration.
     * <p>
     * If the NBT data contains a Chunkis data key, this method will read the delta
     * and force the chunk status to {@link ChunkStatus#EMPTY} to ensure that the
     * world generator applies the delta on top of a fresh terrain.
     *
     * @param world      the server world
     * @param poiStorage the point of interest storage
     * @param key        the storage key
     * @param pos        the chunk position
     * @param nbt        the NBT data to deserialize from
     * @param cir        the callback info containing the returned
     *                   {@link ProtoChunk}
     */
    @Inject(method = "deserialize", at = @At("RETURN"))
    private static void chunkis$onDeserialize(ServerWorld world, PointOfInterestStorage poiStorage, StorageKey key,
            ChunkPos pos, NbtCompound nbt, CallbackInfoReturnable<ProtoChunk> cir) {
        if (!nbt.contains(io.liparakis.chunkis.util.CisNbtUtil.CHUNKIS_DATA_KEY)) {
            return;
        }
        ProtoChunk chunk = cir.getReturnValue();

        // 1. Get storage
        var storage = io.liparakis.chunkis.util.FabricCisStorageHelper.getStorage(world);

        // 2. Load delta
        io.liparakis.chunkis.core.CisChunkPos cisPos = new io.liparakis.chunkis.core.CisChunkPos(pos.x, pos.z);
        ChunkDelta delta = (ChunkDelta) storage.load(cisPos);

        // 3. Attach to ProtoChunk so WorldChunkMixin can find it later
        if (chunk instanceof ChunkisDeltaDuck duck) {
            duck.chunkis$setDelta(delta);
        }

        // 4. Force status to EMPTY to trigger generation/restoration pipeline
        chunk.setStatus(ChunkStatus.EMPTY);
    }
}
