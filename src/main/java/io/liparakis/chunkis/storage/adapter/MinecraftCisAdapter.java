package io.liparakis.chunkis.storage.adapter;

import io.liparakis.chunkis.storage.BitUtils;
import io.liparakis.chunkis.storage.CisAdapter;
import io.liparakis.chunkis.storage.CisMapping;
import net.minecraft.block.BlockState;

import java.io.IOException;

/**
 * Minecraft-specific implementation of the CIS adapter.
 * Delegates to CisMapping for blocked ID management and property serialization.
 */
public record MinecraftCisAdapter(CisMapping mapping) implements CisAdapter<BlockState> {

    @Override
    public int getBlockId(BlockState state) {
        return mapping.getBlockId(state);
    }

    @Override
    public void writeStateProperties(BitUtils.BitWriter writer, BlockState state) {
        mapping.writeStateProperties(writer, state);
    }

    @Override
    public BlockState readStateProperties(BitUtils.BitReader reader, int blockId) throws IOException {
        return mapping.readStateProperties(reader, blockId);
    }
}
