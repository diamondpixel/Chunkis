package io.liparakis.chunkis.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ChunkDelta} covering all branches.
 */
class ChunkDeltaTest {

    private ChunkDelta<String, String> delta;

    @BeforeEach
    void setUp() {
        delta = new ChunkDelta<>(s -> s.equals("air"));
    }

    // ========== Basic Tests ==========

    @Test
    void newDelta_isEmpty() {
        assertThat(delta.isEmpty()).isTrue();
    }

    @Test
    void defaultConstructor_usesNoEmptyCheck() {
        ChunkDelta<String, String> d = new ChunkDelta<>();

        // Add block entity
        d.addBlockEntityData(0, 0, 0, "chest");

        // Add "air" - should NOT trigger cleanup because nothing is empty by default
        d.addBlockChange(0, 0, 0, "air");

        assertThat(d.getBlockEntities()).isNotEmpty();
    }

    // ========== Block Change Tests ==========

    @Test
    void addBlockChange_addsBlock() {
        delta.addBlockChange(0, 0, 0, "stone");
        assertThat(delta.isEmpty()).isFalse();
        assertThat(delta.isDirty()).isTrue();
    }

    @Test
    void addBlockChange_nullState_ignored() {
        delta.addBlockChange(0, 0, 0, null);
        assertThat(delta.isEmpty()).isTrue();
    }

    @Test
    void addBlockChange_samePosition_updatesExisting() {
        delta.addBlockChange(0, 0, 0, "stone");
        delta.addBlockChange(0, 0, 0, "dirt");

        List<BlockInstruction> instructions = delta.getBlockInstructions();
        assertThat(instructions).hasSize(1);
    }

    @Test
    void addBlockChange_samePositionSameState_noUpdate() {
        delta.addBlockChange(0, 0, 0, "stone");
        delta.markSaved();

        delta.addBlockChange(0, 0, 0, "stone");
        // Should still be not dirty because no change occurred
        assertThat(delta.isDirty()).isFalse();
    }

    @Test
    void addBlockChange_withoutMarkDirty_doesNotSetFlag() {
        delta.addBlockChange(0, 0, 0, "stone", false);
        assertThat(delta.isDirty()).isFalse();
    }

    @Test
    void addBlockChange_updateExistingWithoutMarkDirty_doesNotSetFlag() {
        delta.addBlockChange(0, 0, 0, "stone");
        delta.markSaved();

        // Update to "dirt" but don't mark dirty
        delta.addBlockChange(0, 0, 0, "dirt", false);

        assertThat(delta.isDirty()).isFalse();
        // Verify value actually changed despite not being marked dirty
        assertThat(delta.getBlockInstructions().get(0).paletteIndex())
                .isEqualTo(delta.getBlockPalette().getOrAdd("dirt"));
    }

    @Test
    void addBlockChange_multiplePositions_storesAll() {
        delta.addBlockChange(0, 0, 0, "stone");
        delta.addBlockChange(1, 1, 1, "dirt");
        delta.addBlockChange(2, 2, 2, "grass");

        assertThat(delta.getBlockInstructions()).hasSize(3);
    }

    @Test
    void addBlockChange_triggersCapacityExpansion() {
        // Add more than initial capacity (64)
        for (int i = 0; i < 100; i++) {
            delta.addBlockChange(i % 16, i, i % 16, "stone");
        }
        assertThat(delta.getBlockInstructions()).hasSize(100);
    }

    // ========== Remove Block Change Tests ==========

    @Test
    void removeBlockChange_existingBlock_removesIt() {
        delta.addBlockChange(0, 0, 0, "stone");
        delta.markSaved();

        delta.removeBlockChange(0, 0, 0);

        assertThat(delta.isEmpty()).isTrue();
        assertThat(delta.isDirty()).isTrue();
    }

    @Test
    void removeBlockChange_nonExistent_noOp() {
        delta.removeBlockChange(0, 0, 0);
        assertThat(delta.isEmpty()).isTrue();
    }

