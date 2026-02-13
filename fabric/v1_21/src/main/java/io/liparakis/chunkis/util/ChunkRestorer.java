package io.liparakis.chunkis.util;

import io.liparakis.chunkis.Chunkis;
import io.liparakis.chunkis.core.ChunkDelta;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;

/**
 * Utility for restoring chunks from Chunkis delta data.
 *
 * <p>Applies player modifications stored in {@link ChunkDelta} to freshly generated
 * {@link WorldChunk} instances. This restoration process is critical for maintaining
 * player-made changes across world reloads and chunk regeneration.
 *
 * <p><b>Restoration Process:</b>
 * <ol>
 *   <li>Validates each delta entry against vanilla snapshot (optimization)</li>
 *   <li>Applies block state changes to chunk sections</li>
 *   <li>Restores block entities (chests, furnaces, etc.)</li>
 *   <li>Spawns entities saved in the delta</li>
 *   <li>Copies validated changes to runtime delta for future saves</li>
 * </ol>
 *
 * <p><b>Thread Safety:</b> All methods must execute on the server thread.
 * Entity spawning and chunk modification are not thread-safe.
 *
 * <p><b>Optimization:</b> Uses {@link VanillaChunkSnapshot} to skip redundant
 * restorations when delta matches worldgen output (automatic delta cleanup).
 *
 * @author Liparakis
 * @version 2.0
 */
public final class ChunkRestorer {

    private static final Logger LOGGER = Chunkis.LOGGER;
    private static final int SECTION_Y_MASK = 15;

    private ChunkRestorer() {
        throw new AssertionError("Utility class cannot be instantiated");
    }

    /**
     * Restores a chunk from delta data with automatic optimization.
     *
     * <p>Applies all modifications from the proto delta to the world chunk,
     * while simultaneously validating against the vanilla snapshot. Redundant
     * entries (where delta matches vanilla) are automatically cleaned up.
     *
     * <p><b>Parameters:</b>
     * <ul>
     *   <li><b>protoDelta</b>: Source delta loaded from disk/memory</li>
     *   <li><b>runtimeDelta</b>: Target delta for tracking active modifications</li>
     *   <li><b>snapshot</b>: Vanilla state for optimization checks</li>
     * </ul>
     *
     * @param world        the server world context
     * @param chunk        the chunk to restore modifications into
     * @param protoDelta   the source delta containing saved modifications
     * @param runtimeDelta the runtime delta to populate with validated changes
     * @param snapshot     the vanilla worldgen snapshot for optimization
     * @return true if any redundant entries were detected and cleaned up
     */
    public static boolean restore(
            ServerWorld world,
            WorldChunk chunk,
            ChunkDelta<BlockState, NbtCompound> protoDelta,
            ChunkDelta<BlockState, NbtCompound> runtimeDelta,
            VanillaChunkSnapshot snapshot) {

        var visitor = new RestorationVisitor(world, chunk, runtimeDelta, snapshot);
        protoDelta.accept(visitor);
        return visitor.wasOptimized;
    }

    /**
     * Visitor implementation for processing delta entries during restoration.
     *
     * <p>Validates each entry against the vanilla snapshot before applying,
     * enabling automatic cleanup of redundant modifications. Copies validated
     * entries to the runtime delta for future persistence.
     *
     * <p><b>Optimization Logic:</b> If a delta entry exactly matches the vanilla
     * state, it's skipped (not copied to runtime delta), effectively removing
     * the redundant modification. This keeps deltas minimal over time.
     */
    private static class RestorationVisitor implements ChunkDelta.DeltaVisitor<BlockState, NbtCompound> {
        private final ServerWorld world;
        private final WorldChunk chunk;
        private final ChunkPos chunkPosition;
        private final ChunkDelta<BlockState, NbtCompound> runtimeDelta;
        private final VanillaChunkSnapshot snapshot;
        private boolean wasOptimized = false;

        RestorationVisitor(
                ServerWorld world,
                WorldChunk chunk,
                ChunkDelta<BlockState, NbtCompound> runtimeDelta,
                VanillaChunkSnapshot snapshot) {
            this.world = world;
            this.chunk = chunk;
            this.chunkPosition = chunk.getPos();
            this.runtimeDelta = runtimeDelta;
            this.snapshot = snapshot;
        }

        @Override
        public void visitBlock(int localX, int localY, int localZ, BlockState state) {
            if (isRedundantWithVanilla(localX, localY, localZ, state)) {
                wasOptimized = true;
                return;
            }

            if (!applyBlockStateToChunk(localX, localY, localZ, state)) {
                return;
            }

            copyToRuntimeDelta(localX, localY, localZ, state);
        }

        @Override
        public void visitBlockEntity(int localX, int localY, int localZ, NbtCompound nbt) {
            restoreSingleBlockEntity(world, chunk, chunkPosition, localX, localY, localZ, nbt, runtimeDelta);
        }

        @Override
        public void visitEntity(NbtCompound nbt) {
            restoreSingleEntity(world, chunkPosition, nbt, runtimeDelta);
        }

