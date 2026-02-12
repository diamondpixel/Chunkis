package io.liparakis.chunkis.mixin.storage;

import io.liparakis.chunkis.Chunkis;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.scanner.NbtScanner;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.storage.RegionBasedStorage;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin for {@link RegionBasedStorage} to suppress vanilla MCA-based chunk storage.
 *
 * <p>This mixin completely disables Minecraft's standard region file system (.mca files)
 * by intercepting and canceling all storage operations. This prevents conflicts between
 * the vanilla chunk storage system and Chunkis's delta-based CIS format.
 *
 * <p><b>Blocked Operations:</b>
 * <ul>
 *   <li>Writing chunk NBT data to region files</li>
 *   <li>Reading chunk NBT data from region files</li>
 *   <li>Scanning chunks for data verification</li>
 *   <li>Synchronizing/flushing region file buffers</li>
 * </ul>
 *
 * <p><b>Important:</b> With this mixin active, all chunk persistence must be handled
 * by the Chunkis system. If Chunkis fails to save data, chunks will be regenerated
 * from worldgen on next load (player modifications will be lost).
 *
 * <p><b>Performance Impact:</b> Minimal - operations are canceled at injection point
 * before any vanilla I/O occurs. This actually improves performance by eliminating
 * redundant disk writes.
 *
 * <p><b>Compatibility:</b> This mixin may conflict with other mods that rely on
 * vanilla region file storage. Such mods should either integrate with Chunkis or
 * be disabled.
 *
 * @author Liparakis
 * @version 2.0
 */
@Mixin(RegionBasedStorage.class)
public class StoragePreventionMixin {

    @Unique
    private static final Logger LOGGER = Chunkis.LOGGER;

    /**
     * Prevents writing chunk data to vanilla region files (.mca).
     *
     * <p>Cancels the write operation before any I/O occurs, ensuring chunks are
     * only persisted through the Chunkis CIS format. This prevents data duplication
     * and format conflicts between vanilla and Chunkis storage systems.
     *
     * <p><b>Side Effect:</b> Vanilla tools that read .mca files (e.g., NBT editors,
     * region file viewers) will not see chunk data. Use Chunkis-compatible tools instead.
     *
     * @param position the chunk position attempting to be written
     * @param nbt      the NBT compound data (unused as operation is canceled)
     * @param ci       callback info for canceling the operation
     */
    @Inject(
            method = "write(Lnet/minecraft/util/math/ChunkPos;Lnet/minecraft/nbt/NbtCompound;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void chunkis$blockWrite(ChunkPos position, NbtCompound nbt, CallbackInfo ci) {
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Blocking vanilla chunk write for {}", position);
        }
        ci.cancel();
    }

    /**
     * Prevents retrieving chunk NBT from vanilla region files.
     *
     * <p>Returns null immediately instead of attempting to read from .mca files.
     * This forces Minecraft's chunk loading system to either use Chunkis-provided
     * NBT (via ThreadedAnvilChunkStorageMixin) or generate the chunk from scratch.
     *
     * <p><b>Behavior:</b> Returning null tells Minecraft "this chunk doesn't exist
     * in storage," triggering worldgen. The Chunkis system intercepts this and
     * provides its own NBT with delta data before worldgen occurs.
     *
     * @param position the chunk position attempting to be read
     * @param cir      callback containing the return value (set to null)
     */
    @Inject(
            method = "getTagAt(Lnet/minecraft/util/math/ChunkPos;)Lnet/minecraft/nbt/NbtCompound;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void chunkis$blockGetTagAt(ChunkPos position, CallbackInfoReturnable<NbtCompound> cir) {
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Blocking vanilla chunk read for {}", position);
        }
        cir.setReturnValue(null);
    }

    /**
     * Prevents scanning chunks in vanilla region files.
     *
     * <p>Chunk scanning is used by Minecraft for data migration, validation, and
     * upgrade operations. Blocking it prevents vanilla systems from attempting to
     * "fix" or "upgrade" chunks that don't exist in the region file format.
     *
     * <p><b>Note:</b> This may cause warnings in logs during world upgrades or
     * when using /data commands, as Minecraft will be unable to scan chunk data.
     *
     * @param position the chunk position attempting to be scanned
     * @param scanner  the NBT scanner (unused as operation is canceled)
     * @param ci       callback info for canceling the operation
     */
    @Inject(
            method = "scanChunk(Lnet/minecraft/util/math/ChunkPos;Lnet/minecraft/nbt/scanner/NbtScanner;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void chunkis$blockScanChunk(ChunkPos position, NbtScanner scanner, CallbackInfo ci) {
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Blocking vanilla chunk scan for {}", position);
        }
        ci.cancel();
    }

    /**
     * Prevents synchronization of vanilla region file storage.
     *
     * <p>Sync operations flush buffered writes to disk and update metadata. Since
     * all chunk data is handled by Chunkis, vanilla sync is unnecessary and would
     * only waste I/O operations.
     *
     * <p><b>Performance Benefit:</b> Prevents periodic disk flushes of empty region
     * files, reducing unnecessary I/O overhead during gameplay.
     *
     * @param ci callback info for canceling the operation
     */
    @Inject(
            method = "sync()V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void chunkis$blockSync(CallbackInfo ci) {
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Blocking vanilla storage sync");
        }
        ci.cancel();
    }
}