    @Test
    void removeBlockChange_middleElement_swapsWithLast() {
        delta.addBlockChange(0, 0, 0, "stone");
        delta.addBlockChange(1, 1, 1, "dirt");
        delta.addBlockChange(2, 2, 2, "grass");

        // Remove middle element
        delta.removeBlockChange(1, 1, 1);

        List<BlockInstruction> instructions = delta.getBlockInstructions();
        assertThat(instructions).hasSize(2);
    }

    @Test
    void removeBlockChange_lastElement_shrinks() {
        delta.addBlockChange(0, 0, 0, "stone");
        delta.addBlockChange(1, 1, 1, "dirt");

        // Remove last element
        delta.removeBlockChange(1, 1, 1);

        assertThat(delta.getBlockInstructions()).hasSize(1);
    }

    // ========== Block Entity Tests ==========

    @Test
    void addBlockEntityData_addsEntity() {
        delta.addBlockEntityData(0, 0, 0, "{chest:contents}");
        assertThat(delta.isEmpty()).isFalse();
        assertThat(delta.getBlockEntities()).hasSize(1);
    }

    @Test
    void addBlockEntityData_nullNbt_ignored() {
        delta.addBlockEntityData(0, 0, 0, null);
        assertThat(delta.getBlockEntities()).isEmpty();
    }

    @Test
    void addBlockEntityData_sameData_noUpdate() {
        delta.addBlockEntityData(0, 0, 0, "data");
        delta.markSaved();

        delta.addBlockEntityData(0, 0, 0, "data");
        assertThat(delta.isDirty()).isFalse();
    }

    @Test
    void addBlockEntityData_withoutMarkDirty_doesNotSetFlag() {
        delta.addBlockEntityData(0, 0, 0, "data", false);
        assertThat(delta.isDirty()).isFalse();
    }

    @Test
    void getBlockEntities_noEntities_returnsEmptyMap() {
        assertThat(delta.getBlockEntities()).isEmpty();
    }

    @Test
    void addBlockChange_airCleansUpBlockEntity() {
        delta.addBlockEntityData(0, 0, 0, "chest");
        delta.addBlockChange(0, 0, 0, "air");

        assertThat(delta.getBlockEntities()).isEmpty();
    }

    @Test
    void addBlockChange_airWithNullBlockEntities_doesNotThrow() {
        // blockEntities is null solely by default; ensured by not adding any entities
        delta.addBlockChange(0, 0, 0, "air");

        // Should not throw and still be empty
        assertThat(delta.getBlockEntities()).isEmpty();
    }

    @Test
    void addBlockChange_solidBlockWithEntity_doesNotRemoveEntity() {
        delta.addBlockEntityData(0, 0, 0, "chest");
        // "stone" is not "air", so isEmptyState is false
        delta.addBlockChange(0, 0, 0, "stone");

        assertThat(delta.getBlockEntities()).hasSize(1);
        assertThat(delta.getBlockEntities().get(0L)).isEqualTo("chest");
    }

    // ========== Entity Tests ==========

    @Test
    void setEntities_addsEntities() {
        List<String> entities = List.of("zombie", "skeleton");
        delta.setEntities(entities);

        assertThat(delta.isEmpty()).isFalse();
        assertThat(delta.getEntitiesList()).hasSize(2);
        assertThat(delta.isDirty()).isTrue();
    }

    @Test
    void setEntities_null_convertsToEmpty() {
        delta.setEntities(null);
        assertThat(delta.getEntitiesList()).isEmpty();
    }

    @Test
    void setEntities_sameData_noUpdate() {
        List<String> entities = List.of("zombie");
        delta.setEntities(entities);
        delta.markSaved();

        delta.setEntities(new ArrayList<>(entities));
        assertThat(delta.isDirty()).isFalse();
    }

    @Test
    void setEntities_withoutMarkDirty_doesNotSetFlag() {
        delta.setEntities(List.of("zombie"), false);
        assertThat(delta.isDirty()).isFalse();
    }

    // ========== isEmpty Tests ==========

    @Test
    void isEmpty_withOnlyBlockEntities_returnsFalse() {
        delta.addBlockEntityData(0, 0, 0, "chest");
        assertThat(delta.isEmpty()).isFalse();
    }

