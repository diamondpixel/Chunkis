package io.liparakis.chunkis.mixin.storage;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.scanner.NbtScanner;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.storage.RegionBasedStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin for {@link RegionBasedStorage} to suppress vanilla MCA-based chunk
 * storage.
 * <p>
 * This mixin intercepts and cancels various storage operations (write, read,
 * scan, sync)
 * to ensure that the game does not attempt to use the standard region file
 * format,
 * effectively ceding control of chunk persistence to the Chunkis system.
 */
@Mixin(RegionBasedStorage.class)
public class StoragePreventionMixin {

    /**
     * Prevents writing chunk data to the vanilla region files.
     */
    @Inject(method = "write(Lnet/minecraft/util/math/ChunkPos;Lnet/minecraft/nbt/NbtCompound;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void chunkis$blockWrite(ChunkPos pos, NbtCompound nbt, CallbackInfo ci) {
        ci.cancel();
    }

    /**
     * Prevents retrieving chunk NBT from vanilla region files.
     */
    @Inject(method = "getTagAt(Lnet/minecraft/util/math/ChunkPos;)Lnet/minecraft/nbt/NbtCompound;", at = @At("HEAD"), cancellable = true, require = 0)
    private void chunkis$blockGetTagAt(ChunkPos pos, CallbackInfoReturnable<NbtCompound> cir) {
        cir.setReturnValue(null);
    }

    /**
     * Prevents scanning chunks in vanilla region files.
     */
    @Inject(method = "scanChunk(Lnet/minecraft/util/math/ChunkPos;Lnet/minecraft/nbt/scanner/NbtScanner;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void chunkis$blockScanChunk(ChunkPos pos, NbtScanner scanner, CallbackInfo ci) {
        ci.cancel();
    }

    /**
     * Prevents synchronization of vanilla region storage.
     */
    @Inject(method = "sync()V", at = @At("HEAD"), cancellable = true, require = 0)
    private void chunkis$blockSync(CallbackInfo ci) {
        ci.cancel();
    }
}