package io.liparakis.chunkis.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CisChunkPosTest {

    @Test
    void constructor_assignmentsCorrect() {
        CisChunkPos pos = new CisChunkPos(10, -5);
        assertThat(pos.x()).isEqualTo(10);
        assertThat(pos.z()).isEqualTo(-5);
    }

    @Test
    void equals_sameCoordinates_returnsTrue() {
        CisChunkPos pos1 = new CisChunkPos(1, 2);
        CisChunkPos pos2 = new CisChunkPos(1, 2);
        assertThat(pos1).isEqualTo(pos2);
    }

    @Test
    void equals_differentCoordinates_returnsFalse() {
        CisChunkPos pos1 = new CisChunkPos(1, 2);
        CisChunkPos pos2 = new CisChunkPos(1, 3);
        assertThat(pos1).isNotEqualTo(pos2);
    }

    @Test
    void hashCode_sameCoordinates_returnsSameHash() {
        CisChunkPos pos1 = new CisChunkPos(5, 5);
        CisChunkPos pos2 = new CisChunkPos(5, 5);
        assertThat(pos1.hashCode()).isEqualTo(pos2.hashCode());
    }

    @Test
    void toString_containsCoordinates() {
        CisChunkPos pos = new CisChunkPos(10, 20);
        assertThat(pos.toString()).contains("10", "20");
    }
}
