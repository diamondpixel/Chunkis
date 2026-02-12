package io.liparakis.chunkis.storage.codec;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.spi.BlockRegistryAdapter;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.spi.NbtAdapter;
import io.liparakis.chunkis.storage.PropertyPacker;
import org.junit.jupiter.api.Test;

import java.io.DataOutput;
import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CisNetworkEncoderTest {

    @Test
    void testEncode() throws IOException {
        // Setup adapters
        TestBlockRegistry registry = new TestBlockRegistry();
        TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        PropertyPacker<String, String, String> packer = new PropertyPacker<>(stateAdapter);
        TestNbtAdapter nbtAdapter = new TestNbtAdapter();

        CisNetworkEncoder<String, String, String, String> encoder = new CisNetworkEncoder<>(
                registry, packer, stateAdapter, nbtAdapter, "minecraft:air");

        // Create a delta
        ChunkDelta<String, String> delta = new ChunkDelta<>(s -> s.equals("minecraft:air"));
        delta.addBlockChange(0, 0, 0, "minecraft:stone");
        delta.addBlockChange(15, 15, 15, "minecraft:dirt");

        // Encode
        byte[] encoded = encoder.encode(delta);

        assertNotNull(encoded);
        assertTrue(encoded.length > 0);

        // We can't easily verify the exact bytes without decoding,
        // but we can ensure it runs without error and produces output.
        // The coverage report will confirm we hit the methods.
    }

    @Test
    void testEncodeDenseSection() throws IOException {
        // This test covers encodeDenseSection, buildLocalPalette, ensureAirInPalette,
        // writeBlockData
        TestBlockRegistry registry = new TestBlockRegistry();
        TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        PropertyPacker<String, String, String> packer = new PropertyPacker<>(stateAdapter);
        TestNbtAdapter nbtAdapter = new TestNbtAdapter();

        CisNetworkEncoder<String, String, String, String> encoder = new CisNetworkEncoder<>(
                registry, packer, stateAdapter, nbtAdapter, "minecraft:air");

        // Create a delta with many blocks to trigger dense encoding
        // Dense mode is triggered by the encoder when cost analysis favors it
        ChunkDelta<String, String> delta = new ChunkDelta<>(s -> s.equals("minecraft:air"));

        // Fill an entire 16x16x16 section with a mix of blocks
        // This should trigger dense encoding in the section
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    // Alternate between stone and dirt to have multiple palette entries
                    String block = ((x + y + z) % 2 == 0) ? "minecraft:stone" : "minecraft:dirt";
                    delta.addBlockChange(x, y, z, block);
                }
            }
        }

        // Encode
        byte[] encoded = encoder.encode(delta);

        assertNotNull(encoded);
        assertTrue(encoded.length > 0);

        // Decode and verify
        CisNetworkDecoder<String, String, String, String> decoder = new CisNetworkDecoder<>(
                registry, packer, stateAdapter, nbtAdapter, "minecraft:air");
        ChunkDelta<String, String> decoded = decoder.decode(encoded);

        // Should have 4096 blocks
        int[] count = new int[1];
        decoded.accept(new ChunkDelta.DeltaVisitor<>() {
            @Override
            public void visitBlock(int x, int y, int z, String state) {
                count[0]++;
            }

            @Override
            public void visitBlockEntity(int x, int y, int z, String nbt) {
            }

            @Override
            public void visitEntity(String nbt) {
            }
        });
        assertEquals(4096, count[0], "Should encode/decode all 4096 blocks");
    }

    @Test
    void testEncodeWithBlockEntities() throws IOException {
        TestBlockRegistry registry = new TestBlockRegistry();
        TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        PropertyPacker<String, String, String> packer = new PropertyPacker<>(stateAdapter);
        TestNbtAdapter nbtAdapter = new TestNbtAdapter();

        CisNetworkEncoder<String, String, String, String> encoder = new CisNetworkEncoder<>(
                registry, packer, stateAdapter, nbtAdapter, "minecraft:air");

        // Create delta with block entities
        ChunkDelta<String, String> delta = new ChunkDelta<>(s -> s.equals("minecraft:air"));
        delta.addBlockChange(0, 0, 0, "minecraft:chest");
        delta.addBlockEntityData((byte) 0, 0, (byte) 0, "{id:chest,Items:[]}");

        byte[] encoded = encoder.encode(delta);
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);

        // Decode and verify block entity
        CisNetworkDecoder<String, String, String, String> decoder = new CisNetworkDecoder<>(
                registry, packer, stateAdapter, nbtAdapter, "minecraft:air");
        ChunkDelta<String, String> decoded = decoder.decode(encoded);

        assertEquals(1, decoded.getBlockEntities().size());
    }

    @Test
    void testEncodeWithEntities() throws IOException {
        TestBlockRegistry registry = new TestBlockRegistry();
        TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        PropertyPacker<String, String, String> packer = new PropertyPacker<>(stateAdapter);
        TestNbtAdapter nbtAdapter = new TestNbtAdapter();

        CisNetworkEncoder<String, String, String, String> encoder = new CisNetworkEncoder<>(
                registry, packer, stateAdapter, nbtAdapter, "minecraft:air");

        // Create delta with entities
        ChunkDelta<String, String> delta = new ChunkDelta<>(s -> s.equals("minecraft:air"));
        delta.setEntities(List.of("{id:pig,Pos:[0.0,64.0,0.0]}"), false);

        byte[] encoded = encoder.encode(delta);
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);

        // Decode and verify entity
        CisNetworkDecoder<String, String, String, String> decoder = new CisNetworkDecoder<>(
                registry, packer, stateAdapter, nbtAdapter, "minecraft:air");
        ChunkDelta<String, String> decoded = decoder.decode(encoded);

        assertEquals(1, decoded.getEntitiesList().size());
    }

    @Test
    void testEncodeEmptyDelta() throws IOException {
        TestBlockRegistry registry = new TestBlockRegistry();
        TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        PropertyPacker<String, String, String> packer = new PropertyPacker<>(stateAdapter);
        TestNbtAdapter nbtAdapter = new TestNbtAdapter();

        CisNetworkEncoder<String, String, String, String> encoder = new CisNetworkEncoder<>(
                registry, packer, stateAdapter, nbtAdapter, "minecraft:air");

        // Empty delta
        ChunkDelta<String, String> delta = new ChunkDelta<>(s -> s.equals("minecraft:air"));

        byte[] encoded = encoder.encode(delta);
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);
    }

    @Test
    void testEncodeDenseSectionDirectly() throws Exception {
        // This test directly creates a CisChunk with a dense section to cover
        // encodeDenseSection, buildLocalPalette, ensureAirInPalette, writeBlockData
        TestBlockRegistry registry = new TestBlockRegistry();
        TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        PropertyPacker<String, String, String> packer = new PropertyPacker<>(stateAdapter);
        TestNbtAdapter nbtAdapter = new TestNbtAdapter();

        CisNetworkEncoder<String, String, String, String> encoder = new CisNetworkEncoder<>(
                registry, packer, stateAdapter, nbtAdapter, "minecraft:air");

        // Create a CisChunk directly with a dense section
        io.liparakis.chunkis.storage.CisChunk<String> chunk = new io.liparakis.chunkis.storage.CisChunk<>();

        // Create a section and set it to dense mode
        io.liparakis.chunkis.storage.CisSection<String> section = new io.liparakis.chunkis.storage.CisSection<>();
        section.mode = io.liparakis.chunkis.storage.CisSection.MODE_DENSE;
        section.denseBlocks = new Object[4096];

        // Fill with mixed states to cover all branches in writeBlockData
        for (int i = 0; i < 4096; i++) {
            int type = i % 4;
            if (type == 0) {
                section.denseBlocks[i] = "minecraft:stone";
            } else if (type == 1) {
                section.denseBlocks[i] = "minecraft:dirt";
            } else if (type == 2) {
                section.denseBlocks[i] = "minecraft:air"; // Not null, but isAir -> True
            } else {
                section.denseBlocks[i] = null; // Null -> True
            }
        }

        // Add the section to chunk at Y=0
        chunk.getSections().put(0, section);

        // Use reflection to call the internal encoding methods
        // First, get the EncoderContext
        java.lang.reflect.Method getContextMethod = AbstractCisEncoder.class.getDeclaredMethod("getContext");
        getContextMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        AbstractCisEncoder.EncoderContext<String> ctx = (AbstractCisEncoder.EncoderContext<String>) getContextMethod
                .invoke(encoder);
        ctx.reset();

        // Collect used states (this covers the dense section path in collectUsedStates)
        java.lang.reflect.Method collectUsedStatesMethod = AbstractCisEncoder.class.getDeclaredMethod(
                "collectUsedStates", io.liparakis.chunkis.storage.CisChunk.class);
        collectUsedStatesMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<String> usedStates = (java.util.List<String>) collectUsedStatesMethod.invoke(encoder, chunk);

        // Build the global ID map (needed for encoding)
        ctx.globalIdMap.clear();
        ctx.globalIdMap.defaultReturnValue(-1);
        for (int i = 0; i < usedStates.size(); i++) {
            ctx.globalIdMap.put(usedStates.get(i), i);
        }

        // Now call writeSections which will route to encodeDenseSection
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);

        java.lang.reflect.Method writeSectionsMethod = AbstractCisEncoder.class.getDeclaredMethod(
                "writeSections", java.io.DataOutputStream.class,
                AbstractCisEncoder.EncoderContext.class,
                io.liparakis.chunkis.storage.CisChunk.class);
        writeSectionsMethod.setAccessible(true);
        writeSectionsMethod.invoke(encoder, dos, ctx, chunk);

        // Verify output was generated
        byte[] output = baos.toByteArray();
        assertTrue(output.length > 0, "Should have encoded dense section data");

        // Verify the states were collected correctly
        assertTrue(usedStates.contains("minecraft:stone"), "Should contain stone");
        assertTrue(usedStates.contains("minecraft:dirt"), "Should contain dirt");
    }

    @Test
    void testEncodeDenseAllAir() throws Exception {
        // This test covers the case where a dense section contains only Air.
        // This results in localSize=1 (Air), bitsPerBlock=0.
        // Covers: calculateBitsNeeded(1) -> 0
        // Covers: encodeDenseSection -> bitsPerBlock > 0 (false branch)

        TestBlockRegistry registry = new TestBlockRegistry();
        TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        PropertyPacker<String, String, String> packer = new PropertyPacker<>(stateAdapter);
        TestNbtAdapter nbtAdapter = new TestNbtAdapter();

        CisNetworkEncoder<String, String, String, String> encoder = new CisNetworkEncoder<>(
                registry, packer, stateAdapter, nbtAdapter, "minecraft:air");

        // Create dense section full of air
        io.liparakis.chunkis.storage.CisChunk<String> chunk = new io.liparakis.chunkis.storage.CisChunk<>();
        io.liparakis.chunkis.storage.CisSection<String> section = new io.liparakis.chunkis.storage.CisSection<>();
        section.mode = io.liparakis.chunkis.storage.CisSection.MODE_DENSE;
        section.denseBlocks = new Object[4096];
        // Fill with nulls (Air)
        for (int i = 0; i < 4096; i++) {
            section.denseBlocks[i] = null;
        }
        chunk.getSections().put(0, section);

        // Access context and methods via reflection
        java.lang.reflect.Method getContextMethod = AbstractCisEncoder.class.getDeclaredMethod("getContext");
        getContextMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        AbstractCisEncoder.EncoderContext<String> ctx = (AbstractCisEncoder.EncoderContext<String>) getContextMethod
                .invoke(encoder);
        ctx.reset();

        // 1. collectUsedStates (should only find Air)
        java.lang.reflect.Method collectUsedStatesMethod = AbstractCisEncoder.class.getDeclaredMethod(
                "collectUsedStates", io.liparakis.chunkis.storage.CisChunk.class);
        collectUsedStatesMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<String> usedStates = (java.util.List<String>) collectUsedStatesMethod.invoke(encoder, chunk);
        assertFalse(usedStates.isEmpty(), "Dense section full of air should have states");

        // 2. Setup global ID map
        ctx.globalIdMap.clear();
        ctx.globalIdMap.defaultReturnValue(-1);
        ctx.globalIdMap.put("minecraft:air", 0);

        // 3. writeSections
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);

        java.lang.reflect.Method writeSectionsMethod = AbstractCisEncoder.class.getDeclaredMethod(
                "writeSections", java.io.DataOutputStream.class,
                AbstractCisEncoder.EncoderContext.class,
                io.liparakis.chunkis.storage.CisChunk.class);
        writeSectionsMethod.setAccessible(true);
        writeSectionsMethod.invoke(encoder, dos, ctx, chunk);

        byte[] output = baos.toByteArray();
        assertTrue(output.length > 0);
        // We expect localSize=1 -> bitsPerBlock=0 -> no data block written (only
        // palette)
    }

    @Test
    void testEncodeEmptySectionDirectly() throws Exception {
        // Covers encodeSection -> MODE_EMPTY branch
        TestBlockRegistry registry = new TestBlockRegistry();
        TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        PropertyPacker<String, String, String> packer = new PropertyPacker<>(stateAdapter);
        TestNbtAdapter nbtAdapter = new TestNbtAdapter();

        CisNetworkEncoder<String, String, String, String> encoder = new CisNetworkEncoder<>(
                registry, packer, stateAdapter, nbtAdapter, "minecraft:air");

        io.liparakis.chunkis.storage.CisChunk<String> chunk = new io.liparakis.chunkis.storage.CisChunk<>();
        io.liparakis.chunkis.storage.CisSection<String> section = new io.liparakis.chunkis.storage.CisSection<>();
        section.mode = io.liparakis.chunkis.storage.CisSection.MODE_EMPTY;
        chunk.getSections().put(0, section);

        // Setup context
        java.lang.reflect.Method getContextMethod = AbstractCisEncoder.class.getDeclaredMethod("getContext");
        getContextMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        AbstractCisEncoder.EncoderContext<String> ctx = (AbstractCisEncoder.EncoderContext<String>) getContextMethod
                .invoke(encoder);
        ctx.reset();

        // Setup global ID map with Air
        ctx.globalIdMap.put("minecraft:air", 0);

        // writeSections
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);

        java.lang.reflect.Method writeSectionsMethod = AbstractCisEncoder.class.getDeclaredMethod(
                "writeSections", java.io.DataOutputStream.class,
                AbstractCisEncoder.EncoderContext.class,
                io.liparakis.chunkis.storage.CisChunk.class);
        writeSectionsMethod.setAccessible(true);
        writeSectionsMethod.invoke(encoder, dos, ctx, chunk);

        byte[] output = baos.toByteArray();
        assertTrue(output.length > 0);
    }

    @Test
    void testEncodeSparseSectionEmpty() throws Exception {
        // Covers encodeSparseSection -> sparseSize > 0 (false branch)
        TestBlockRegistry registry = new TestBlockRegistry();
        TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        PropertyPacker<String, String, String> packer = new PropertyPacker<>(stateAdapter);
        TestNbtAdapter nbtAdapter = new TestNbtAdapter();

        CisNetworkEncoder<String, String, String, String> encoder = new CisNetworkEncoder<>(
                registry, packer, stateAdapter, nbtAdapter, "minecraft:air");

        io.liparakis.chunkis.storage.CisChunk<String> chunk = new io.liparakis.chunkis.storage.CisChunk<>();
        io.liparakis.chunkis.storage.CisSection<String> section = new io.liparakis.chunkis.storage.CisSection<>();
        section.mode = io.liparakis.chunkis.storage.CisSection.MODE_SPARSE;
        section.sparseSize = 0; // Empty sparse section
        chunk.getSections().put(0, section);

        // Setup context
        java.lang.reflect.Method getContextMethod = AbstractCisEncoder.class.getDeclaredMethod("getContext");
        getContextMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        AbstractCisEncoder.EncoderContext<String> ctx = (AbstractCisEncoder.EncoderContext<String>) getContextMethod
                .invoke(encoder);
        ctx.reset();

        ctx.globalIdMap.put("minecraft:air", 0);

        // writeSections
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);

        java.lang.reflect.Method writeSectionsMethod = AbstractCisEncoder.class.getDeclaredMethod(
                "writeSections", java.io.DataOutputStream.class,
                AbstractCisEncoder.EncoderContext.class,
                io.liparakis.chunkis.storage.CisChunk.class);
        writeSectionsMethod.setAccessible(true);
        writeSectionsMethod.invoke(encoder, dos, ctx, chunk);

        byte[] output = baos.toByteArray();
        assertTrue(output.length > 0);
    }

    @Test
    void testFromDeltaNullState() throws Exception {
        // Covers fromDelta -> state != null (false branch)
        TestBlockRegistry registry = new TestBlockRegistry();
        TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        PropertyPacker<String, String, String> packer = new PropertyPacker<>(stateAdapter);
        TestNbtAdapter nbtAdapter = new TestNbtAdapter();

        CisNetworkEncoder<String, String, String, String> encoder = new CisNetworkEncoder<>(
                registry, packer, stateAdapter, nbtAdapter, "minecraft:air");

        // Create a delta
        ChunkDelta<String, String> delta = new ChunkDelta<>(s -> s.equals("minecraft:air"));
        delta.addBlockChange(0, 0, 0, "minecraft:stone");

        // Corrupt the palette to return null for the stone entry
        // ChunkDelta.getBlockPalette() returns the Palette
        // Palette.getAll() returns the internal list which is mutable
        io.liparakis.chunkis.core.Palette<String> palette = delta.getBlockPalette();
        java.util.List<String> entries = palette.getAll();

        // Find "minecraft:stone" index and set to null
        int index = entries.indexOf("minecraft:stone");
        if (index != -1) {
            entries.set(index, null);
        }

        // Encode - this trigger fromDelta -> state != null check
        // The block should be skipped during conversion to CisChunk
        byte[] encoded = encoder.encode(delta);
        assertNotNull(encoded);

        // Verify result is valid but empty (or contains no blocks)
        // Since we skipped the only block, the chunk should be empty/sparse with size 0
        CisNetworkDecoder<String, String, String, String> decoder = new CisNetworkDecoder<>(
                registry, packer, stateAdapter, nbtAdapter, "minecraft:air");
        ChunkDelta<String, String> decoded = decoder.decode(encoded);

        // Should have 0 block changes
        assertEquals(0, decoded.getBlockInstructions().size());
    }

    @Test
    void testEnsureAirInPaletteAlreadyPresent() throws Exception {
        // Covers ensureAirInPalette -> air already in palette (true branch)
        TestBlockRegistry registry = new TestBlockRegistry();
        TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        PropertyPacker<String, String, String> packer = new PropertyPacker<>(stateAdapter);
        TestNbtAdapter nbtAdapter = new TestNbtAdapter();

        CisNetworkEncoder<String, String, String, String> encoder = new CisNetworkEncoder<>(
                registry, packer, stateAdapter, nbtAdapter, "minecraft:air");

        // Get context
        java.lang.reflect.Method getContextMethod = AbstractCisEncoder.class.getDeclaredMethod("getContext");
        getContextMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        AbstractCisEncoder.EncoderContext<String> ctx = (AbstractCisEncoder.EncoderContext<String>) getContextMethod
                .invoke(encoder);
        ctx.reset();

        // Manually add air to palette
        ctx.fastLocalPaletteIndex.put("minecraft:air", 123);

        // Call ensureAirInPalette
        java.lang.reflect.Method ensureAirInPaletteMethod = AbstractCisEncoder.class.getDeclaredMethod(
                "ensureAirInPalette", AbstractCisEncoder.EncoderContext.class);
        ensureAirInPaletteMethod.setAccessible(true);
        int index = (int) ensureAirInPaletteMethod.invoke(encoder, ctx);

        assertEquals(123, index);
    }

    @Test
    void testEncodeSparseSectionUnknownBlock() throws Exception {
        // Covers encodeSparseSection -> globalIdx == -1 (false branch)
        TestBlockRegistry registry = new TestBlockRegistry();
        TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        PropertyPacker<String, String, String> packer = new PropertyPacker<>(stateAdapter);
        TestNbtAdapter nbtAdapter = new TestNbtAdapter();

        CisNetworkEncoder<String, String, String, String> encoder = new CisNetworkEncoder<>(
                registry, packer, stateAdapter, nbtAdapter, "minecraft:air");

        io.liparakis.chunkis.storage.CisChunk<String> chunk = new io.liparakis.chunkis.storage.CisChunk<>();
        io.liparakis.chunkis.storage.CisSection<String> section = new io.liparakis.chunkis.storage.CisSection<>();
        section.mode = io.liparakis.chunkis.storage.CisSection.MODE_SPARSE;

        // Initialize sparse arrays via reflection
        java.lang.reflect.Field sparseKeysField = io.liparakis.chunkis.storage.CisSection.class
                .getDeclaredField("sparseKeys");
        sparseKeysField.setAccessible(true);
        sparseKeysField.set(section, new short[16]);

        java.lang.reflect.Field sparseValuesField = io.liparakis.chunkis.storage.CisSection.class
                .getDeclaredField("sparseValues");
        sparseValuesField.setAccessible(true);
        sparseValuesField.set(section, new Object[16]);

        // Add a block to sparse storage manually
        java.lang.reflect.Method addSparseEntryMethod = io.liparakis.chunkis.storage.CisSection.class.getDeclaredMethod(
                "addSparseEntry", short.class, Object.class);
        addSparseEntryMethod.setAccessible(true);
        addSparseEntryMethod.invoke(section, (short) 0, "minecraft:unknown_block");

        chunk.getSections().put(0, section);

        // Get context and clear global map (so block lookup fails)
        java.lang.reflect.Method getContextMethod = AbstractCisEncoder.class.getDeclaredMethod("getContext");
        getContextMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        AbstractCisEncoder.EncoderContext<String> ctx = (AbstractCisEncoder.EncoderContext<String>) getContextMethod
                .invoke(encoder);
        ctx.reset();
        ctx.globalIdMap.clear();
        ctx.globalIdMap.defaultReturnValue(-1);

        // Call invoke encodeSparseSection indirectly via writeSections
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);

        java.lang.reflect.Method writeSectionsMethod = AbstractCisEncoder.class.getDeclaredMethod(
                "writeSections", java.io.DataOutputStream.class,
                AbstractCisEncoder.EncoderContext.class,
                io.liparakis.chunkis.storage.CisChunk.class);
        writeSectionsMethod.setAccessible(true);
        writeSectionsMethod.invoke(encoder, dos, ctx, chunk);

        byte[] output = baos.toByteArray();
        assertTrue(output.length > 0);
    }

    static class TestBlockRegistry implements BlockRegistryAdapter<String> {
        @Override
        public String getId(String block) {
            return block;
        }

        @Override
        public String getBlock(String id) {
            return id;
        }

        public String getAir() {
            return "minecraft:air";
        }
    }

    static class TestBlockStateAdapter implements BlockStateAdapter<String, String, String> {
        @Override
        public String getDefaultState(String block) {
            return block;
        }

        @Override
        public String getBlock(String state) {
            return state;
        }

        @Override
        public List<String> getProperties(String block) {
            return Collections.emptyList();
        }

        @Override
        public String getPropertyName(String property) {
            return property;
        }

        @Override
        public List<Object> getPropertyValues(String property) {
            return Collections.emptyList();
        }

        @Override
        public int getValueIndex(String state, String property) {
            return 0;
        }

        @Override
        public String withProperty(String state, String property, int valueIndex) {
            return state;
        }

        @Override
        public Comparator<Object> getValueComparator() {
            return (o1, o2) -> 0;
        }

        @Override
        public boolean isAir(String state) {
            return "minecraft:air".equals(state);
        }
    }

    static class TestNbtAdapter implements NbtAdapter<String> {
        @Override
        public void write(String nbt, DataOutput output) throws IOException {
            output.writeUTF(nbt);
        }

        @Override
        public String read(java.io.DataInput input) throws IOException {
            return input.readUTF();
        }
    }
}
