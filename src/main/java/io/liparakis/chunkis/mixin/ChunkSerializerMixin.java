package io.liparakis.chunkis.mixin;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.ChunkisDeltaDuck;
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

@Mixin(ChunkSerializer.class)
public class ChunkSerializerMixin {

    @Unique
    private static final int MAX_INSTRUCTION_THRESHOLD = 1000;

    @Unique
    private static final String CHUNKIS_DATA_KEY = "ChunkisData";

    @Unique
    private static final String STATUS_EMPTY = "minecraft:empty";

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
        delta.readNbt(nbt.getCompound(CHUNKIS_DATA_KEY));

        // Force chunk regeneration by setting status to EMPTY
        // This ensures the game regenerates terrain, then applies our delta on top
        chunk.setStatus(ChunkStatus.EMPTY);
    }
}