package io.liparakis.chunkis.storage;

import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.storage.BitUtils.BitReader;
import io.liparakis.chunkis.storage.BitUtils.BitWriter;
import io.liparakis.chunkis.storage.PropertyPacker.PropertyMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for {@link PropertyPacker} ensuring correct caching,
 * bit-packing, and structure.
 */
@ExtendWith(MockitoExtension.class)
class PropertyPackerTest {

    @Mock
    private BlockStateAdapter<String, Integer, String> mockAdapter;

    @Mock
    private BitWriter mockWriter;

    @Mock
    private BitReader mockReader;

    private PropertyPacker<String, Integer, String> packer;

    @BeforeEach
    void setUp() {
        packer = new PropertyPacker<>(mockAdapter);
    }

    // ========== getPropertyMetas Tests ==========

    @Test
    void getPropertyMetasReturnsEmptyForBlockWithNoProperties() {
        when(mockAdapter.getProperties("stone")).thenReturn(new ArrayList<>());

        PropertyMeta<String>[] metas = packer.getPropertyMetas("stone");

        assertThat(metas).isEmpty();
        verify(mockAdapter, times(1)).getProperties("stone");
    }

    @Test
    void getPropertyMetasCachesResults() {
        when(mockAdapter.getProperties("cobblestone")).thenReturn(new ArrayList<>());

        PropertyMeta<String>[] metas1 = packer.getPropertyMetas("cobblestone");
        PropertyMeta<String>[] metas2 = packer.getPropertyMetas("cobblestone");

        // Should return the same cached array instance
        assertThat(metas1).isSameAs(metas2);
        // getProperties should only be called once due to caching
        verify(mockAdapter, times(1)).getProperties("cobblestone");
    }

    @Test
    void getPropertyMetasCreatesSortedMetadataForSingleProperty() {
        List<String> properties = new ArrayList<>(List.of("facing"));
        when(mockAdapter.getProperties("furnace")).thenReturn(properties);
        when(mockAdapter.getPropertyValues("facing")).thenReturn(Arrays.asList("north", "south", "east", "west"));

        PropertyMeta<String>[] metas = packer.getPropertyMetas("furnace");

        assertThat(metas).hasSize(1);
        assertThat(metas[0].property()).isEqualTo("facing");
        assertThat(metas[0].bits()).isEqualTo(2); // 4 values need 2 bits
        verify(mockAdapter).getPropertyValues("facing");
    }

    @Test
    void getPropertyMetasCreatesSortedMetadataForMultipleProperties() {
        // Create unsorted list
        List<String> properties = new ArrayList<>(Arrays.asList("waterlogged", "facing", "lit"));
        when(mockAdapter.getProperties("lamp")).thenReturn(properties);

        when(mockAdapter.getPropertyName("waterlogged")).thenReturn("waterlogged");
        when(mockAdapter.getPropertyName("facing")).thenReturn("facing");
        when(mockAdapter.getPropertyName("lit")).thenReturn("lit");

        when(mockAdapter.getPropertyValues("waterlogged")).thenReturn(Arrays.asList("true", "false"));
        when(mockAdapter.getPropertyValues("facing")).thenReturn(Arrays.asList("north", "south", "east", "west"));
        when(mockAdapter.getPropertyValues("lit")).thenReturn(Arrays.asList("true", "false"));

        PropertyMeta<String>[] metas = packer.getPropertyMetas("lamp");

        assertThat(metas).hasSize(3);
        // Should be sorted alphabetically: facing, lit, waterlogged
        assertThat(metas[0].property()).isEqualTo("facing");
        assertThat(metas[1].property()).isEqualTo("lit");
        assertThat(metas[2].property()).isEqualTo("waterlogged");

        assertThat(metas[0].bits()).isEqualTo(2); // 4 values
        assertThat(metas[1].bits()).isEqualTo(1); // 2 values
        assertThat(metas[2].bits()).isEqualTo(1); // 2 values

        // Verify all stubbed methods were called
        verify(mockAdapter, atLeastOnce()).getPropertyName("waterlogged");
        verify(mockAdapter, atLeastOnce()).getPropertyName("facing");
        verify(mockAdapter, atLeastOnce()).getPropertyName("lit");
        verify(mockAdapter).getPropertyValues("waterlogged");
        verify(mockAdapter).getPropertyValues("facing");
        verify(mockAdapter).getPropertyValues("lit");
    }

