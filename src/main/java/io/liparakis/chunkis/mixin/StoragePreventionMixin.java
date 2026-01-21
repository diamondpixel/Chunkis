package io.liparakis.chunkis.mixin;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.scanner.NbtScanner;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.storage.RegionBasedStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RegionBasedStorage.class)
public class StoragePreventionMixin {

    @Inject(method = "write(Lnet/minecraft/util/math/ChunkPos;Lnet/minecraft/nbt/NbtCompound;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void chunkis$blockWrite(ChunkPos pos, NbtCompound nbt, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "getTagAt(Lnet/minecraft/util/math/ChunkPos;)Lnet/minecraft/nbt/NbtCompound;", at = @At("HEAD"), cancellable = true, require = 0)
    private void chunkis$blockGetTagAt(ChunkPos pos, CallbackInfoReturnable<NbtCompound> cir) {
        cir.setReturnValue(null);
    }

    @Inject(method = "scanChunk(Lnet/minecraft/util/math/ChunkPos;Lnet/minecraft/nbt/scanner/NbtScanner;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void chunkis$blockScanChunk(ChunkPos pos, NbtScanner scanner, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "sync()V", at = @At("HEAD"), cancellable = true, require = 0)
    private void chunkis$blockSync(CallbackInfo ci) {
        ci.cancel();
    }
}