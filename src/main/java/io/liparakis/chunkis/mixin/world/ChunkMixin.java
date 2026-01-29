package io.liparakis.chunkis.mixin.world;

import io.liparakis.chunkis.api.ChunkisDeltaDuck;
import io.liparakis.chunkis.core.BlockInstruction;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.Palette;
import io.liparakis.chunkis.core.VanillaChunkSnapshot;
import net.minecraft.block.BlockState;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkSection;
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

/**
 * Mixin for {@link WorldChunk} that implements player modification tracking and
 * restoration.
 * <p>
 * This mixin adds the ability to:
 * <ul>
 * <li>Track all player-made changes to blocks, block entities, and
 * entities</li>
 * <li>Distinguish between vanilla/generated state and modified state</li>
 * <li>Restore previously saved modifications when chunks are loaded</li>
 * <li>Optimize storage by removing changes that revert to vanilla state</li>
 * </ul>
 * </p>
 * <p>
 * Key components:
 * <ul>
 * <li>{@link ChunkDelta}: Stores all modifications made to the chunk</li>
 * <li>{@link VanillaChunkSnapshot}: Immutable reference to original generated
 * state</li>
 * <li>Restoration flag: Prevents re-tracking during chunk restoration</li>
 * </ul>
 * </p>
 * <p>
 * Thread safety:
 * <ul>
 * <li>Only tracks changes made on the server thread</li>
 * <li>Ignores client-side changes (these are ephemeral)</li>
 * <li>Uses restoration flag to prevent reentrancy during restoration</li>
 * </ul>
 * </p>
 * <p>
 * Change detection filters:
 * <ul>
 * <li>Client-side changes (ignored)</li>
 * <li>Changes during chunk generation (ignored until FULL status)</li>
 * <li>Natural leaf decay (ignored to prevent spam)</li>
 * <li>Wrong thread changes (ignored for thread safety)</li>
 * <li>Restoration operations (ignored to prevent double-tracking)</li>
 * </ul>
 * </p>
 *
 * @see ChunkisDeltaDuck
 * @see ChunkDelta
 * @see VanillaChunkSnapshot
 */
@Mixin(WorldChunk.class)
public class ChunkMixin implements ChunkisDeltaDuck {

    /**
     * Bit mask for extracting chunk-local coordinates (0-15) from world
     * coordinates.
     */
    @Unique
    private static final int COORD_MASK = 15;

    /**
     * Delta tracking all modifications made to this chunk.
     * Initialized immediately and persists for the chunk's lifetime.
     */
    @Unique
    private final ChunkDelta chunkis$delta = new ChunkDelta();

    /**
     * Immutable snapshot of the chunk's vanilla/generated state.
     * Captured during chunk promotion from ProtoChunk to WorldChunk.
     * Used to determine if changes revert to vanilla state.
     */
    @Unique
    private VanillaChunkSnapshot chunkis$vanillaSnapshot;

    /**
     * Flag indicating whether restoration is currently in progress.
     * Prevents re-tracking of changes during chunk restoration to avoid
     * infinite loops and duplicate delta entries.
     */
    @Unique
    private boolean chunkis$isRestoring = false;

    /**
     * Provides access to the chunk's modification delta.
     *
     * @return the ChunkDelta tracking all modifications to this chunk
     */
    @Override
    public ChunkDelta chunkis$getDelta() {
        return chunkis$delta;
    }

    /**
     * Intercepts block state changes to track player modifications.
     * <p>
     * This injection point captures all block changes and applies several filters
     * to determine if the change should be tracked:
     * <ol>
     * <li>Client-side changes are ignored (not persistent)</li>
     * <li>Changes during restoration are ignored (prevents double-tracking)</li>
     * <li>Changes before full chunk generation are ignored</li>
     * <li>Natural leaf decay is ignored (prevents delta spam)</li>
     * <li>Off-thread changes are ignored (thread safety)</li>
     * </ol>
     * </p>
     * <p>
     * For valid changes:
     * <ul>
     * <li>If the new state matches vanilla: delta entry is removed
     * (optimization)</li>
     * <li>If the new state differs from vanilla: delta entry is added/updated</li>
     * </ul>
     * </p>
     *
     * @param pos   the world position of the block being changed
     * @param state the new block state
     * @param moved whether the block is being moved (unused in this method)
     * @param cir   callback info returnable from the mixin injection
     */
    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void chunkis$onSetBlockState(
            BlockPos pos,
            BlockState state,
            boolean moved,
            CallbackInfoReturnable<BlockState> cir) {

        WorldChunk self = (WorldChunk) (Object) this;

        // Early exit: client-side changes
        if (self.getWorld().isClient()) {
            return;
        }

        // Early exit: restoration in progress
        if (chunkis$isRestoring) {
            return;
        }

        // Early exit: chunk not fully generated
        if (!ChunkStatus.FULL.equals(self.getStatus())) {
            return;
        }

        // Early exit: no vanilla snapshot available
        if (chunkis$vanillaSnapshot == null) {
            return;
        }

        // Early exit: leaf natural decay
        if (state.getBlock() instanceof LeavesBlock && io.liparakis.chunkis.core.LeafTickContext.get()) {
            return;
        }

        // Early exit: wrong thread
        if (self.getWorld() instanceof ServerWorld serverWorld) {
            if (serverWorld.getServer().getThread() != Thread.currentThread()) {
                return;
            }
        }

        int localX = pos.getX() & COORD_MASK;
        int localY = pos.getY();
        int localZ = pos.getZ() & COORD_MASK;

        BlockState vanillaState = chunkis$vanillaSnapshot.getVanillaState(localX, localY, localZ);

        // Block reverted to vanilla: remove delta entry
        if (vanillaState != null && vanillaState.equals(state)) {
            chunkis$delta.removeBlockChange(localX, localY, localZ);
            return;
        }

        // Block differs from vanilla: record change
        chunkis$delta.addBlockChange(localX, localY, localZ, state);
    }

