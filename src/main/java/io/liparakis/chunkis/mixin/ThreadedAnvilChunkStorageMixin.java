package io.liparakis.chunkis.mixin;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.ChunkisDeltaDuck;
import io.liparakis.chunkis.storage.CisStorage;
import net.minecraft.SharedConstants;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Mixin(ServerChunkLoadingManager.class)
public abstract class ThreadedAnvilChunkStorageMixin {

    @Inject(method = "close", at = @At("HEAD"))
    private void chunkis$onClose(CallbackInfo ci) {
        if (cisStorage != null) {
            cisStorage.close();
        }
    }

    @Shadow
    @Final
    ServerWorld world;

    @Unique
    private static final int GAME_DATA_VERSION = SharedConstants.getGameVersion().getSaveVersion().getId();

    @Unique
    private CisStorage cisStorage;

    @Unique
    private final ThreadLocal<BlockPos.Mutable> blockPosCache = ThreadLocal.withInitial(BlockPos.Mutable::new);

    @Inject(method = "getUpdatedChunkNbt(Lnet/minecraft/util/math/ChunkPos;)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"), cancellable = true)
    private void chunkis$onGetUpdatedChunkNbt(
            ChunkPos pos,
            CallbackInfoReturnable<CompletableFuture<Optional<NbtCompound>>> cir) {

        if (cisStorage == null) {
            cisStorage = new CisStorage(world);
        }

        ChunkDelta delta = cisStorage.load(pos);
        if (delta == null)
            return;

        NbtCompound nbt = new NbtCompound();
        nbt.putInt("DataVersion", GAME_DATA_VERSION);
        nbt.putString("Status", "minecraft:empty");
        nbt.putInt("xPos", pos.x);
        nbt.putInt("zPos", pos.z);

        NbtCompound chunkisData = new NbtCompound();
        delta.writeNbt(chunkisData);
        nbt.put("ChunkisData", chunkisData);

        cir.setReturnValue(CompletableFuture.completedFuture(Optional.of(nbt)));
    }

    @Inject(method = "save(Lnet/minecraft/server/world/ChunkHolder;)Z", at = @At("HEAD"), cancellable = true)
    private void chunkis$onSave(ChunkHolder chunkHolder, CallbackInfoReturnable<Boolean> cir) {
        // Direct access to the current chunk - bypassing the future chain for our own
        // RAM state
        WorldChunk worldChunk = chunkHolder.getWorldChunk();

        if (!(worldChunk instanceof ChunkisDeltaDuck duck)) {
            // Not a WorldChunk or not our duck - don't suppress Minecraft save if we don't
            // handle it
            return;
        }

        ChunkDelta delta = duck.chunkis$getDelta();

        // Always capture BlockEntity data (furnaces, etc.)
        chunkis$captureBlockEntities(worldChunk, delta);

        if (delta.isDirty()) {
            if (cisStorage == null) {
                cisStorage = new CisStorage(world);
            }
            ChunkPos pos = worldChunk.getPos();
            cisStorage.save(pos, delta);
            io.liparakis.chunkis.ChunkisMod.LOGGER.info("[Chunkis] Saved CIS chunk {}", pos);
        }

        cir.setReturnValue(true); // Suppress vanilla save for chunks we handled
    }

    @Unique
    private void chunkis$captureBlockEntities(WorldChunk chunk, ChunkDelta delta) {
        // Iterate ALL block entities in the chunk to ensure we capture inventory
        // changes
        // even if the block state itself hasn't changed (e.g. putting items in a
        // chest).
        // This also supports capturing NBT for generated blocks that weren't placed by
        // the player.
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be.isRemoved())
                continue;

            // Only capture valid block entities
            NbtCompound nbt = be.createNbtWithId(chunk.getWorld().getRegistryManager());
            BlockPos pos = be.getPos();

            // Convert to local coordinates for ChunkDelta
            int localX = pos.getX() & 15;
            int localY = pos.getY();
            int localZ = pos.getZ() & 15;

            delta.addBlockEntityData(localX, localY, localZ, nbt);
        }
    }
}