package io.liparakis.chunkis.storage;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.liparakis.chunkis.spi.BlockRegistryAdapter;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CisMappingTest {

    @TempDir
    Path tempDir;

    private final BlockRegistryAdapter<String> registry = new BlockRegistryAdapter<>() {
        @Override
        public String getId(String block) {
            return "minecraft:" + block;
        }

        @Override
        public String getBlock(String id) {
            if (id.startsWith("minecraft:")) {
                return id.substring(10).intern();
            }
            return null;
        }

        @Override
        public String getAir() {
            return "air";
        }
    };

    private final BlockStateAdapter<String, String, String> stateAdapter = new BlockStateAdapter<>() {
        @Override
        public String getDefaultState(String block) {
            if ("lamp".equals(block))
                return "lamp:0";
            return block;
        }

        @Override
        public String getBlock(String state) {
            if (state.startsWith("lamp:"))
                return "lamp";
            return state.intern();
        }

        @Override
        public List<String> getProperties(String block) {
            if ("lamp".equals(block))
                return Collections.singletonList("lit");
            return Collections.emptyList();
        }

        @Override
        public String getPropertyName(String property) {
            return property;
        }

        @Override
        public List<Object> getPropertyValues(String property) {
            if ("lit".equals(property))
                return java.util.Arrays.asList(0, 1);
            return Collections.emptyList();
        }

        @Override
        public int getValueIndex(String state, String property) {
            if ("lamp".equals(getBlock(state)) && "lit".equals(property)) {
                return Integer.parseInt(state.split(":")[1]);
            }
            return 0;
        }

        @Override
        public String withProperty(String state, String property, int valueIndex) {
            if ("lamp".equals(getBlock(state)) && "lit".equals(property)) {
                return "lamp:" + valueIndex;
            }
            return state;
        }

        @Override
        public Comparator<Object> getValueComparator() {
            return (o1, o2) -> ((Integer) o1).compareTo((Integer) o2);
        }

        @Override
        public boolean isAir(String state) {
            return "air".equals(state);
        }
    };

    private final PropertyPacker<String, String, String> packer = new PropertyPacker<>(stateAdapter);

    @Test
    void testStatePropertiesRoundTrip() throws IOException {
        Path mappingFile = tempDir.resolve("mappings.json");
        CisMapping<String, String, String> mapping = new CisMapping<>(mappingFile, registry, stateAdapter, packer);

        // Stone (no properties)
        BitUtils.BitWriter writer = new BitUtils.BitWriter(16);
        mapping.writeStateProperties(writer, "stone");

        BitUtils.BitReader reader = new BitUtils.BitReader(writer.toByteArray());
        // Stone ID is implicitly assigned? No, we need to get ID first
        int stoneId = mapping.getBlockId("stone");
        String readState = mapping.readStateProperties(reader, stoneId);
        assertThat(readState).isEqualTo("stone");

        // Lamp (properties)
        int lampId = mapping.getBlockId("lamp:0"); // Register lamp

        BitUtils.BitWriter lampWriter = new BitUtils.BitWriter(16);
        mapping.writeStateProperties(lampWriter, "lamp:1"); // Write state with val 1

        BitUtils.BitReader lampReader = new BitUtils.BitReader(lampWriter.toByteArray());
        String readLamp = mapping.readStateProperties(lampReader, lampId);
        assertThat(readLamp).isEqualTo("lamp:1");
    }

    @Test
    void testConcurrentGetBlockId() throws IOException, InterruptedException, java.util.concurrent.ExecutionException {
        Path mappingFile = tempDir.resolve("mappings.json");
        CisMapping<String, String, String> mapping = new CisMapping<>(mappingFile, registry, stateAdapter, packer);

        int numberOfThreads = 2;
        java.util.concurrent.ExecutorService service = java.util.concurrent.Executors
                .newFixedThreadPool(numberOfThreads);

        // Run multiple iterations to increase chance of triggering the race condition
        // (Double-checked locking branch: block found in write lock)
        for (int i = 0; i < 100; i++) {
            final String blockName = "race_block_" + i;
            java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);

            java.util.concurrent.Callable<Integer> task = () -> {
                startLatch.await(); // Wait for signal
                return mapping.getBlockId(blockName);
            };

            java.util.concurrent.Future<Integer> f1 = service.submit(task);
            java.util.concurrent.Future<Integer> f2 = service.submit(task);

            startLatch.countDown(); // Go!

            int id1 = f1.get();
            int id2 = f2.get();

            assertThat(id1).isEqualTo(id2);
        }
        service.shutdown();
    }

    @Test
    void loadMappings_LoadsExistingFile() throws IOException {
        Path mappingFile = tempDir.resolve("mappings.json");

        Map<String, Integer> initialMappings = new HashMap<>();
        initialMappings.put("minecraft:stone", 1);
        initialMappings.put("minecraft:dirt", 2);

        try (FileWriter writer = new FileWriter(mappingFile.toFile())) {
            new Gson().toJson(initialMappings, writer);
        }

        CisMapping<String, String, String> mapping = new CisMapping<>(mappingFile, registry, stateAdapter, packer);

        assertThat(mapping.getBlockId("stone")).isEqualTo(1);
        assertThat(mapping.getBlockId("dirt")).isEqualTo(2);
        assertThat(mapping.getBlockId("air")).isNotEqualTo(-1);
    }

    @Test
    void loadMappings_HandlesEmptyFile() throws IOException {
        Path mappingFile = tempDir.resolve("empty_mappings.json");
        Files.createFile(mappingFile);

        CisMapping<String, String, String> mapping = new CisMapping<>(mappingFile, registry, stateAdapter, packer);

        assertThat(mapping.getBlockId("stone")).isGreaterThanOrEqualTo(0);
    }

    @Test
    void loadMappings_SkipsUnknownBlocks() throws IOException {
        Path mappingFile = tempDir.resolve("mappings.json");

        Map<String, Integer> initialMappings = new HashMap<>();
        initialMappings.put("mod:unknown_block", 5);
        initialMappings.put("minecraft:stone", 1);

        try (FileWriter writer = new FileWriter(mappingFile.toFile())) {
            new Gson().toJson(initialMappings, writer);
        }

        // Registry returns null for "mod:unknown_block"
        CisMapping<String, String, String> mapping = new CisMapping<>(mappingFile, registry, stateAdapter, packer);

        assertThat(mapping.getBlockId("stone")).isEqualTo(1);

        // Stone=1 (nextId -> 2). Air is missing -> gets 2 (nextId -> 3).
        // Dirt should get 3.
        int newId = mapping.getBlockId("dirt");
        assertThat(newId).isEqualTo(3);
    }

    @Test
    void loadMappings_SkipsDefaultedBlocks() throws IOException {
        Path mappingFile = tempDir.resolve("mappings.json");
        Map<String, Integer> initialMappings = new HashMap<>();
        initialMappings.put("mod:missing_block", 5);
        initialMappings.put("minecraft:stone", 1);

        try (FileWriter writer = new FileWriter(mappingFile.toFile())) {
            new Gson().toJson(initialMappings, writer);
        }

        // Custom registry that returns Air for unknown blocks
        BlockRegistryAdapter<String> defaultedRegistry = new BlockRegistryAdapter<>() {
            @Override
            public String getId(String block) {
                return "minecraft:" + block;
            }

            @Override
            public String getBlock(String id) {
                if (id.equals("mod:missing_block"))
                    return "air"; // Return default (air)
                if (id.startsWith("minecraft:"))
                    return id.substring(10).intern();
                return null;
            }

            @Override
            public String getAir() {
                return "air";
            }
        };

        CisMapping<String, String, String> mapping = new CisMapping<>(mappingFile, defaultedRegistry, stateAdapter,
                packer);

        // Stone=1. nextId->2.
        // mod:missing skipped.
        // ensureAirMapped() -> Air gets 2. nextId->3.

        int nextIdVal = mapping.getBlockId("dirt");
        assertThat(nextIdVal).isEqualTo(3);
    }

    @Test
    void loadMappings_HandlesUnorderedIds() throws IOException {
        Path mappingFile = tempDir.resolve("mappings.json");
        // Use LinkedHashMap to enforce order in JSON: High ID first, then Low ID
        Map<String, Integer> initialMappings = new java.util.LinkedHashMap<>();
        initialMappings.put("minecraft:stone", 10);
        initialMappings.put("minecraft:dirt", 2);

        try (FileWriter writer = new FileWriter(mappingFile.toFile())) {
            new Gson().toJson(initialMappings, writer);
        }

        CisMapping<String, String, String> mapping = new CisMapping<>(mappingFile, registry, stateAdapter, packer);

        assertThat(mapping.getBlockId("stone")).isEqualTo(10);
        assertThat(mapping.getBlockId("dirt")).isEqualTo(2);

        // Stone=10 -> nextId=11.
        // Dirt=2 (2 < 11).
        // ensureAirMapped -> Air gets 11. nextId=12.

        int nextIdVal = mapping.getBlockId("grass");
        assertThat(nextIdVal).isEqualTo(12);
    }

    @Test
    void loadMappings_LoadsExplicitAir() throws IOException {
        Path mappingFile = tempDir.resolve("mappings.json");
        Map<String, Integer> initialMappings = new HashMap<>();
        initialMappings.put("minecraft:air", 0);
        initialMappings.put("minecraft:stone", 1);

        try (FileWriter writer = new FileWriter(mappingFile.toFile())) {
            new Gson().toJson(initialMappings, writer);
        }

        CisMapping<String, String, String> mapping = new CisMapping<>(mappingFile, registry, stateAdapter, packer);

        assertThat(mapping.getBlockId("air")).isEqualTo(0);
        assertThat(mapping.getBlockId("stone")).isEqualTo(1);
    }

    @Test
    void flush_HandlesWriteError() throws IOException {
        Path mappingFile = tempDir.resolve("mappings_error.json");
        CisMapping<String, String, String> mapping = new CisMapping<>(mappingFile, registry, stateAdapter, packer);

        mapping.getBlockId("stone"); // Add entry to trigger save needed

        // Make the path a directory so FileWriter fails
        // Ensure it doesn't exist as a file first (CisMapping doesn't create it on
        // init, but just to be safe)
        Files.deleteIfExists(mappingFile);
        Files.createDirectory(mappingFile);

        // Should not throw (catches Exception and logs to stderr)
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> mapping.flush());
    }

    @Test
    void testFlushSnapshotRaceCondition() throws Exception {
        Path mappingFile = tempDir.resolve("mappings.json");
        CisMapping<String, String, String> mapping = new CisMapping<>(mappingFile, registry, stateAdapter, packer);

        // 1. Add data and flush to sync savedCount = size
        mapping.getBlockId("stone");
        mapping.flush();

        // 2. Access private method createMappingSnapshot
        java.lang.reflect.Method method = CisMapping.class.getDeclaredMethod("createMappingSnapshot");
        method.setAccessible(true);

        // 3. Invoke it. Logic: if (size <= savedCount) return empty map
        @SuppressWarnings("unchecked")
        Map<String, Integer> snapshot = (Map<String, Integer>) method.invoke(mapping);

        // 4. Verify optimization branch took effect
        assertThat(snapshot).isEmpty();

        // 5. Add new data (dirty state)
        mapping.getBlockId("dirt");

        // 6. Invoke again. Logic: size > savedCount -> return full map
        @SuppressWarnings("unchecked")
        Map<String, Integer> fullSnapshot = (Map<String, Integer>) method.invoke(mapping);

        assertThat(fullSnapshot).isNotEmpty();
        assertThat(fullSnapshot).containsKey("minecraft:dirt");
    }

    @Test
    void flush_WritesNewMappings() throws IOException {
        Path mappingFile = tempDir.resolve("mappings.json");
        CisMapping<String, String, String> mapping = new CisMapping<>(mappingFile, registry, stateAdapter, packer);

        mapping.getBlockId("stone");
        mapping.flush();

        // Verify file content
        assertThat(Files.exists(mappingFile)).isTrue();

        Map<String, Integer> saved = new Gson().fromJson(Files.newBufferedReader(mappingFile),
                new TypeToken<Map<String, Integer>>() {
                }.getType());
        assertThat(saved).containsEntry("minecraft:stone", mapping.getBlockId("stone"));
    }

    @Test
    void readStateProperties_ThrowsOnInvalidId() throws IOException {
        Path mappingFile = tempDir.resolve("mappings.json");
        CisMapping<String, String, String> mapping = new CisMapping<>(mappingFile, registry, stateAdapter, packer);

        BitUtils.BitReader reader = new BitUtils.BitReader(new byte[0]);

        assertThatThrownBy(() -> mapping.readStateProperties(reader, 999))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Unknown Block ID 999");
    }

    @Test
    void ensureAirMapped_MapsAirOnStart() throws IOException {
        Path mappingFile = tempDir.resolve("mappings.json");
        CisMapping<String, String, String> mapping = new CisMapping<>(mappingFile, registry, stateAdapter, packer);

        // Air should be mapped immediately
        int airId = mapping.getBlockId("air");
        assertThat(airId).isEqualTo(0);

        assertThat(Files.exists(mappingFile)).isTrue();
    }
}