    @Test
    void getPropertyMetasReturnsSingletonEmptyArrayForBlocksWithNoProperties() {
        when(mockAdapter.getProperties("stone")).thenReturn(new ArrayList<>());
        when(mockAdapter.getProperties("dirt")).thenReturn(new ArrayList<>());

        PropertyMeta<String>[] stoneMetas = packer.getPropertyMetas("stone");
        PropertyMeta<String>[] dirtMetas = packer.getPropertyMetas("dirt");

        // Both should return the same singleton instance
        assertThat(stoneMetas).isSameAs(dirtMetas);

        // getProperties should be called for each block
        verify(mockAdapter, times(1)).getProperties("stone");
        verify(mockAdapter, times(1)).getProperties("dirt");
    }

    @Test
    void getPropertyMetasReturnsDifferentArraysForDifferentBlocksWithProperties() {
        when(mockAdapter.getProperties("stone")).thenReturn(List.of("prop1"));
        when(mockAdapter.getPropertyValues("prop1")).thenReturn(List.of("val1", "val2"));

        when(mockAdapter.getProperties("dirt")).thenReturn(List.of("prop2"));
        when(mockAdapter.getPropertyValues("prop2")).thenReturn(List.of("val3"));

        PropertyMeta<String>[] stoneMetas = packer.getPropertyMetas("stone");
        PropertyMeta<String>[] dirtMetas = packer.getPropertyMetas("dirt");

        // Should return different array instances
        assertThat(stoneMetas).isNotSameAs(dirtMetas);
        assertThat(stoneMetas).hasSize(1);
        assertThat(dirtMetas).hasSize(1);
    }

    // ========== PropertyMeta Constructor Tests ==========

    @Test
    void propertyMetaCalculatesBitsForSingleValue() {
        // 1 value needs 1 bit (edge case: Math.max ensures minimum 1 bit)
        PropertyMeta<String> meta = new PropertyMeta<>("prop", 1);
        assertThat(meta.bits()).isEqualTo(1);
    }

    @Test
    void propertyMetaCalculatesBitsForTwoValues() {
        // 2 values need 1 bit
        PropertyMeta<String> meta = new PropertyMeta<>("prop", 2);
        assertThat(meta.bits()).isEqualTo(1);
    }

    @Test
    void propertyMetaCalculatesBitsForThreeValues() {
        // 3 values need 2 bits
        PropertyMeta<String> meta = new PropertyMeta<>("prop", 3);
        assertThat(meta.bits()).isEqualTo(2);
    }

    @Test
    void propertyMetaCalculatesBitsForFourValues() {
        // 4 values need 2 bits
        PropertyMeta<String> meta = new PropertyMeta<>("prop", 4);
        assertThat(meta.bits()).isEqualTo(2);
    }

    @Test
    void propertyMetaCalculatesBitsForPowerOfTwo() {
        // 8 values need 3 bits
        PropertyMeta<String> meta = new PropertyMeta<>("prop", 8);
        assertThat(meta.bits()).isEqualTo(3);
    }

    @Test
    void propertyMetaCalculatesBitsForNonPowerOfTwo() {
        // 6 values need 3 bits
        PropertyMeta<String> meta = new PropertyMeta<>("prop", 6);
        assertThat(meta.bits()).isEqualTo(3);
    }

    @Test
    void propertyMetaCalculatesBitsForLargeValueCount() {
        // 16 values need 4 bits
        PropertyMeta<String> meta = new PropertyMeta<>("prop", 16);
        assertThat(meta.bits()).isEqualTo(4);
    }

    // ========== writeProperties Tests ==========

    @Test
    void writePropertiesWritesNothingForEmptyMetas() {
        PropertyMeta<String>[] emptyMetas = new PropertyMeta[0];

        packer.writeProperties(mockWriter, 123, emptyMetas);

        verifyNoInteractions(mockWriter);
    }

    @Test
    void writePropertiesWritesSinglePropertyValue() {
        PropertyMeta<String>[] metas = new PropertyMeta[] {
                new PropertyMeta<>("facing", 4)
        };

        when(mockAdapter.getValueIndex(100, "facing")).thenReturn(2);

        packer.writeProperties(mockWriter, 100, metas);

        verify(mockAdapter, times(1)).getValueIndex(100, "facing");
        verify(mockWriter, times(1)).write(2, 2); // value=2, bits=2
    }

