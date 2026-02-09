package io.liparakis.chunkis.adapter;

import io.liparakis.chunkis.spi.BlockRegistryAdapter;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

public class FabricBlockRegistryAdapter implements BlockRegistryAdapter<Block> {

    @Override
    public String getId(Block block) {
        return Registries.BLOCK.getId(block).toString();
    }

    @Override
    public Block getBlock(String id) {
        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null) {
            return Blocks.AIR;
        }
        return Registries.BLOCK.get(identifier);
    }

    @Override
    public Block getAir() {
        return Blocks.AIR;
    }
}
