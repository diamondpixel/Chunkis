package io.liparakis.chunkis.util;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.ChunkDelta;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Utility for restoring chunks from Chunkis deltas.
 * <p>
 * Handles the application of block changes, block entities, and global entities
 * from a {@link ChunkDelta} to a {@link WorldChunk}.
 */
public final class ChunkRestorer {

    private ChunkRestorer() {
        // Utility class
    }

    /**
     * Applies all instructions from a delta to a chunk.
     *
     * @param world        The server world
     * @param chunk        The chunk to restore
     * @param protoDelta   The source delta (from ProtoChunk/Disk)
     * @param runtimeDelta The runtime delta to populate (the one tracking live
     *                     changes)
     * @param snapshot     The vanilla snapshot for optimization (optional, can be
     *                     null)
     * @return True if optimization occurred (delta became dirty), false otherwise
     */
    @SuppressWarnings("unchecked")
    public static boolean restore(
            ServerWorld world,
            WorldChunk chunk,
            ChunkDelta<BlockState, NbtCompound> protoDelta,
            ChunkDelta<BlockState, NbtCompound> runtimeDelta,
            VanillaChunkSnapshot snapshot) {

        RestorationVisitor visitor = new RestorationVisitor(world, chunk, runtimeDelta, snapshot);
        protoDelta.accept(visitor);
        return visitor.wasOptimized;
    }

    private static class RestorationVisitor implements ChunkDelta.DeltaVisitor<BlockState, NbtCompound> {
        private final ServerWorld world;
        private final WorldChunk chunk;
        private final ChunkDelta runtimeDelta;
        private final VanillaChunkSnapshot snapshot;
        private boolean wasOptimized = false;

        RestorationVisitor(ServerWorld world, WorldChunk chunk, ChunkDelta runtimeDelta,
                VanillaChunkSnapshot snapshot) {
            this.world = world;
            this.chunk = chunk;
            this.runtimeDelta = runtimeDelta;
            this.snapshot = snapshot;
        }

        @Override
        public void visitBlock(int x, int y, int z, BlockState state) {
            // Check optimization (redundancy with vanilla)
            if (snapshot != null) {
                BlockState vanilla = snapshot.getVanillaState(x, y, z);
                if (vanilla != null && vanilla.equals(state)) {
                    wasOptimized = true;
                    return;
                }
            }

            BlockPos pos = chunk.getPos().getBlockPos(x, y, z);

            if (!ChunkRestorer.applyBlockChange(chunk, x, y, z, state, pos)) {
                return;
            }

            // Copy to runtime delta (silent)
            if (runtimeDelta != null) {
                runtimeDelta.addBlockChange(x, y, z, state, false);
            }
        }

        @Override
        public void visitBlockEntity(int x, int y, int z, NbtCompound nbt) {
            ChunkRestorer.restoreSingleBlockEntity(world, chunk, x, y, z, nbt, runtimeDelta);
        }

        @Override
        public void visitEntity(NbtCompound nbt) {
            ChunkRestorer.restoreSingleEntity(world, chunk, nbt, runtimeDelta);
        }
    }

    private static boolean applyBlockChange(
            WorldChunk chunk,
            int x, int y, int z,
            BlockState state,
            BlockPos pos) {

        try {
            int sectionIndex = chunk.getSectionIndex(y);
            ChunkSection section = chunk.getSection(sectionIndex);

            if (section == null) {
                Chunkis.LOGGER.warn(
                        "Chunkis: Null section at index {} for chunk {}",
                        sectionIndex,
                        chunk.getPos());
                return false;
            }

            section.setBlockState(x, y & 15, z, state);
            return true;

        } catch (Throwable t) {
            Chunkis.LOGGER.error(
                    "Failed to restore block at {} in chunk {}",
                    pos,
                    chunk.getPos(),
                    t);
            return false;
        }
    }

    private static void restoreSingleBlockEntity(
            ServerWorld world,
            WorldChunk chunk,
            int x,
            int y,
            int z,
            NbtCompound nbt,
            ChunkDelta runtimeDelta) {

        try {
            BlockPos worldPos = chunk.getPos().getBlockPos(x, y, z);
            BlockState currentState = chunk.getBlockState(worldPos);

            if (!currentState.hasBlockEntity()) {
                if (currentState.getBlock().toString().contains("chest")) {
                    Chunkis.LOGGER.warn(
                            "Chunkis Debug: Chest at {} missing BE flag! State: {}",
                            worldPos,
                            currentState);
                }
                return;
            }

            BlockEntity be = BlockEntity.createFromNbt(worldPos, currentState, nbt, world.getRegistryManager());

            if (be == null) {
                return;
            }

            chunk.removeBlockEntity(worldPos);
            chunk.addBlockEntity(be);

            if (runtimeDelta != null) {
                runtimeDelta.addBlockEntityData(x, y, z, nbt, false);
            }

        } catch (Throwable t) {
            Chunkis.LOGGER.error(
                    "Failed to restore block entity at ({},{},{}) in chunk {}",
                    x, y, z,
                    chunk.getPos(),
                    t);
        }
    }

    private static void restoreSingleEntity(
            ServerWorld world,
            WorldChunk chunk,
            NbtCompound nbt,
            ChunkDelta runtimeDelta) {

        try {
            EntityType.loadEntityWithPassengers(nbt, world, net.minecraft.entity.SpawnReason.LOAD, entity -> {
                if (world.getEntity(entity.getUuid()) != null) {
                    return entity;
                }

                world.spawnEntity(entity);
                return entity;
            });

            if (runtimeDelta != null) {
                runtimeDelta.getEntitiesList().add(nbt);
            }

        } catch (Exception e) {
            Chunkis.LOGGER.error(
                    "Failed to restore entity in chunk {}",
                    chunk.getPos(),
                    e);
        }
    }
}
