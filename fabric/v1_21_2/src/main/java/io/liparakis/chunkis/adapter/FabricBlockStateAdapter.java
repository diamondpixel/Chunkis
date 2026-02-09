package io.liparakis.chunkis.adapter;

import io.liparakis.chunkis.spi.BlockStateAdapter;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.state.property.Property;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@SuppressWarnings({ "unchecked", "rawtypes" })
public class FabricBlockStateAdapter implements BlockStateAdapter<Block, BlockState, Property<?>> {

    @Override
    public Block getBlock(BlockState state) {
        return state.getBlock();
    }

    @Override
    public List<Property<?>> getProperties(Block block) {
        return new ArrayList<>(block.getStateManager().getProperties());
    }

    @Override
    public String getPropertyName(Property<?> property) {
        return property.getName();
    }

    @Override
    public List<Object> getPropertyValues(Property<?> property) {
        return new ArrayList<>(property.getValues());
    }

    @Override
    public int getValueIndex(BlockState state, Property<?> property) {
        Comparable<?> val = state.get(property);
        List<?> values = new ArrayList<>(property.getValues());
        return values.indexOf(val);
    }

    @Override
    public BlockState withProperty(BlockState state, Property<?> property, int valueIndex) {
        List<?> values = new ArrayList<>(property.getValues());
        if (valueIndex < 0 || valueIndex >= values.size()) {
            return state;
        }
        Comparable val = (Comparable) values.get(valueIndex);
        return state.with((Property) property, val);
    }

    @Override
    public BlockState getDefaultState(Block block) {
        return block.getDefaultState();
    }

    @Override
    public boolean isAir(BlockState state) {
        return state.isAir();
    }

    @Override
    public Comparator<Object> getValueComparator() {
        // Use natural ordering (string representation) for deterministic comparison
        return Comparator.comparing(Object::toString);
    }
}