    @Test
    void writePropertiesWritesMultiplePropertiesInOrder() {
        PropertyMeta<String>[] metas = new PropertyMeta[] {
                new PropertyMeta<>("facing", 4), // 2 bits
                new PropertyMeta<>("lit", 2), // 1 bit
                new PropertyMeta<>("powered", 2) // 1 bit
        };

        when(mockAdapter.getValueIndex(200, "facing")).thenReturn(3);
        when(mockAdapter.getValueIndex(200, "lit")).thenReturn(1);
        when(mockAdapter.getValueIndex(200, "powered")).thenReturn(0);

        packer.writeProperties(mockWriter, 200, metas);

        verify(mockAdapter, times(1)).getValueIndex(200, "facing");
        verify(mockAdapter, times(1)).getValueIndex(200, "lit");
        verify(mockAdapter, times(1)).getValueIndex(200, "powered");

        verify(mockWriter, times(1)).write(3, 2);
        verify(mockWriter, times(1)).write(1, 1);
        verify(mockWriter, times(1)).write(0, 1);
    }

    @Test
    void writePropertiesHandlesZeroValueIndex() {
        PropertyMeta<String>[] metas = new PropertyMeta[] {
                new PropertyMeta<>("prop", 8)
        };

        when(mockAdapter.getValueIndex(300, "prop")).thenReturn(0);

        packer.writeProperties(mockWriter, 300, metas);

        verify(mockWriter, times(1)).write(0, 3);
    }

    @Test
    void writePropertiesHandlesMaxValueIndex() {
        PropertyMeta<String>[] metas = new PropertyMeta[] {
                new PropertyMeta<>("prop", 8) // 3 bits, max index = 7
        };

        when(mockAdapter.getValueIndex(400, "prop")).thenReturn(7);

        packer.writeProperties(mockWriter, 400, metas);

        verify(mockWriter, times(1)).write(7, 3);
    }

    // ========== readProperties Tests ==========

    @Test
    void readPropertiesReturnsDefaultStateForEmptyMetas() {
        PropertyMeta<String>[] emptyMetas = new PropertyMeta[0];
        when(mockAdapter.getDefaultState("stone")).thenReturn(1000);

        Integer result = packer.readProperties(mockReader, "stone", emptyMetas);

        assertThat(result).isEqualTo(1000);
        verify(mockAdapter, times(1)).getDefaultState("stone");
        verifyNoInteractions(mockReader);
    }

    @Test
    void readPropertiesReadsSinglePropertyAndAppliesIt() {
        PropertyMeta<String>[] metas = new PropertyMeta[] {
                new PropertyMeta<>("facing", 4) // 2 bits
        };

        when(mockAdapter.getDefaultState("furnace")).thenReturn(500);
        when(mockReader.read(2)).thenReturn(3L);
        when(mockAdapter.withProperty(500, "facing", 3)).thenReturn(503);

        Integer result = packer.readProperties(mockReader, "furnace", metas);

        assertThat(result).isEqualTo(503);
        verify(mockAdapter, times(1)).getDefaultState("furnace");
        verify(mockReader, times(1)).read(2);
        verify(mockAdapter, times(1)).withProperty(500, "facing", 3);
    }

    @Test
    void readPropertiesReadsMultiplePropertiesInOrderAndAppliesThem() {
        PropertyMeta<String>[] metas = new PropertyMeta[] {
                new PropertyMeta<>("facing", 4), // 2 bits
                new PropertyMeta<>("lit", 2), // 1 bit
                new PropertyMeta<>("powered", 2) // 1 bit
        };

        when(mockAdapter.getDefaultState("lamp")).thenReturn(600);
        when(mockReader.read(2)).thenReturn(2L);
        when(mockReader.read(1)).thenReturn(1L, 0L);

        when(mockAdapter.withProperty(600, "facing", 2)).thenReturn(601);
        when(mockAdapter.withProperty(601, "lit", 1)).thenReturn(602);
        when(mockAdapter.withProperty(602, "powered", 0)).thenReturn(603);

        Integer result = packer.readProperties(mockReader, "lamp", metas);

        assertThat(result).isEqualTo(603);
        verify(mockAdapter, times(1)).getDefaultState("lamp");
        verify(mockReader, times(1)).read(2);
        verify(mockReader, times(2)).read(1);
        verify(mockAdapter, times(1)).withProperty(600, "facing", 2);
        verify(mockAdapter, times(1)).withProperty(601, "lit", 1);
        verify(mockAdapter, times(1)).withProperty(602, "powered", 0);
    }

    @Test
    void readPropertiesHandlesZeroIndex() {
        PropertyMeta<String>[] metas = new PropertyMeta[] {
                new PropertyMeta<>("prop", 8)
        };

        when(mockAdapter.getDefaultState("block")).thenReturn(700);
        when(mockReader.read(3)).thenReturn(0L);
        when(mockAdapter.withProperty(700, "prop", 0)).thenReturn(700);

        Integer result = packer.readProperties(mockReader, "block", metas);

        assertThat(result).isEqualTo(700);
        verify(mockReader, times(1)).read(3);
        verify(mockAdapter, times(1)).withProperty(700, "prop", 0);
    }

