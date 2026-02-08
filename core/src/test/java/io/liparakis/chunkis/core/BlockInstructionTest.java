package io.liparakis.chunkis.core;

import io.liparakis.chunkis.storage.CisConstants;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link BlockInstruction} covering all branches.
 */
class BlockInstructionTest {

    // ========== Constructor Validation Tests ==========

    @Test
    void constructor_validCoordinates_createsInstruction() {
        BlockInstruction instruction = new BlockInstruction((byte) 0, 0, (byte) 0, 0);
        assertThat(instruction.x()).isEqualTo((byte) 0);
        assertThat(instruction.y()).isEqualTo(0);
        assertThat(instruction.z()).isEqualTo((byte) 0);
    }

    @Test
    void constructor_maxValidCoordinates_createsInstruction() {
        BlockInstruction instruction = new BlockInstruction((byte) 15, CisConstants.MAX_Y, (byte) 15,
                Integer.MAX_VALUE);
        assertThat(instruction.x()).isEqualTo((byte) 15);
        assertThat(instruction.y()).isEqualTo(CisConstants.MAX_Y);
        assertThat(instruction.z()).isEqualTo((byte) 15);
    }

    @Test
    void constructor_minValidY_createsInstruction() {
        BlockInstruction instruction = new BlockInstruction((byte) 0, CisConstants.MIN_Y, (byte) 0, 0);
        assertThat(instruction.y()).isEqualTo(CisConstants.MIN_Y);
    }

    @Test
    void constructor_negativeX_throwsException() {
        assertThatThrownBy(() -> new BlockInstruction((byte) -1, 0, (byte) 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("X coordinate out of bounds");
    }

    @Test
    void constructor_xTooLarge_throwsException() {
        assertThatThrownBy(() -> new BlockInstruction((byte) 16, 0, (byte) 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("X coordinate out of bounds");
    }

    @Test
    void constructor_negativeZ_throwsException() {
        assertThatThrownBy(() -> new BlockInstruction((byte) 0, 0, (byte) -1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Z coordinate out of bounds");
    }

    @Test
    void constructor_zTooLarge_throwsException() {
        assertThatThrownBy(() -> new BlockInstruction((byte) 0, 0, (byte) 16, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Z coordinate out of bounds");
    }

    @Test
    void constructor_yBelowMin_throwsException() {
        assertThatThrownBy(() -> new BlockInstruction((byte) 0, CisConstants.MIN_Y - 1, (byte) 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Y coordinate out of bounds");
    }

    @Test
    void constructor_yAboveMax_throwsException() {
        assertThatThrownBy(() -> new BlockInstruction((byte) 0, CisConstants.MAX_Y + 1, (byte) 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Y coordinate out of bounds");
    }

    // ========== Pack/Unpack Tests ==========

    @Test
    void packAndUnpack_roundTrip_preservesData() {
        BlockInstruction original = new BlockInstruction((byte) 5, 100, (byte) 10, 42);
        long packed = original.pack();
        BlockInstruction unpacked = BlockInstruction.fromPacked(packed);

        assertThat(unpacked).isEqualTo(original);
    }

    @Test
    void packAndUnpack_negativeY_preservesSign() {
        BlockInstruction original = new BlockInstruction((byte) 0, -64, (byte) 0, 0);
        long packed = original.pack();
        BlockInstruction unpacked = BlockInstruction.fromPacked(packed);

        assertThat(unpacked.y()).isEqualTo(-64);
    }

    @Test
    void packAndUnpack_extremeValues_preservesData() {
        BlockInstruction original = new BlockInstruction((byte) 15, CisConstants.MIN_Y, (byte) 15, Integer.MAX_VALUE);
        long packed = original.pack();
        BlockInstruction unpacked = BlockInstruction.fromPacked(packed);

        assertThat(unpacked.x()).isEqualTo((byte) 15);
        assertThat(unpacked.y()).isEqualTo(CisConstants.MIN_Y);
        assertThat(unpacked.z()).isEqualTo((byte) 15);
    }

    @Test
    void packPos_andUnpackComponents_matchOriginal() {
        int x = 7, y = 200, z = 12;
        long packed = BlockInstruction.packPos(x, y, z);

        assertThat(BlockInstruction.unpackX(packed)).isEqualTo(x);
        assertThat(BlockInstruction.unpackY(packed)).isEqualTo(y);
        assertThat(BlockInstruction.unpackZ(packed)).isEqualTo(z);
    }

    @Test
    void unpackY_positiveY_noSignExtension() {
        long packed = BlockInstruction.packPos(0, 100, 0);
        assertThat(BlockInstruction.unpackY(packed)).isEqualTo(100);
    }

    @Test
    void unpackY_negativeY_signExtends() {
        long packed = BlockInstruction.packPos(0, -64, 0);
        assertThat(BlockInstruction.unpackY(packed)).isEqualTo(-64);
    }
}
