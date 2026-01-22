package io.liparakis.chunkis.mixin;

import io.liparakis.chunkis.core.BlockInstruction;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.Palette;
import net.minecraft.block.BlockState;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(WorldChunk.class)
public class ChunkMixin implements ChunkisDeltaDuck {

    @Unique
    private static final int COORD_MASK = 15;

    @Unique
    private final ChunkDelta chunkis$delta = new ChunkDelta();

    @Unique
    private boolean chunkis$isRestoring = false;

    @Override
    public ChunkDelta chunkis$getDelta() {
        return chunkis$delta;
    }

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void chunkis$onSetBlockState(BlockPos pos, BlockState state, boolean moved,
            CallbackInfoReturnable<BlockState> cir) {

        WorldChunk self = (WorldChunk) (Object) this;

        // Strictly ignore Client-side changes (Visuals, Prediction, Sync)
        if (self.getWorld().isClient()) {
            return;
        }

        // Fast path: early exit checks
        if (chunkis$isRestoring || cir.getReturnValue() == null) {
            return;
        }

        // Fast path: status check
        if (!ChunkStatus.FULL.equals(self.getStatus())) {
            return;
        }

        // Thread check - Ensure we only record changes from the Main Server Thread
        if (self.getWorld() instanceof ServerWorld serverWorld) {
            Thread serverThread = serverWorld.getServer().getThread();
            if (serverThread != Thread.currentThread()) {
                return;
            }
        }

        // Optimized leaf check - use ThreadLocal flag instead of stack trace
        if (state.getBlock() instanceof LeavesBlock && io.liparakis.chunkis.core.LeafTickContext.get()) {
            return;
        }

        chunkis$delta.addBlockChange(pos.getX() & COORD_MASK, pos.getY(), pos.getZ() & COORD_MASK, state);
    }

    @Inject(method = "<init>(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/world/chunk/ProtoChunk;Lnet/minecraft/world/chunk/WorldChunk$EntityLoader;)V", at = @At("RETURN"))
    private void chunkis$onConstructFromProto(ServerWorld world, ProtoChunk proto, WorldChunk.EntityLoader entityLoader,
            CallbackInfo ci) {
        if (!(proto instanceof ChunkisDeltaDuck duck)) {
            return;
        }

        ChunkDelta protoDelta = duck.chunkis$getDelta();
        if (protoDelta.isEmpty()) {
            return;
        }

        WorldChunk self = (WorldChunk) (Object) this;

        try {
            chunkis$isRestoring = true;
            chunkis$applyInstructions(world, self, protoDelta);
        } catch (Exception e) {
            io.liparakis.chunkis.ChunkisMod.LOGGER.error("Chunkis: Failed to restore chunk {}", proto.getPos(), e);
        } finally {
            chunkis$isRestoring = false;
        }
    }

    @Unique
    private void chunkis$applyInstructions(ServerWorld world, WorldChunk chunk, ChunkDelta protoDelta) {
        Palette<BlockState> sourcePalette = protoDelta.getBlockPalette();
        List<BlockInstruction> instructions = protoDelta.getBlockInstructions();

        int instructionCount = instructions.size();
        if (instructionCount == 0)
            return;

        // Apply instructions
        for (int i = 0; i < instructionCount; i++) {
            BlockInstruction ins = instructions.get(i);
            BlockState state = sourcePalette.get(ins.paletteIndex());

            if (state == null)
                continue;

            BlockPos pos = chunk.getPos().getBlockPos(ins.x(), ins.y(), ins.z());

            try {
                // Set block state using chunk's high-level method to ensure BE metadata and
                // lighting
                // updates
                chunk.setBlockState(pos, state, false);

                if (state.getBlock().toString().contains("chest")) {
                    io.liparakis.chunkis.ChunkisMod.LOGGER.info("Chunkis Debug: Restored {} at {}", state.getBlock(),
                            pos);
                }

                // Copy to target delta
                chunkis$delta.addBlockChange(ins.x(), ins.y(), ins.z(), state);

            } catch (Throwable t) {
                io.liparakis.chunkis.ChunkisMod.LOGGER.error(
                        "Failed to restore block at {} in chunk {}",
                        pos, chunk.getPos(), t);
            }
        }

        // Separate pass: Restore Block Entities independently (Inventory, etc.)
        var blockEntities = protoDelta.getBlockEntities();
        if (blockEntities != null && !blockEntities.isEmpty()) {
            try {
                for (var entry : blockEntities.long2ObjectEntrySet()) {
                    long packedPos = entry.getLongKey();
                    NbtCompound nbt = entry.getValue();

                    int x = BlockInstruction.unpackX(packedPos);
                    int y = BlockInstruction.unpackY(packedPos);
                    int z = BlockInstruction.unpackZ(packedPos);

                    BlockPos worldPos = chunk.getPos().getBlockPos(x, y, z);
                    BlockState currentState = chunk.getBlockState(worldPos);

                    if (currentState.hasBlockEntity()) {
                        // Safe creation: Instantiate directly from NBT to avoid getBlockEntity logic
                        // stalls
                        BlockEntity be = BlockEntity.createFromNbt(worldPos, currentState, nbt,
                                world.getRegistryManager());
                        if (be != null) {
                            chunk.removeBlockEntity(worldPos);
                            chunk.addBlockEntity(be);

                            if (currentState.getBlock().toString().contains("chest")) {
                                io.liparakis.chunkis.ChunkisMod.LOGGER.info(
                                        "Chunkis Debug: Restored Content for {} at {}", currentState.getBlock(),
                                        worldPos);
                            }

                            // Copy to target delta so it persists on next save
                            chunkis$delta.addBlockEntityData(x, y, z, nbt);
                        }
                    } else if (currentState.getBlock().toString().contains("chest")) {
                        io.liparakis.chunkis.ChunkisMod.LOGGER
                                .warn("Chunkis Debug: Chest at {} missing BE flag! State: {}", worldPos, currentState);
                    }
                }
            } catch (Throwable t) {
                io.liparakis.chunkis.ChunkisMod.LOGGER.error("Failed to restore block entities in chunk {}",
                        chunk.getPos(), t);
            }
        }
    }

}