    /**
     * Intercepts WorldChunk construction from ProtoChunk to restore saved
     * modifications.
     * <p>
     * This method is called when a chunk is promoted from generation state
     * (ProtoChunk)
     * to runtime state (WorldChunk). It performs two critical tasks:
     * <ol>
     * <li>Captures the vanilla snapshot before any restoration occurs</li>
     * <li>Restores all previously saved modifications from the ProtoChunk's
     * delta</li>
     * </ol>
     * </p>
     * <p>
     * The restoration process:
     * <ul>
     * <li>Sets the restoration flag to prevent change tracking during
     * restoration</li>
     * <li>Applies all block changes from the proto delta</li>
     * <li>Restores block entities (chests, furnaces, etc.)</li>
     * <li>Restores global entities (mobs, items, etc.)</li>
     * <li>Optimizes the delta by removing changes that match vanilla state</li>
     * <li>Marks the proto delta as saved to prevent duplicate restoration</li>
     * </ul>
     * </p>
     *
     * @param world        the server world this chunk belongs to
     * @param proto        the proto chunk containing generation data and saved
     *                     modifications
     * @param entityLoader the entity loader for this chunk
     * @param ci           callback info from the mixin injection
     */
    @Inject(method = "<init>(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/world/chunk/ProtoChunk;Lnet/minecraft/world/chunk/WorldChunk$EntityLoader;)V", at = @At("RETURN"))
    private void chunkis$onConstructFromProto(
            ServerWorld world,
            ProtoChunk proto,
            WorldChunk.EntityLoader entityLoader,
            CallbackInfo ci) {

        WorldChunk self = (WorldChunk) (Object) this;

        // Capture vanilla snapshot before restoration
        chunkis$vanillaSnapshot = new VanillaChunkSnapshot(proto);

        // Early exit: proto has no delta interface
        if (!(proto instanceof ChunkisDeltaDuck duck)) {
            return;
        }

        ChunkDelta protoDelta = duck.chunkis$getDelta();

        // Early exit: nothing to restore
        if (protoDelta.isEmpty()) {
            return;
        }

        try {
            chunkis$isRestoring = true;
            chunkis$applyInstructions(world, self, protoDelta);
        } catch (Exception e) {
            io.liparakis.chunkis.ChunkisMod.LOGGER.error(
                    "Chunkis: Failed to restore chunk {}",
                    proto.getPos(),
                    e);
        } finally {
            chunkis$isRestoring = false;
        }

        protoDelta.markSaved();
    }