    @Test
    void readPropertiesHandlesMaxIndexForBitWidth() {
        PropertyMeta<String>[] metas = new PropertyMeta[] {
                new PropertyMeta<>("prop", 8) // 3 bits, max = 7
        };

        when(mockAdapter.getDefaultState("block")).thenReturn(800);
        when(mockReader.read(3)).thenReturn(7L);
        when(mockAdapter.withProperty(800, "prop", 7)).thenReturn(807);

        Integer result = packer.readProperties(mockReader, "block", metas);

        assertThat(result).isEqualTo(807);
        verify(mockReader, times(1)).read(3);
        verify(mockAdapter, times(1)).withProperty(800, "prop", 7);
    }

    @Test
    void readPropertiesCastsLongToIntCorrectly() {
        PropertyMeta<String>[] metas = new PropertyMeta[] {
                new PropertyMeta<>("prop", 16) // 4 bits
        };

        when(mockAdapter.getDefaultState("block")).thenReturn(900);
        when(mockReader.read(4)).thenReturn(15L); // Returns long
        when(mockAdapter.withProperty(900, "prop", 15)).thenReturn(915);

        Integer result = packer.readProperties(mockReader, "block", metas);

        assertThat(result).isEqualTo(915);
        verify(mockAdapter, times(1)).withProperty(900, "prop", 15);
    }

    // ========== Integration Tests ==========

    @Test
    void roundTripWriteAndReadProducesCorrectState() {
        // Setup properties
        List<String> properties = new ArrayList<>(Arrays.asList("facing", "lit"));
        when(mockAdapter.getProperties("torch")).thenReturn(properties);
        when(mockAdapter.getPropertyName("facing")).thenReturn("facing");
        when(mockAdapter.getPropertyName("lit")).thenReturn("lit");
        when(mockAdapter.getPropertyValues("facing")).thenReturn(Arrays.asList("north", "south", "east", "west"));
        when(mockAdapter.getPropertyValues("lit")).thenReturn(Arrays.asList("true", "false"));

        // Get metadata
        PropertyMeta<String>[] metas = packer.getPropertyMetas("torch");

        // Write properties
        when(mockAdapter.getValueIndex(1000, "facing")).thenReturn(2);
        when(mockAdapter.getValueIndex(1000, "lit")).thenReturn(1);
        packer.writeProperties(mockWriter, 1000, metas);

        // Verify write calls
        verify(mockWriter, times(1)).write(2, 2); // facing
        verify(mockWriter, times(1)).write(1, 1); // lit

        // Read properties
        when(mockAdapter.getDefaultState("torch")).thenReturn(2000);
        when(mockReader.read(2)).thenReturn(2L);
        when(mockReader.read(1)).thenReturn(1L);
        when(mockAdapter.withProperty(2000, "facing", 2)).thenReturn(2002);
        when(mockAdapter.withProperty(2002, "lit", 1)).thenReturn(2003);

        Integer result = packer.readProperties(mockReader, "torch", metas);

        assertThat(result).isEqualTo(2003);
    }

    @Test
    void getPropertyMetasHandlesImmutableListFromAdapter() {
        // Return an immutable list from getProperties to replicate the crash
        List<String> immutableProps = List.of("waterlogged", "facing", "lit");
        when(mockAdapter.getProperties("lamp")).thenReturn(immutableProps);

        when(mockAdapter.getPropertyName("waterlogged")).thenReturn("waterlogged");
        when(mockAdapter.getPropertyName("facing")).thenReturn("facing");
        when(mockAdapter.getPropertyName("lit")).thenReturn("lit");

        when(mockAdapter.getPropertyValues("waterlogged")).thenReturn(Arrays.asList("true", "false"));
        when(mockAdapter.getPropertyValues("facing")).thenReturn(Arrays.asList("north", "south", "east", "west"));
        when(mockAdapter.getPropertyValues("lit")).thenReturn(Arrays.asList("true", "false"));

        // Should not throw UnsupportedOperationException
        PropertyMeta<String>[] metas = packer.getPropertyMetas("lamp");

        assertThat(metas).hasSize(3);
        // Should be sorted alphabetically: facing, lit, waterlogged
        assertThat(metas[0].property()).isEqualTo("facing");
        assertThat(metas[1].property()).isEqualTo("lit");
        assertThat(metas[2].property()).isEqualTo("waterlogged");
    }
}