    @Test
    void isEmpty_withOnlyEntities_returnsFalse() {
        delta.setEntities(List.of("zombie"));
        assertThat(delta.isEmpty()).isFalse();
    }

    @Test
    void isEmpty_withEmptyBlockEntities_notNull_returnsTrue() {
        // Force blockEntities creation then clear
        delta.addBlockEntityData(0, 0, 0, "data");
        delta.addBlockChange(0, 0, 0, "air"); // Removes block entity
        delta.removeBlockChange(0, 0, 0);

        assertThat(delta.isEmpty()).isTrue();
    }

    // ========== Dirty Flag Tests ==========

    @Test
    void markDirty_setsFlag() {
        delta.markDirty();
        assertThat(delta.isDirty()).isTrue();
    }

    @Test
    void markSaved_clearsFlag() {
        delta.addBlockChange(0, 0, 0, "stone");
        delta.markSaved();
        assertThat(delta.isDirty()).isFalse();
    }

    // ========== Visitor Tests ==========

    @Test
    void accept_visitsAllContent() {
        delta.addBlockChange(0, 0, 0, "stone");
        delta.addBlockEntityData(1, 1, 1, "chest");
        delta.setEntities(List.of("zombie"));

        List<String> visited = new ArrayList<>();
        delta.accept(new ChunkDelta.DeltaVisitor<>() {
            @Override
            public void visitBlock(int x, int y, int z, String state) {
                visited.add("block:" + state);
            }

            @Override
            public void visitBlockEntity(int x, int y, int z, String nbt) {
                visited.add("blockEntity:" + nbt);
            }

            @Override
            public void visitEntity(String nbt) {
                visited.add("entity:" + nbt);
            }
        });

        assertThat(visited).containsExactlyInAnyOrder(
                "block:stone",
                "blockEntity:chest",
                "entity:zombie");
    }

    @Test
    void accept_skipsInvalidPaletteEntries() throws Exception {
        delta.addBlockChange(0, 0, 0, "stone");

        // Use reflection to corrupt the instruction with an invalid palette ID
        java.lang.reflect.Field instructionsField = ChunkDelta.class.getDeclaredField("packedInstructions");
        instructionsField.setAccessible(true);
        long[] instructions = (long[]) instructionsField.get(delta);

        // Create an instruction with correct position but invalid palette ID (999)
        long invalidInstruction = new BlockInstruction((byte) 0, 0, (byte) 0, 999).pack();
        instructions[0] = invalidInstruction;

        List<String> visited = new ArrayList<>();
        delta.accept(new ChunkDelta.DeltaVisitor<>() {
            @Override
            public void visitBlock(int x, int y, int z, String state) {
                visited.add(state);
            }

            @Override
            public void visitBlockEntity(int x, int y, int z, String nbt) {
            }

            @Override
            public void visitEntity(String nbt) {
            }
        });

        // Should be empty because the invalid entry was skipped
        assertThat(visited).isEmpty();
    }

    @Test
    void accept_withEmptyButInitializedBlockEntities_visitsNothing() {
        // Initialize blockEntities map
        delta.addBlockEntityData(0, 0, 0, "chest");

        // Remove it, making the map empty but not null
        delta.addBlockChange(0, 0, 0, "air");

        List<String> visited = new ArrayList<>();
        delta.accept(new ChunkDelta.DeltaVisitor<>() {
            @Override
            public void visitBlock(int x, int y, int z, String state) {
                if (!state.equals("air"))
                    visited.add("block:" + state);
            }

            @Override
            public void visitBlockEntity(int x, int y, int z, String nbt) {
                visited.add("blockEntity:" + nbt);
            }

            @Override
            public void visitEntity(String nbt) {
                visited.add("entity:" + nbt);
            }
        });

        // Should be empty
        assertThat(visited).isEmpty();
    }

    // ========== Palette Tests ==========

    @Test
    void getBlockPalette_returnsSharedPalette() {
        delta.addBlockChange(0, 0, 0, "stone");
        delta.addBlockChange(1, 1, 1, "stone");

        Palette<String> palette = delta.getBlockPalette();
        assertThat(palette.getAll()).containsExactly("stone");
    }
}
