package io.liparakis.chunkis.util;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.ChunkDelta;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.NotNull;

/**
 * Utility for restoring chunks from Chunkis deltas.
 * <p>
 * Handles the application of block changes, block entities, and global entities
 * from a {@link ChunkDelta} to a {@link WorldChunk}.
 *
 * @author Liparakis
 * @version 1.0
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
    public static boolean restore(
            ServerWorld world,
            WorldChunk chunk,
            @NotNull ChunkDelta<BlockState, NbtCompound> protoDelta,
            ChunkDelta<BlockState, NbtCompound> runtimeDelta,
            VanillaChunkSnapshot snapshot) {

        RestorationVisitor visitor = new RestorationVisitor(world, chunk, runtimeDelta, snapshot);
        protoDelta.accept(visitor);
        return visitor.wasOptimized;
    }

    // ==================== Visitor Implementation ====================

    private static final class RestorationVisitor implements ChunkDelta.DeltaVisitor<BlockState, NbtCompound> {

        private final ServerWorld world;
        private final WorldChunk chunk;
        private final ChunkDelta<BlockState, NbtCompound> runtimeDelta;
        private final VanillaChunkSnapshot snapshot;

        private final ChunkPos chunkPos;
        private final BlockPos.Mutable mutablePos;

        private boolean wasOptimized;

        RestorationVisitor(ServerWorld world, WorldChunk chunk,
                ChunkDelta<BlockState, NbtCompound> runtimeDelta,
                VanillaChunkSnapshot snapshot) {
            this.world = world;
            this.chunk = chunk;
            this.runtimeDelta = runtimeDelta;
            this.snapshot = snapshot;

            this.chunkPos = chunk.getPos();
            this.mutablePos = new BlockPos.Mutable();
            this.wasOptimized = false;
        }

        @Override
        public void visitBlock(int x, int y, int z, BlockState state) {
            if (snapshot != null) {
                BlockState vanilla = snapshot.getVanillaState(x, y, z);
                if (vanilla != null && vanilla.equals(state)) {
                    wasOptimized = true;
                    return;
                }
            }

            if (ChunkRestorer.applyBlockChange(chunk, chunkPos, x, y, z, state)) {
                if (runtimeDelta != null) {
                    runtimeDelta.addBlockChange(x, y, z, state, false);
                }
            }
        }

        @Override
        public void visitBlockEntity(int x, int y, int z, NbtCompound nbt) {
            ChunkRestorer.restoreSingleBlockEntity(world, chunk, chunkPos, mutablePos, x, y, z, nbt, runtimeDelta);
        }

        @Override
        public void visitEntity(NbtCompound nbt) {
            ChunkRestorer.restoreSingleEntity(world, chunkPos, nbt, runtimeDelta);
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Applies a single block change to a chunk.
     *
     * @param chunk    The chunk to modify.
     * @param chunkPos The position of the chunk (passed to avoid repeated calls).
     * @param x        Local X coordinate (0-15).
     * @param y        Local Y coordinate.
     * @param z        Local Z coordinate (0-15).
     * @param state    The new block state.
     * @return True if successful, false if section was missing.
     */
    private static boolean applyBlockChange(
            WorldChunk chunk,
            ChunkPos chunkPos,
            int x, int y, int z,
            BlockState state) {

        try {
            // Retrieve section index using Y coordinate optimization
            int sectionIndex = chunk.getSectionIndex(y);
            ChunkSection section = chunk.getSection(sectionIndex);

            if (section == null) {
                Chunkis.LOGGER.warn(
                        "Chunkis: Null section at index {} for chunk {}",
                        sectionIndex, chunkPos);
                return false;
            }

            // Apply state change directly to section for performance
            section.setBlockState(x, y & 15, z, state);
            return true;

        } catch (Throwable t) {
            BlockPos pos = chunkPos.getBlockPos(x, y, z);
            Chunkis.LOGGER.error(
                    "Failed to restore block at {} in chunk {}",
                    pos, chunkPos, t);
            return false;
        }
    }

    /**
     * Restores a single block entity from NBT.
     *
     * @param world        The server world.
     * @param chunk        The chunk to modify.
     * @param chunkPos     The chunk position.
     * @param mutablePos   Mutable block position for reuse.
     * @param x            Local X coordinate.
     * @param y            Local Y coordinate.
     * @param z            Local Z coordinate.
     * @param nbt          The block entity NBT data.
     * @param runtimeDelta The runtime delta to update (optional).
     */
    private static void restoreSingleBlockEntity(
            ServerWorld world,
            WorldChunk chunk,
            ChunkPos chunkPos,
            BlockPos.Mutable mutablePos,
            int x, int y, int z,
            NbtCompound nbt,
            ChunkDelta<BlockState, NbtCompound> runtimeDelta) {

        try {
            mutablePos.set(chunkPos.getStartX() + x, y, chunkPos.getStartZ() + z);
            BlockState currentState = chunk.getBlockState(mutablePos);

            // Validate that the block at this position actually supports a block entity
            if (!currentState.hasBlockEntity()) {
                if (currentState.isOf(Blocks.CHEST) || currentState.isOf(Blocks.TRAPPED_CHEST)) {
                    Chunkis.LOGGER.warn(
                            "Chunkis Debug: Chest at {} missing BE flag! State: {}",
                            mutablePos, currentState);
                }
                return;
            }

            // Create and validate block entity
            BlockEntity be = BlockEntity.createFromNbt(mutablePos, currentState, nbt, world.getRegistryManager());
            if (be == null) {
                return;
            }

            // Replace existing block entity
            chunk.removeBlockEntity(mutablePos);
            chunk.addBlockEntity(be);

            // Update runtime delta if tracking is active
            if (runtimeDelta != null) {
                runtimeDelta.addBlockEntityData(x, y, z, nbt, false);
            }

        } catch (Throwable t) {
            Chunkis.LOGGER.error(
                    "Failed to restore block entity at ({},{},{}) in chunk {}",
                    x, y, z, chunkPos, t);
        }
    }

    /**
     * Restores a single entity from NBT.
     *
     * @param world        The server world.
     * @param chunkPos     The chunk position (for logging).
     * @param nbt          The entity NBT data.
     * @param runtimeDelta The runtime delta to update (optional).
     */
    private static void restoreSingleEntity(
            ServerWorld world,
            ChunkPos chunkPos,
            NbtCompound nbt,
            ChunkDelta<BlockState, NbtCompound> runtimeDelta) {

        try {
            // Load and spawn entity with passengers
            EntityType.loadEntityWithPassengers(nbt, world, entity -> {
                // Prevent duplicate spawning if UUID already exists
                if (world.getEntity(entity.getUuid()) != null) {
                    return entity;
                }
                world.spawnEntity(entity);
                return entity;
            });

            // Update runtime delta if tracking is active
            if (runtimeDelta != null) {
                runtimeDelta.getEntitiesList().add(nbt);
            }

        } catch (Exception e) {
            Chunkis.LOGGER.error("Failed to restore entity in chunk {}", chunkPos, e);
        }
    }
}