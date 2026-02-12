package io.liparakis.chunkis.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link Palette} covering all branches.
 */
class PaletteTest {

    private Palette<String> palette;

    @BeforeEach
    void setUp() {
        palette = new Palette<>();
    }

    // ========== getOrAdd Tests ==========

    @Test
    void getOrAdd_newEntry_assignsSequentialIds() {
        int id1 = palette.getOrAdd("stone");
        int id2 = palette.getOrAdd("dirt");
        int id3 = palette.getOrAdd("grass");

        assertThat(id1).isEqualTo(0);
        assertThat(id2).isEqualTo(1);
        assertThat(id3).isEqualTo(2);
    }

    @Test
    void getOrAdd_existingEntry_returnsSameId() {
        int firstId = palette.getOrAdd("stone");
        int secondId = palette.getOrAdd("stone");

        assertThat(secondId).isEqualTo(firstId);
    }

    @Test
    void getOrAdd_nullEntry_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> palette.getOrAdd(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cannot add null to Palette");
    }

    @Test
    void getOrAdd_multipleEntries_maintainsCorrectMappings() {
        palette.getOrAdd("a");
        palette.getOrAdd("b");
        palette.getOrAdd("c");

        // Add again and verify IDs are stable
        assertThat(palette.getOrAdd("b")).isEqualTo(1);
        assertThat(palette.getOrAdd("a")).isEqualTo(0);
        assertThat(palette.getOrAdd("c")).isEqualTo(2);
    }

    // ========== get Tests ==========

    @Test
    void get_validId_returnsCorrectEntry() {
        palette.getOrAdd("stone");
        palette.getOrAdd("dirt");

        assertThat(palette.get(0)).isEqualTo("stone");
        assertThat(palette.get(1)).isEqualTo("dirt");
    }

    @Test
    void get_negativeId_returnsNull() {
        palette.getOrAdd("stone");

        assertThat(palette.get(-1)).isNull();
        assertThat(palette.get(-100)).isNull();
    }

    @Test
    void get_outOfBoundsId_returnsNull() {
        palette.getOrAdd("stone");

        assertThat(palette.get(1)).isNull();
        assertThat(palette.get(100)).isNull();
    }

    @Test
    void get_emptyPalette_returnsNull() {
        assertThat(palette.get(0)).isNull();
    }

    // ========== getAll Tests ==========

    @Test
    void getAll_returnsAllEntriesInOrder() {
        palette.getOrAdd("a");
        palette.getOrAdd("b");
        palette.getOrAdd("c");

        assertThat(palette.getAll())
                .containsExactly("a", "b", "c");
    }

    @Test
    void getAll_emptyPalette_returnsEmptyList() {
        assertThat(palette.getAll()).isEmpty();
    }

    // ========== Additional Boundary Tests ==========

    @Test
    void get_idEqualsSize_returnsNull() {
        palette.getOrAdd("item");
        // Size is 1. get(1) checks 1 < 1 (False)
        assertThat(palette.get(1)).isNull();
    }

    @Test
    void get_maxInteger_returnsNull() {
        palette.getOrAdd("item");
        assertThat(palette.get(Integer.MAX_VALUE)).isNull();
    }

    @Test
    void get_minInteger_returnsNull() {
        // -2147483648 >= 0 is False
        assertThat(palette.get(Integer.MIN_VALUE)).isNull();
    }
}