    /**
     * Applies all block, block entity, and entity instructions from a delta to the
     * chunk.
     * <p>
     * This method orchestrates the complete restoration process:
     * <ol>
     * <li>Applies all block state changes</li>
     * <li>Skips redundant instructions that match vanilla state (optimization)</li>
     * <li>Copies valid changes to the runtime delta</li>
     * <li>Restores block entities</li>
     * <li>Restores global entities</li>
     * </ol>
     * </p>
     * <p>
     * The method uses silent delta operations during restoration to avoid
     * marking the delta as dirty unnecessarily. If optimizations are performed
     * (redundant instructions skipped), the delta is explicitly marked dirty.
     * </p>
     *
     * @param world      the server world
     * @param chunk      the chunk being restored
     * @param protoDelta the delta containing saved modifications
     */
    @Unique
    private void chunkis$applyInstructions(
            ServerWorld world,
            WorldChunk chunk,
            ChunkDelta protoDelta) {

        Palette<BlockState> sourcePalette = protoDelta.getBlockPalette();
        List<BlockInstruction> instructions = protoDelta.getBlockInstructions();

        // Apply block instructions if present
        if (!instructions.isEmpty()) {
            boolean wasOptimized = false;

            for (BlockInstruction ins : instructions) {
                BlockState state = sourcePalette.get(ins.paletteIndex());

                // Skip invalid palette entries
                if (state == null) {
                    continue;
                }

                // Check if instruction is redundant (matches vanilla)
                BlockState vanilla = chunkis$vanillaSnapshot.getVanillaState(ins.x(), ins.y(), ins.z());
                if (vanilla != null && vanilla.equals(state)) {
                    wasOptimized = true;
                    continue;
                }

                BlockPos pos = chunk.getPos().getBlockPos(ins.x(), ins.y(), ins.z());

                if (!chunkis$applyBlockChange(chunk, ins, state, pos)) {
                    continue;
                }

                // Copy to runtime delta (silent during restoration)
                chunkis$delta.addBlockChange(ins.x(), ins.y(), ins.z(), state, false);
            }

            if (wasOptimized) {
                chunkis$delta.markDirty();
            }
        }

        chunkis$restoreBlockEntities(world, chunk, protoDelta);
        chunkis$restoreGlobalEntities(world, chunk, protoDelta);
    }

    /**
     * Applies a single block change instruction to the chunk.
     * <p>
     * This method directly modifies the chunk section's block state storage,
     * bypassing normal setBlockState to avoid triggering change tracking.
     * Error handling ensures that failures don't crash chunk restoration.
     * </p>
     *
     * @param chunk the chunk being modified
     * @param ins   the block instruction containing position and palette index
     * @param state the block state to apply
     * @param pos   the world position (used for logging)
     * @return true if the change was successfully applied, false on error
     */
    @Unique
    private boolean chunkis$applyBlockChange(
            WorldChunk chunk,
            BlockInstruction ins,
            BlockState state,
            BlockPos pos) {

        try {
            int sectionIndex = chunk.getSectionIndex(ins.y());
            ChunkSection section = chunk.getSection(sectionIndex);

            // Section should exist at this point, but defensive check
            if (section == null) {
                io.liparakis.chunkis.ChunkisMod.LOGGER.warn(
                        "Chunkis: Null section at index {} for chunk {}",
                        sectionIndex,
                        chunk.getPos());
                return false;
            }

            section.setBlockState(ins.x(), ins.y() & 15, ins.z(), state);

            //if (state.getBlock().toString().contains("chest")) {
                // io.liparakis.chunkis.ChunkisMod.LOGGER.info(
                // "Chunkis Debug: Restored {} at {}",
                // state.getBlock(),
                // pos);
            //}

            return true;

        } catch (Throwable t) {
            io.liparakis.chunkis.ChunkisMod.LOGGER.error(
                    "Failed to restore block at {} in chunk {}",
                    pos,
                    chunk.getPos(),
                    t);
            return false;
        }
    }

    /**
     * Restores all block entities (chests, furnaces, signs, etc.) from the delta.
     * <p>
     * Block entities contain additional data beyond block state, such as chest
     * inventories, furnace smelting progress, sign text, etc. This method iterates
     * through all saved block entity data and restores each one.
     * </p>
     *
     * @param world      the server world
     * @param chunk      the chunk being restored
     * @param protoDelta the delta containing saved block entity data
     */
    @Unique
    private void chunkis$restoreBlockEntities(
            ServerWorld world,
            WorldChunk chunk,
            ChunkDelta protoDelta) {

        var blockEntities = protoDelta.getBlockEntities();

        // Early exit: no block entities
        if (blockEntities == null || blockEntities.isEmpty()) {
            return;
        }

        for (var entry : blockEntities.long2ObjectEntrySet()) {
            long packedPos = entry.getLongKey();
            NbtCompound nbt = entry.getValue();

            int x = BlockInstruction.unpackX(packedPos);
            int y = BlockInstruction.unpackY(packedPos);
            int z = BlockInstruction.unpackZ(packedPos);

            chunkis$restoreSingleBlockEntity(world, chunk, x, y, z, nbt);
        }
    }

