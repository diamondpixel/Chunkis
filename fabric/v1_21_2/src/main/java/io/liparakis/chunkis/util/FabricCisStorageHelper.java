package io.liparakis.chunkis.util;

import io.liparakis.chunkis.adapter.FabricBlockRegistryAdapter;
import io.liparakis.chunkis.adapter.FabricBlockStateAdapter;
import io.liparakis.chunkis.adapter.FabricNbtAdapter;
import io.liparakis.chunkis.spi.BlockRegistryAdapter;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.spi.NbtAdapter;
import io.liparakis.chunkis.storage.CisMapping;
import io.liparakis.chunkis.storage.CisStorage;
import io.liparakis.chunkis.storage.PropertyPacker;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FabricCisStorageHelper {

    private static final java.util.Map<net.minecraft.registry.RegistryKey<net.minecraft.world.World>, CisStorage<Block, BlockState, Property<?>, NbtCompound>> storageMap = new java.util.concurrent.ConcurrentHashMap<>();

    public static CisStorage<Block, BlockState, Property<?>, NbtCompound> getStorage(ServerWorld world) {
        return storageMap.computeIfAbsent(world.getRegistryKey(), key -> createStorageInternal(world));
    }

    public static void closeStorage(ServerWorld world) {
        CisStorage<Block, BlockState, Property<?>, NbtCompound> storage = storageMap.remove(world.getRegistryKey());
        if (storage != null) {
            storage.close();
        }
    }

    private static CisStorage<Block, BlockState, Property<?>, NbtCompound> createStorageInternal(ServerWorld world) {
        Path storageDir = getOrCreateDimensionStorage(world);

        BlockRegistryAdapter<Block> registryAdapter = new FabricBlockRegistryAdapter();
        BlockStateAdapter<Block, BlockState, Property<?>> stateAdapter = new FabricBlockStateAdapter();
        NbtAdapter<NbtCompound> nbtAdapter = new FabricNbtAdapter();

        // Load mapping
        Path mappingFile = storageDir.getParent().resolve("global_ids.json");
        PropertyPacker<Block, BlockState, Property<?>> packer = new PropertyPacker<>(stateAdapter);
        CisMapping<Block, BlockState, Property<?>> mapping;
        try {
            mapping = new CisMapping<>(mappingFile, registryAdapter, stateAdapter, packer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load Chunkis mappings", e);
        }

        return new CisStorage<>(storageDir, mapping, stateAdapter, nbtAdapter, Blocks.AIR.getDefaultState());
    }

    private static Path getOrCreateDimensionStorage(ServerWorld world) {
        Path baseDir = world.getServer().getSavePath(WorldSavePath.ROOT);
        String dimId = world.getRegistryKey().getValue().getPath();

        if (!"overworld".equals(dimId)) {
            baseDir = baseDir.resolve("dimensions")
                    .resolve(world.getRegistryKey().getValue().getNamespace())
                    .resolve(dimId);
        }

        Path storageDir = baseDir.resolve("chunkis").resolve("regions");

        try {
            Files.createDirectories(storageDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create Chunkis storage directory", e);
        }

        return storageDir;
    }
}
