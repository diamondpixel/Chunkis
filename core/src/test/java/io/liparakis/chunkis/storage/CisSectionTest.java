package io.liparakis.chunkis.storage;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class CisSectionTest {

    @Test
    void testSparseOperations() {
        CisSection<String> section = new CisSection<>();
        assertThat(section.isEmpty()).isTrue();
        assertThat(section.mode).isEqualTo(CisSection.MODE_EMPTY);

        // 1. Add block -> Sparse
        section.setBlock(0, 0, 0, "stone");
        assertThat(section.mode).isEqualTo(CisSection.MODE_SPARSE);
        assertThat(section.sparseSize).isEqualTo(1);
        assertThat(section.sparseValues[0]).isEqualTo("stone");
        assertThat(section.isEmpty()).isFalse(); // Cover isEmpty() -> false

        // 2. Overwrite block
        section.setBlock(0, 0, 0, "dirt");
        assertThat(section.sparseSize).isEqualTo(1);
        assertThat(section.sparseValues[0]).isEqualTo("dirt");

        // 3. Add second block
        section.setBlock(15, 15, 15, "glass");
        assertThat(section.sparseSize).isEqualTo(2);

        // 4. Remove block -> Stay Sparse
        section.setBlock(0, 0, 0, null); // null = air
        assertThat(section.sparseSize).isEqualTo(1);
        // "dirt" removed, "glass" might have moved to index 0 depending on
        // implementation (swap-remove)
        // Implementation uses swap-remove: last item moves to removed slot.
        // dirt was at 0. glass was at 1. glass moves to 0.
        assertThat(section.sparseValues[0]).isEqualTo("glass");

        // 5. Remove last block -> Empty
        section.setBlock(15, 15, 15, null);
        assertThat(section.isEmpty()).isTrue();
        assertThat(section.sparseSize).isEqualTo(0);
        assertThat(section.mode).isEqualTo(CisSection.MODE_EMPTY);
    }

    @Test
    void testSparseResize() {
        CisSection<String> section = new CisSection<>();
        // Initial capacity is 4. Add 5 blocks to trigger resize.
        for (int i = 0; i < 5; i++) {
            section.setBlock(i, 0, 0, "block_" + i);
        }

        assertThat(section.sparseSize).isEqualTo(5);
        assertThat(section.sparseKeys.length).isGreaterThanOrEqualTo(5);

        // Check content
        for (int i = 0; i < 5; i++) {
            // We can't easily check internal array without order assumption or search.
            // But we can check if setBlock logic kept it consistent by "getting" it?
            // CisSection doesn't have getBlock().. wait, it's a storage container.
            // We'll rely on internal inspection for this test since getBlock isn't public
            // API
            // (or rather, we are testing internal state integrity).

            // Actually, we can assume the internal findSparseIndex works if we rely on
            // public setBlock behavior not crashing?
            // Better: use reflection to verify the keys are present.
            boolean found = false;
            short targetKey = (short) i; // x=i, y=0, z=0 -> key=i
            for (int k = 0; k < section.sparseSize; k++) {
                if (section.sparseKeys[k] == targetKey) {
                    found = true;
                    assertThat(section.sparseValues[k]).isEqualTo("block_" + i);
                    break;
                }
            }
            assertThat(found).as("Block " + i + " should be present").isTrue();
        }
    }

    @Test
    void testConvertToDense_Reflective() throws Exception {
        CisSection<String> section = new CisSection<>();

        // Add some sparse data
        section.setBlock(0, 0, 0, "stone");
        section.setBlock(15, 15, 15, "dirt");

        // Force conversion via reflection
        Method convertToDense = CisSection.class.getDeclaredMethod("convertToDense");
        convertToDense.setAccessible(true);
        convertToDense.invoke(section);

        assertThat(section.mode).isEqualTo(CisSection.MODE_DENSE);
        assertThat(section.denseBlocks).isNotNull();
        assertThat(section.denseBlocks.length).isEqualTo(4096);
        assertThat(section.denseCount).isEqualTo(2);

        // Verify data in dense array
        // Index for (0,0,0) is 0
        assertThat(section.denseBlocks[0]).isEqualTo("stone");

        // Index for (15,15,15) is 4095
        int index = (15 << 8) | (15 << 4) | 15;
        assertThat(section.denseBlocks[index]).isEqualTo("dirt");
    }

    @Test
    void testSetBlockDense_Reflective() throws Exception {
        CisSection<String> section = new CisSection<>();

        // Force into DENSE mode manually
        Field modeField = CisSection.class.getDeclaredField("mode");
        modeField.setAccessible(true);
        modeField.setByte(section, CisSection.MODE_DENSE);

        Field denseBlocksField = CisSection.class.getDeclaredField("denseBlocks");
        denseBlocksField.setAccessible(true);
        Object[] denseArr = new Object[4096];
        denseBlocksField.set(section, denseArr);

        // Test Adding block in dense mode
        section.setBlock(1, 1, 1, "glass");

        int index = (1 << 8) | (1 << 4) | 1;
        assertThat(denseArr[index]).isEqualTo("glass");

        Field denseCountField = CisSection.class.getDeclaredField("denseCount");
        denseCountField.setAccessible(true);
        int count = denseCountField.getInt(section);
        assertThat(count).isEqualTo(1);

        // Test Removing block in dense mode
        section.setBlock(1, 1, 1, null);
        assertThat(denseArr[index]).isNull();
        count = denseCountField.getInt(section);
        assertThat(count).isEqualTo(0);

        // With count 0, it should trigger convertToSparse (threshold/2 = 2048)
        assertThat(section.mode).isEqualTo(CisSection.MODE_EMPTY);
    }

    /*
     * Covers: convertToSparse() called from setBlockDense
     */
    @Test
    void testConvertToSparse_Reflective() throws Exception {
        CisSection<String> section = new CisSection<>();

        // Setup Dense mode via reflection helper
        Method convertToDense = CisSection.class.getDeclaredMethod("convertToDense");
        convertToDense.setAccessible(true);

        // Add 1 block in sparse, then convert
        section.setBlock(2, 2, 2, "bedrock");
        convertToDense.invoke(section);

        assertThat(section.mode).isEqualTo(CisSection.MODE_DENSE);
        assertThat(section.denseCount).isEqualTo(1);

        // Remove the block. denseCount -> 0. 0 < 2048. Should convert.
        section.setBlock(2, 2, 2, null);

        assertThat(section.mode).isEqualTo(CisSection.MODE_EMPTY);
        assertThat(section.denseBlocks).isNull();
        assertThat(section.sparseSize).isEqualTo(0);

        // Verify we can add again (transitions to sparse)
        section.setBlock(3, 3, 3, "cobble");
        assertThat(section.mode).isEqualTo(CisSection.MODE_SPARSE);
    }

    @Test
    void testSetBlockDense_Overwrite() throws Exception {
        CisSection<String> section = new CisSection<>();

        // Force DENSE
        Method convertToDense = CisSection.class.getDeclaredMethod("convertToDense");
        convertToDense.setAccessible(true);
        section.setBlock(0, 0, 0, "A");
        convertToDense.invoke(section);

        // Overwrite A -> B. denseCount should check if "wasAir". A is not air.
        // denseCount should remain 1.
        section.setBlock(0, 0, 0, "B");

        assertThat(section.denseBlocks[0]).isEqualTo("B");
        assertThat(section.denseCount).isEqualTo(1);
    }

    @Test
    void testSetAirInEmptyMode() {
        CisSection<String> section = new CisSection<>();
        assertThat(section.mode).isEqualTo(CisSection.MODE_EMPTY);

        // Setting air in empty mode should do nothing (branch coverage)
        section.setBlock(0, 0, 0, null);

        assertThat(section.mode).isEqualTo(CisSection.MODE_EMPTY);
        assertThat(section.sparseSize).isEqualTo(0);
        assertThat(section.denseBlocks).isNull();
    }

    @Test
    void testInvalidMode() throws Exception {
        CisSection<String> section = new CisSection<>();

        // Force invalid mode via reflection
        Field modeField = CisSection.class.getDeclaredField("mode");
        modeField.setAccessible(true);
        modeField.setByte(section, (byte) 99); // Invalid mode

        // Should do nothing (switch default case)
        section.setBlock(5, 5, 5, "stone");

        // Verify state strictly unchanged
        assertThat(section.sparseSize).isEqualTo(0);
        assertThat(section.denseBlocks).isNull();
        // Mode remains invalid
        assertThat(section.mode).isEqualTo((byte) 99);
    }

    @Test
    void testSetBlockDense_AirToAir() throws Exception {
        CisSection<String> section = new CisSection<>();

        // Force DENSE
        Method convertToDense = CisSection.class.getDeclaredMethod("convertToDense");
        convertToDense.setAccessible(true);
        // Add one block so convertToDense works without empty array
        section.setBlock(15, 15, 15, "stone");
        convertToDense.invoke(section);

        assertThat(section.mode).isEqualTo(CisSection.MODE_DENSE);
        assertThat(section.denseCount).isEqualTo(1);

        // Set Air to Air (already null at 0,0,0)
        section.setBlock(0, 0, 0, null);

        // Verify denseCount unchanged (wasAir was true)
        assertThat(section.denseCount).isEqualTo(1);
        assertThat(section.denseBlocks[0]).isNull();
    }

    @Test
    void testSetBlockSparse_RemoveNonExistent() {
        CisSection<String> section = new CisSection<>();
        section.setBlock(0, 0, 0, "stone");
        assertThat(section.sparseSize).isEqualTo(1);

        // Remove non-existent key (1,1,1)
        section.setBlock(1, 1, 1, null);

        // Verify size unchanged
        assertThat(section.sparseSize).isEqualTo(1);
        assertThat(section.sparseValues[0]).isEqualTo("stone");
    }

    @Test
    void testStayDense() throws Exception {
        CisSection<String> section = new CisSection<>();

        // Force DENSE with high count (above threshold/2 = 2048)
        Field modeField = CisSection.class.getDeclaredField("mode");
        modeField.setAccessible(true);
        modeField.setByte(section, CisSection.MODE_DENSE);

        Field denseCountField = CisSection.class.getDeclaredField("denseCount");
        denseCountField.setAccessible(true);
        denseCountField.setInt(section, 3000); // Well above 2048

        Field denseBlocksField = CisSection.class.getDeclaredField("denseBlocks");
        denseBlocksField.setAccessible(true);
        Object[] denseArr = new Object[4096];
        denseBlocksField.set(section, denseArr);

        // Set a block so we can remove it
        int index = (0 << 8) | (0 << 4) | 0;
        denseArr[index] = "stone";

        // Remove valid block
        section.setBlock(0, 0, 0, null);

        // Count should decrease to 2999
        assertThat(denseCountField.getInt(section)).isEqualTo(2999);
        // Should STAY dense because 2999 > 2048
        assertThat(section.mode).isEqualTo(CisSection.MODE_DENSE);
    }

    @Test
    void testSparseCapacityConversion() throws Exception {
        CisSection<String> section = new CisSection<>();

        // Force sparseSize to MAX_SPARSE_CAPACITY (4097)
        Field sparseSizeField = CisSection.class.getDeclaredField("sparseSize");
        sparseSizeField.setAccessible(true);
        sparseSizeField.setInt(section, CisConstants.MAX_SPARSE_CAPACITY); // 4097

        // We need arrays large enough to avoid IndexOutOfBounds before conversion check
        // Actually, the check happens first?
        // Code: if (sparseSize >= MAX) { convertToDense(); ... }
        // So arrays don't need to be huge IF the check is first.

        // Initialize arrays so it's a valid sparse section
        section.setBlock(0, 0, 0, "placeholder"); // Init arrays

        // Resize arrays via reflection to accommodate the fake size
        Field sparseKeysField = CisSection.class.getDeclaredField("sparseKeys");
        sparseKeysField.setAccessible(true);
        sparseKeysField.set(section, new short[CisConstants.MAX_SPARSE_CAPACITY + 1]);

        Field sparseValuesField = CisSection.class.getDeclaredField("sparseValues");
        sparseValuesField.setAccessible(true);
        sparseValuesField.set(section, new Object[CisConstants.MAX_SPARSE_CAPACITY + 1]);

        sparseSizeField.setInt(section, CisConstants.MAX_SPARSE_CAPACITY); // Override size 4097

        // Add a block. Should trigger conversion to dense.
        section.setBlock(15, 15, 15, "new_block");

        assertThat(section.mode).isEqualTo(CisSection.MODE_DENSE);
        assertThat(section.denseBlocks).isNotNull();
        // Check new block is in dense
        int index = (15 << 8) | (15 << 4) | 15;
        assertThat(section.denseBlocks[index]).isEqualTo("new_block");
    }

    @Test
    void testConvertToSparse_WithNulls() throws Exception {
        CisSection<String> section = new CisSection<>();

        // Setup Dense
        Mode helper = new Mode();
        helper.forceDense(section);

        // Populate with some data
        section.setBlock(0, 0, 0, "A");
        section.setBlock(1, 1, 1, null); // Explicit null in dense array (already null but ensuring logic)

        // Invoke convertToSparse manually
        Method convert = CisSection.class.getDeclaredMethod("convertToSparse");
        convert.setAccessible(true);
        convert.invoke(section);

        // Should skip nulls. Only "A" should be in sparse.
        assertThat(section.sparseSize).isEqualTo(1);
        assertThat(section.sparseValues[0]).isEqualTo("A");

        // Test empty conversion
        helper.forceDense(section);
        // No blocks set (all null)
        convert.invoke(section);

        // Count 0 -> MODE_EMPTY
        assertThat(section.mode).isEqualTo(CisSection.MODE_EMPTY);
        assertThat(section.sparseSize).isEqualTo(0);
    }

    // Helper to force modes easily
    private static class Mode {
        void forceDense(CisSection<?> s) throws Exception {
            Field m = CisSection.class.getDeclaredField("mode");
            m.setAccessible(true);
            m.setByte(s, CisSection.MODE_DENSE);

            Field d = CisSection.class.getDeclaredField("denseBlocks");
            d.setAccessible(true);
            d.set(s, new Object[4096]);

            Field c = CisSection.class.getDeclaredField("denseCount");
            c.setAccessible(true);
            c.setInt(s, 0);
        }
    }
}