    /**
     * Restores a single block entity at the specified position.
     * <p>
     * This method:
     * <ol>
     * <li>Verifies the block at the position supports block entities</li>
     * <li>Creates a block entity from the saved NBT data</li>
     * <li>Removes any existing block entity at the position</li>
     * <li>Adds the restored block entity to the chunk</li>
     * <li>Copies the data to the runtime delta (silently)</li>
     * </ol>
     * </p>
     * <p>
     * Includes defensive checks for missing block entity support and creation
     * failures, with special logging for chest blocks to aid debugging.
     * </p>
     *
     * @param world the server world
     * @param chunk the chunk containing the block entity
     * @param x     the local x-coordinate (0-15)
     * @param y     the world y-coordinate
     * @param z     the local z-coordinate (0-15)
     * @param nbt   the saved block entity data
     */
    @Unique
    private void chunkis$restoreSingleBlockEntity(
            ServerWorld world,
            WorldChunk chunk,
            int x,
            int y,
            int z,
            NbtCompound nbt) {

        try {
            BlockPos worldPos = chunk.getPos().getBlockPos(x, y, z);
            BlockState currentState = chunk.getBlockState(worldPos);

            // Early exit: block doesn't support block entities
            if (!currentState.hasBlockEntity()) {
                if (currentState.getBlock().toString().contains("chest")) {
                    io.liparakis.chunkis.ChunkisMod.LOGGER.warn(
                            "Chunkis Debug: Chest at {} missing BE flag! State: {}",
                            worldPos,
                            currentState);
                }
                return;
            }

            BlockEntity be = BlockEntity.createFromNbt(worldPos, currentState, nbt, world.getRegistryManager());

            // Early exit: failed to create block entity
            if (be == null) {
                return;
            }

            chunk.removeBlockEntity(worldPos);
            chunk.addBlockEntity(be);

            // if (currentState.getBlock().toString().contains("chest")) {
            // io.liparakis.chunkis.ChunkisMod.LOGGER.info(
            // "Chunkis Debug: Restored Content for {} at {}",
            // currentState.getBlock(),
            // worldPos);
            // }

            // Copy to runtime delta (silent)
            chunkis$delta.addBlockEntityData(x, y, z, nbt, false);

        } catch (Throwable t) {
            io.liparakis.chunkis.ChunkisMod.LOGGER.error(
                    "Failed to restore block entity at ({},{},{}) in chunk {}",
                    x, y, z,
                    chunk.getPos(),
                    t);
        }
    }

    /**
     * Restores all global entities (mobs, items, minecarts, etc.) from the delta.
     * <p>
     * Global entities are entities not tied to specific blocks, such as:
     * <ul>
     * <li>Mobs (zombies, villagers, etc.)</li>
     * <li>Dropped items</li>
     * <li>Minecarts and boats</li>
     * <li>Projectiles</li>
     * </ul>
     * </p>
     *
     * @param world      the server world
     * @param chunk      the chunk being restored
     * @param protoDelta the delta containing saved entity data
     */
    @Unique
    private void chunkis$restoreGlobalEntities(
            ServerWorld world,
            WorldChunk chunk,
            ChunkDelta protoDelta) {

        List<NbtCompound> globalEntities = protoDelta.getEntitiesList();

        // Early exit: no entities
        if (globalEntities == null || globalEntities.isEmpty()) {
            return;
        }

        // io.liparakis.chunkis.ChunkisMod.LOGGER.info(
        // "Chunkis Debug: Restoring {} global entities for chunk {}",
        // globalEntities.size(),
        // chunk.getPos());

        for (NbtCompound nbt : globalEntities) {
            chunkis$restoreSingleEntity(world, chunk, nbt);
        }
    }

    /**
     * Restores a single entity from NBT data.
     * <p>
     * This method:
     * <ol>
     * <li>Loads the entity (and any passengers) from NBT</li>
     * <li>Checks for duplicate entities by UUID to prevent duplication</li>
     * <li>Spawns the entity in the world if it's not a duplicate</li>
     * <li>Copies the entity data to the runtime delta (silently)</li>
     * </ol>
     * </p>
     * <p>
     * The duplication check is critical to prevent entities from being spawned
     * multiple times during chunk reloads or restoration operations.
     * </p>
     *
     * @param world the server world
     * @param chunk the chunk where the entity should be restored (used for logging)
     * @param nbt   the saved entity data
     */
    @Unique
    private void chunkis$restoreSingleEntity(
            ServerWorld world,
            WorldChunk chunk,
            NbtCompound nbt) {

        try {
            net.minecraft.entity.EntityType.loadEntityWithPassengers(nbt, world, entity -> {
                // Skip if entity already exists (prevents duplication)
                if (world.getEntity(entity.getUuid()) != null) {
                    // io.liparakis.chunkis.ChunkisMod.LOGGER.info(
                    // "Chunkis Debug: Skipping duplicate entity {} ({})",
                    // entity.getType(),
                    // entity.getUuid());
                    return entity;
                }

                world.spawnEntity(entity);
                // io.liparakis.chunkis.ChunkisMod.LOGGER.info(
                // "Chunkis Debug: Restored entity {} ({}) at {}",
                // entity.getType(),
                // entity.getUuid(),
                // entity.getPos());

                return entity;
            });

            // Copy to runtime delta (silent)
            chunkis$delta.getEntitiesList().add(nbt);

        } catch (Exception e) {
            io.liparakis.chunkis.ChunkisMod.LOGGER.error(
                    "Failed to restore entity in chunk {}",
                    chunk.getPos(),
                    e);
        }
    }
}