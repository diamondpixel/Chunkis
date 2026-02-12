package io.liparakis.chunkis.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CisChunkTest {

    private CisChunk<String> chunk;

    @BeforeEach
    void setUp() {
        chunk = new CisChunk<>();
    }

    @Test
    void addBlockCreatesNewSection() {
        // 1. Cache Miss -> New Section (and lastSection is initially null/mismatched)
        chunk.addBlock(0, 0, 0, "Stone");

        assertThat(chunk.getSections()).hasSize(1);
        assertThat(chunk.getSortedSectionIndices()).containsExactly(0);
    }

    @Test
    void addBlockUsesCacheIdeally() {
        // 1. Initial add: Cache Miss -> New Section
        chunk.addBlock(0, 0, 0, "Stone");

        // 2. Second add to same section: Cache Hit (sectionY == lastSectionY &&
        // lastSection != null)
        chunk.addBlock(1, 0, 0, "Dirt");

        assertThat(chunk.getSections()).hasSize(1);
    }

    @Test
    void addBlockHandlesSectionSwitching() {
        // 1. Add to Section 0
        chunk.addBlock(0, 0, 0, "Stone");

        // 2. Add to Section 1 (Cache Miss -> New Section)
        chunk.addBlock(0, 16, 0, "Dirt");
        assertThat(chunk.getSections()).hasSize(2);

        // 3. Add to Section 0 AGAIN (Cache Miss -> Existing Section)
        // This covers the branch where sectionY != lastSectionY but
        // sections.get(sectionY) != null
        chunk.addBlock(1, 0, 0, "Gravel");

        assertThat(chunk.getSections()).hasSize(2);
        assertThat(chunk.getSortedSectionIndices()).containsExactly(0, 1);
    }
}