        /**
         * Checks if a block state matches vanilla worldgen (optimization candidate).
         */
        private boolean isRedundantWithVanilla(int localX, int localY, int localZ, BlockState state) {
            if (snapshot == null) {
                return false;
            }

            var vanillaState = snapshot.getVanillaState(localX, localY, localZ);
            return vanillaState != null && vanillaState.equals(state);
        }

        /**
         * Applies a block state to the chunk's section storage.
         */
        private boolean applyBlockStateToChunk(int localX, int localY, int localZ, BlockState state) {
            var worldPosition = chunkPosition.getBlockPos(localX, localY, localZ);
            return applyBlockChange(chunk, chunkPosition, localX, localY, localZ, state, worldPosition);
        }

        /**
         * Copies a validated block change to the runtime delta (silent mode).
         */
        private void copyToRuntimeDelta(int localX, int localY, int localZ, BlockState state) {
            if (runtimeDelta != null) {
                runtimeDelta.addBlockChange(localX, localY, localZ, state, false);
            }
        }
    }

    /**
     * Applies a single block state change to a chunk section.
     *
     * <p>Directly modifies the chunk's palette storage for performance.
     * Validates section existence before modification to prevent crashes.
     *
     * @param chunk         the chunk to modify
     * @param chunkPosition the chunk's position (for logging)
     * @param localX        local X coordinate (0-15)
     * @param localY        absolute Y coordinate
     * @param localZ        local Z coordinate (0-15)
     * @param state         the new block state
     * @param worldPosition absolute position (for logging)
     * @return true if successful, false if section was missing
     */
    private static boolean applyBlockChange(
            WorldChunk chunk,
            ChunkPos chunkPosition,
            int localX,
            int localY,
            int localZ,
            BlockState state,
            BlockPos worldPosition) {

        try {
            int sectionIndex = chunk.getSectionIndex(localY);
            ChunkSection section = chunk.getSection(sectionIndex);

            if (section == null) {
                LOGGER.warn("Null section at index {} for chunk {}", sectionIndex, chunkPosition);
                return false;
            }

            int sectionLocalY = localY & SECTION_Y_MASK;
            section.setBlockState(localX, sectionLocalY, localZ, state);
            return true;

        } catch (Exception error) {
            LOGGER.error("Failed to restore block at {} in chunk {}", worldPosition, chunkPosition, error);
            return false;
        }
    }

    /**
     * Restores a block entity from NBT data.
     *
     * <p>Validates that the block state at the position supports block entities
     * before attempting restoration. This prevents errors when worldgen changes
     * cause block entity positions to become invalid.
     *
     * <p><b>Note:</b> The block entity is removed and re-added rather than
     * updated in place to ensure all NBT data is properly applied.
     *
     * @param world         the server world
     * @param chunk         the chunk containing the block entity
     * @param chunkPosition the chunk's position (for logging)
     * @param localX        local X coordinate (0-15)
     * @param localY        absolute Y coordinate
     * @param localZ        local Z coordinate (0-15)
     * @param nbt           the block entity NBT data
     * @param runtimeDelta  the runtime delta to update (may be null)
     */
    private static void restoreSingleBlockEntity(
            ServerWorld world,
            WorldChunk chunk,
            ChunkPos chunkPosition,
            int localX,
            int localY,
            int localZ,
            NbtCompound nbt,
            ChunkDelta<BlockState, NbtCompound> runtimeDelta) {

        try {
            var worldPosition = chunkPosition.getBlockPos(localX, localY, localZ);
            var currentState = chunk.getBlockState(worldPosition);

            if (!currentState.hasBlockEntity()) {
                LOGGER.debug("Skipping block entity restoration at {} - state {} does not support block entities",
                        worldPosition, currentState);
                return;
            }

            var blockEntity = BlockEntity.createFromNbt(
                    worldPosition,
                    currentState,
                    nbt,
                    world.getRegistryManager()
            );

            if (blockEntity == null) {
                LOGGER.warn("Failed to create block entity from NBT at {}", worldPosition);
                return;
            }

            chunk.removeBlockEntity(worldPosition);
            chunk.addBlockEntity(blockEntity);

            if (runtimeDelta != null) {
                runtimeDelta.addBlockEntityData(localX, localY, localZ, nbt, false);
            }

        } catch (Exception error) {
            LOGGER.error("Failed to restore block entity at ({},{},{}) in chunk {}",
                    localX, localY, localZ, chunkPosition, error);
        }
    }

    /**
     * Restores and spawns an entity from NBT data.
     *
     * <p>Checks for UUID conflicts before spawning to prevent duplicate entities.
     * Uses Minecraft's built-in entity loading mechanism to handle all entity
     * types, including those with passengers.
     *
     * <p><b>Thread Safety:</b> Must be called on server thread as entity
     * spawning modifies world state.
     *
     * @param world         the server world to spawn into
     * @param chunkPos the chunk's position (for logging)
     * @param nbt           the entity NBT data
     * @param runtimeDelta  the runtime delta to update (may be null)
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