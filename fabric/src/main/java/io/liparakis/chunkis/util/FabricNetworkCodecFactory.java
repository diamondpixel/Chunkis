package io.liparakis.chunkis.util;

import io.liparakis.chunkis.adapter.FabricBlockRegistryAdapter;
import io.liparakis.chunkis.adapter.FabricBlockStateAdapter;
import io.liparakis.chunkis.adapter.FabricNbtAdapter;
import io.liparakis.chunkis.spi.BlockRegistryAdapter;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.spi.NbtAdapter;
import io.liparakis.chunkis.storage.PropertyPacker;
import io.liparakis.chunkis.storage.codec.CisNetworkDecoder;
import io.liparakis.chunkis.storage.codec.CisNetworkEncoder;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.state.property.Property;

/**
 * Factory for creating Fabric-specific network encoders and decoders.
 */
public final class FabricNetworkCodecFactory {

    private static final BlockRegistryAdapter<Block> REGISTRY_ADAPTER = new FabricBlockRegistryAdapter();
    private static final BlockStateAdapter<Block, BlockState, Property<?>> STATE_ADAPTER = new FabricBlockStateAdapter();
    private static final NbtAdapter<NbtCompound> NBT_ADAPTER = new FabricNbtAdapter();
    private static final PropertyPacker<Block, BlockState, Property<?>> PROPERTY_PACKER = new PropertyPacker<>(
            STATE_ADAPTER);
    private static final BlockState AIR_STATE = Blocks.AIR.getDefaultState();

    private FabricNetworkCodecFactory() {
        // Utility class
    }

    public static CisNetworkDecoder<Block, BlockState, Property<?>, NbtCompound> createDecoder() {
        return new CisNetworkDecoder<>(REGISTRY_ADAPTER, PROPERTY_PACKER, STATE_ADAPTER, NBT_ADAPTER, AIR_STATE);
    }

    public static CisNetworkEncoder<Block, BlockState, Property<?>, NbtCompound> createEncoder() {
        return new CisNetworkEncoder<>(REGISTRY_ADAPTER, PROPERTY_PACKER, STATE_ADAPTER, NBT_ADAPTER, AIR_STATE);
    }
}
