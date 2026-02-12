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
            ChunkDelta<BlockState, NbtCompound> protoDelta,
            ChunkDelta<BlockState, NbtCompound> runtimeDelta,
            VanillaChunkSnapshot snapshot) {

        RestorationVisitor visitor = new RestorationVisitor(world, chunk, runtimeDelta, snapshot);
        protoDelta.accept(visitor);
        return visitor.wasOptimized;
    }

    /**
     * Internal visitor implementation that applies delta instructions to the target
     * chunk.
     * <p>
     * This visitor acts as the bridge between the implementation-agnostic
     * {@link ChunkDelta}
     * and the specific Minecraft {@link WorldChunk} implementation.
     */
    private static class RestorationVisitor implements ChunkDelta.DeltaVisitor<BlockState, NbtCompound> {
        // The server world where the chunk resides, used for entity spawning
        private final ServerWorld world;
        // The target chunk being restored
        private final WorldChunk chunk;
        // The runtime delta to be populated with restored changes (mirrors the proto
        // delta)
        private final ChunkDelta<BlockState, NbtCompound> runtimeDelta;
        // Snapshot of the original vanilla chunk state, used for optimization
        private final VanillaChunkSnapshot snapshot;
        // Flag tracking if any non-vanilla changes were skipped (optimized out)
        private boolean wasOptimized = false;

        /**
         * Creates a new restoration visitor.
         *
         * @param world        The server world.
         * @param chunk        The chunk to restore.
         * @param runtimeDelta The runtime delta to populate.
         * @param snapshot     The vanilla snapshot for optimization checks.
         */
        RestorationVisitor(ServerWorld world, WorldChunk chunk, ChunkDelta<BlockState, NbtCompound> runtimeDelta,
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

    /**
     * Applies a single block change to the chunk.
     *
     * @param chunk The chunk to modify.
     * @param x     The local X coordinate (0-15).
     * @param y     The local Y coordinate (minY to maxY).
     * @param z     The local Z coordinate (0-15).
     * @param state The new block state to set.
     * @param pos   The absolute block position (for logging).
     * @return True if the block change was applied successfully, false otherwise.
     */
    private static boolean applyBlockChange(
            WorldChunk chunk,
            int x, int y, int z,
            BlockState state,
            BlockPos pos) {

        try {
            // Calculate which vertical section contains this block
            int sectionIndex = chunk.getSectionIndex(y);
            // Retrieve the section storage
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

    /**
     * Restores a single block entity from NBT data.
     * <p>
     * This method handles the creation of the block entity, adding it to the chunk,
     * and updating the runtime delta.
     *
     * @param world        The server world.
     * @param chunk        The chunk to modify.
     * @param x            The local X coordinate.
     * @param y            The local Y coordinate.
     * @param z            The local Z coordinate.
     * @param nbt          The NBT data for the block entity.
     * @param runtimeDelta The runtime delta to update.
     */
    private static void restoreSingleBlockEntity(
            ServerWorld world,
            WorldChunk chunk,
            int x,
            int y,
            int z,
            NbtCompound nbt,
            ChunkDelta<BlockState, NbtCompound> runtimeDelta) {

        try {
            // Convert local chunk coordinates to absolute world coordinates
            BlockPos worldPos = chunk.getPos().getBlockPos(x, y, z);
            // Get the block state currently in the world (should be the one we just placed)
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

            // Create the block entity from NBT data
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

    /**
     * Restores a single global entity from NBT data.
     * <p>
     * This method spawns the entity into the world if it doesn't already exist.
     *
     * @param world        The server world.
     * @param chunk        The chunk to spawn into.
     * @param nbt          The NBT data for the entity.
     * @param runtimeDelta The runtime delta to update.
     */
    private static void restoreSingleEntity(
            ServerWorld world,
            WorldChunk chunk,
            NbtCompound nbt,
            ChunkDelta<BlockState, NbtCompound> runtimeDelta) {

        try {
            // Load the entity and its passengers recursively
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
