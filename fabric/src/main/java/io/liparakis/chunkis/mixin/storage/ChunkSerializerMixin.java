package io.liparakis.chunkis.mixin.storage;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.ChunkDelta;
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
     * The NBT key used to store Chunkis-specific delta data.
     */
    @Unique
    private static final String CHUNKIS_DATA_KEY = "ChunkisData";

    /**
     * The status string used to represent an empty chunk status in NBT.
     */
    @Unique
    private static final String STATUS_EMPTY = "minecraft:empty";

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

        // Write our custom delta data
        NbtCompound chunkisData = new NbtCompound();
        delta.writeNbt(chunkisData);
        nbt.put(CHUNKIS_DATA_KEY, chunkisData);

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
        NbtCompound nbt = new NbtCompound();
        ChunkPos pos = chunk.getPos();

        nbt.putInt("DataVersion", net.minecraft.SharedConstants.getGameVersion().getSaveVersion().getId());
        nbt.putInt("xPos", pos.x);
        nbt.putInt("zPos", pos.z);
        nbt.putLong("LastUpdate", world.getTime());
        nbt.putString("Status", STATUS_EMPTY);

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
        if (!nbt.contains(CHUNKIS_DATA_KEY)) {
            return;
        }
        ProtoChunk chunk = cir.getReturnValue();
        if (!(chunk instanceof ChunkisDeltaDuck duck)) {
            return;
        }
        ChunkDelta delta = duck.chunkis$getDelta();
        NbtCompound data = nbt.getCompound(CHUNKIS_DATA_KEY);
        delta.readNbt(data);

        chunk.setStatus(ChunkStatus.EMPTY);
    }
}