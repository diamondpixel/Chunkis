package io.liparakis.chunkis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkisTest {

    @Test
    void modId_isCorrect() {
        assertThat(Chunkis.MOD_ID).isEqualTo("chunkis");
    }

    @Test
    void logger_isInitializedWithModId() {
        assertThat(Chunkis.LOGGER).isNotNull();
        assertThat(Chunkis.LOGGER.getName()).isEqualTo("chunkis");
    }

    @Test
    void canInstantiate() {
        new Chunkis();
    }
}
