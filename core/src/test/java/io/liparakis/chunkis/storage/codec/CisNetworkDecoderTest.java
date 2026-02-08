package io.liparakis.chunkis.storage.codec;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.spi.BlockRegistryAdapter;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.spi.NbtAdapter;
import io.liparakis.chunkis.storage.PropertyPacker;
import io.liparakis.chunkis.storage.BitUtils;
import io.liparakis.chunkis.storage.CisConstants;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CisNetworkDecoderTest {

    @Test
    void testRoundTrip() throws IOException {
        // Setup adapters
        TestBlockRegistry registry = new TestBlockRegistry();
        TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        PropertyPacker<String, String, String> packer = new PropertyPacker<>(stateAdapter);
        TestNbtAdapter nbtAdapter = new TestNbtAdapter();

        CisNetworkEncoder<String, String, String, String> encoder = new CisNetworkEncoder<>(
                registry, packer, stateAdapter, nbtAdapter, "minecraft:air");

        CisNetworkDecoder<String, String, String, String> decoder = new CisNetworkDecoder<>(
                registry, packer, stateAdapter, nbtAdapter, "minecraft:air");

        // Create a delta
        ChunkDelta<String, String> originalDelta = new ChunkDelta<>(s -> s.equals("minecraft:air"));
        originalDelta.addBlockChange(0, 0, 0, "minecraft:stone");
        originalDelta.addBlockChange(15, 15, 15, "minecraft:dirt");
        originalDelta.addBlockEntityData(0, 0, 0, "{id:test_be}");
        originalDelta.setEntities(List.of("{id:test_entity}"));

        // Encode
        byte[] encoded = encoder.encode(originalDelta);
        assertNotNull(encoded);

        // Decode
        ChunkDelta<String, String> decoded = decoder.decode(encoded);
        assertNotNull(decoded);

        // Verify Contents
        // 1. Visit blocks
        AtomicInteger blockCount = new AtomicInteger(0);
        decoded.accept(new ChunkDelta.DeltaVisitor<>() {
            @Override
            public void visitBlock(int x, int y, int z, String state) {
                blockCount.incrementAndGet();
                if (x == 0 && y == 0 && z == 0)
                    assertEquals("minecraft:stone", state);
                if (x == 15 && y == 15 && z == 15)
                    assertEquals("minecraft:dirt", state);
            }

            @Override
            public void visitBlockEntity(int x, int y, int z, String nbt) {
                assertEquals(0, x);
                assertEquals(0, y);
                assertEquals(0, z);
                assertEquals("{id:test_be}", nbt);
            }

            @Override
            public void visitEntity(String nbt) {
                assertEquals("{id:test_entity}", nbt);
            }
        });

        assertEquals(2, blockCount.get());
        assertEquals(1, decoded.getBlockEntities().size());
        assertEquals(1, decoded.getEntitiesList().size());
    }

    @Test
    void testInvalidPaletteSize() throws IOException {
        // Setup simple encoder
        TestBlockRegistry registry = new TestBlockRegistry();
        TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        PropertyPacker<String, String, String> packer = new PropertyPacker<>(stateAdapter);
        TestNbtAdapter nbtAdapter = new TestNbtAdapter();

        CisNetworkEncoder<String, String, String, String> encoder = new CisNetworkEncoder<>(
                registry, packer, stateAdapter, nbtAdapter, "minecraft:air");
        CisNetworkDecoder<String, String, String, String> decoder = new CisNetworkDecoder<>(
                registry, packer, stateAdapter, nbtAdapter, "minecraft:air");

        ChunkDelta<String, String> delta = new ChunkDelta<>(s -> s.equals("minecraft:air"));
        byte[] encoded = encoder.encode(delta);

        // 1. Test Negative Size
        byte[] negativeSize = encoded.clone();
        // Offset 8 is where global palette size is (Header is 8 bytes)
        // Set to -1 (0xFFFFFFFF)
        negativeSize[8] = (byte) 0xFF;
        negativeSize[9] = (byte) 0xFF;
        negativeSize[10] = (byte) 0xFF;
        negativeSize[11] = (byte) 0xFF;

        assertThrows(IOException.class, () -> decoder.decode(negativeSize));

        // 2. Test Too Large Size (> 10000)
        byte[] tooLarge = encoded.clone();
        // Set to 10001 (0x00002711)
        tooLarge[8] = 0;
        tooLarge[9] = 0;
        tooLarge[10] = 0x27;
        tooLarge[11] = 0x11;

        assertThrows(IOException.class, () -> decoder.decode(tooLarge));
    }

    @Test
    void testUnknownBlockFallback() throws IOException {
        TestBlockRegistry registry = new TestBlockRegistry();
        TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        PropertyPacker<String, String, String> packer = new PropertyPacker<>(stateAdapter);
        TestNbtAdapter nbtAdapter = new TestNbtAdapter();

        CisNetworkEncoder<String, String, String, String> encoder = new CisNetworkEncoder<>(
                registry, packer, stateAdapter, nbtAdapter, "minecraft:air");

        // Create delta with a "unknown" block
        ChunkDelta<String, String> delta = new ChunkDelta<>(s -> s.equals("minecraft:air"));
        delta.addBlockChange(0, 0, 0, "mod:unknown_block");
        byte[] encoded = encoder.encode(delta);

        // Use a strict registry that returns null for unknown blocks
        BlockRegistryAdapter<String> strictRegistry = new BlockRegistryAdapter<>() {
            @Override
            public String getId(String block) {
                return block;
            }

            @Override
            public String getBlock(String id) {
                if ("mod:unknown_block".equals(id))
                    return null;
                return id;
            }

            @Override
            public String getAir() {
                return "minecraft:air";
            }
        };

        CisNetworkDecoder<String, String, String, String> decoder = new CisNetworkDecoder<>(
                strictRegistry, packer, stateAdapter, nbtAdapter, "minecraft:air");

        ChunkDelta<String, String> decoded = decoder.decode(encoded);

        // Verify that the unknown block was replaced with air
        // With the !stateAdapter.isAir(state) check in sparse decoding, air blocks are
        // now skipped
        // So the delta should be empty
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

        assertEquals(0, count[0], "Unknown block fallback to air should be skipped");
    }

    @Test
    void testDecodeDenseSection() throws IOException {
        // Setup adapters
        TestBlockRegistry registry = new TestBlockRegistry();
        TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        PropertyPacker<String, String, String> packer = new PropertyPacker<>(stateAdapter);
        TestNbtAdapter nbtAdapter = new TestNbtAdapter();

        CisNetworkDecoder<String, String, String, String> decoder = new CisNetworkDecoder<>(
                registry, packer, stateAdapter, nbtAdapter, "minecraft:air");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        // 1. Header
        dos.writeInt(CisConstants.MAGIC);
        dos.writeInt(CisConstants.VERSION);

        // 2. Global Palette
        // 0: Air, 1: Stone
        dos.writeInt(2);

        ByteArrayOutputStream paletteBuffer = new ByteArrayOutputStream();
        try (DataOutputStream paletteDos = new DataOutputStream(paletteBuffer)) {
            paletteDos.writeUTF("minecraft:air");
            paletteDos.writeUTF("minecraft:stone");
        }
        dos.write(paletteBuffer.toByteArray());

        // Properties (empty)
        dos.writeInt(0);

        // 3. Section Data
        dos.writeShort(1); // 1 Section

        BitUtils.BitWriter writer = new BitUtils.BitWriter(8192);
        // Section Y = 0
        writer.writeZigZag(0, CisConstants.SECTION_Y_BITS);
        // Mode = DENSE
        writer.write(CisConstants.SECTION_ENCODING_DENSE, 1);

        // Local Palette Size = 2 (Air, Stone)
        writer.write(2, 8);

        // Local Palette Entries (Global Indices)
        // Global 0 -> Local 0 (Air)
        // Global 1 -> Local 1 (Stone)
        int globalBits = 1; // ceil(log2(2)) = 1
        writer.write(0, globalBits);
        writer.write(1, globalBits);

        // Block Data
        // 4096 blocks. Bits per block = ceil(log2(2)) = 1.
        // We set (0,0,0) to Stone (Local 1), rest Air (Local 0).
        // YZX order. (0,0,0) is first.
        writer.write(1, 1); // (0,0,0) -> Stone
        for (int i = 1; i < 4096; i++) {
            writer.write(0, 1); // Rest -> Air
        }
        writer.flush(); // Ensure last byte is written

        byte[] sectionData = writer.toByteArray();
        dos.writeInt(sectionData.length);
        dos.write(sectionData);

        // 4. BE/Entities (0)
        dos.writeInt(0);
        dos.writeInt(0);

        byte[] payload = baos.toByteArray();

        // Decode
        ChunkDelta<String, String> decoded = decoder.decode(payload);

        // Verify
        // We expect (0,0,0) to be Stone.
        // All others Air (so not in delta).

        boolean[] found = new boolean[1];
        decoded.accept(new ChunkDelta.DeltaVisitor<>() {
            @Override
            public void visitBlock(int x, int y, int z, String state) {
                if (x == 0 && y == 0 && z == 0) {
                    assertEquals("minecraft:stone", state);
                    found[0] = true;
                } else {
                    fail("Unexpected block at " + x + "," + y + "," + z + ": " + state);
                }
            }

            @Override
            public void visitBlockEntity(int x, int y, int z, String nbt) {
            }

            @Override
            public void visitEntity(String nbt) {
            }
        });

        assertTrue(found[0], "Should have found the dense block");
    }

    @Test
    void testDecodeTruncatedData() throws IOException {
        // Setup adapters
        TestBlockRegistry registry = new TestBlockRegistry();
        TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        PropertyPacker<String, String, String> packer = new PropertyPacker<>(stateAdapter);
        TestNbtAdapter nbtAdapter = new TestNbtAdapter();

        CisNetworkEncoder<String, String, String, String> encoder = new CisNetworkEncoder<>(
                registry, packer, stateAdapter, nbtAdapter, "minecraft:air");
        CisNetworkDecoder<String, String, String, String> decoder = new CisNetworkDecoder<>(
                registry, packer, stateAdapter, nbtAdapter, "minecraft:air");

        ChunkDelta<String, String> delta = new ChunkDelta<>(s -> s.equals("minecraft:air"));
        delta.addBlockChange(0, 0, 0, "minecraft:stone");
        byte[] encoded = encoder.encode(delta);

        // 1. Truncated Header (< 8 bytes)
        byte[] truncatedHeader = new byte[7];
        System.arraycopy(encoded, 0, truncatedHeader, 0, 7);
        IOException e1 = assertThrows(IOException.class, () -> decoder.decode(truncatedHeader));
        boolean hitSectionHeaderTruncation = false;
        for (int i = 1; i < 50; i++) {
            byte[] trunc = new byte[encoded.length - i];
            System.arraycopy(encoded, 0, trunc, 0, trunc.length);
            try {
                decoder.decode(trunc);
            } catch (IOException e) {
                if (e.getMessage().contains("cannot read section header")) {
                    hitSectionHeaderTruncation = true;
                    break;
                }
            }
        }
        assertTrue(hitSectionHeaderTruncation, "Should have triggered 'cannot read section header' check");

        // 3. Truncated Section Data
        // Manually construct a payload where declared section length > actual data
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        dos.writeInt(CisConstants.MAGIC);
        dos.writeInt(CisConstants.VERSION);

        // Global Palette (Air only)
        dos.writeInt(1);
        dos.writeUTF("minecraft:air");
        dos.writeInt(0); // Properties

        // Section Headers
        dos.writeShort(1); // 1 Section
        dos.writeInt(100); // Claim 100 bytes of section data

        dos.write(new byte[50]); // Only write 50 bytes

        byte[] malformedPayload = baos.toByteArray();

        IOException e3 = assertThrows(IOException.class, () -> decoder.decode(malformedPayload));
        assertTrue(e3.getMessage().contains("Truncated data") && e3.getMessage().contains("expected 100 bytes"),
                "Message was: " + e3.getMessage());
    }

    @Test
    void testDenseSectionNullState() throws IOException {
        TestBlockRegistry registry = new TestBlockRegistry();
        TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        PropertyPacker<String, String, String> packer = new PropertyPacker<>(stateAdapter);
        TestNbtAdapter nbtAdapter = new TestNbtAdapter();

        // Pass null as airState - this makes getStateFromPalette return null for OOB
        // indices
        CisNetworkDecoder<String, String, String, String> decoder = new CisNetworkDecoder<>(
                registry, packer, stateAdapter, nbtAdapter, null);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        dos.writeInt(CisConstants.MAGIC);
        dos.writeInt(CisConstants.VERSION);

        // Global Palette: 3 entries to make globalBits = 2 (can represent 0,1,2,3)
        dos.writeInt(3);
        dos.writeUTF("minecraft:stone");
        dos.writeUTF("minecraft:dirt");
        dos.writeUTF("minecraft:cobblestone");
        dos.writeInt(0); // Property data length (no properties)

        // Section Data
        dos.writeShort(1);
        BitUtils.BitWriter writer = new BitUtils.BitWriter(8192);
        writer.writeZigZag(0, CisConstants.SECTION_Y_BITS);
        writer.write(CisConstants.SECTION_ENCODING_DENSE, 1);

        // Local Palette Size = 2 (bitsPerBlock = 1)
        writer.write(2, 8);
        // Local 0 -> Global 0 (Stone)
        writer.write(0, 2);
        // Local 1 -> Global 3 (OOB - returns null since airState is null)
        writer.write(3, 2);

        // Block Data: bitsPerBlock = 1
        writer.write(0, 1); // Block 0: Stone
        writer.write(1, 1); // Block 1: null (skipped)
        for (int i = 2; i < 4096; i++) {
            writer.write(0, 1);
        }
        writer.flush();

        byte[] sectionData = writer.toByteArray();
        dos.writeInt(sectionData.length);
        dos.write(sectionData);

        dos.writeInt(0);
        dos.writeInt(0);

        ChunkDelta<String, String> decoded = decoder.decode(baos.toByteArray());

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
        assertEquals(4095, count[0], "One block should be skipped due to null state");
    }

    @Test
    void testDenseSectionIndexOutOfBounds() throws IOException {
        TestBlockRegistry registry = new TestBlockRegistry();
        TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        PropertyPacker<String, String, String> packer = new PropertyPacker<>(stateAdapter);
        TestNbtAdapter nbtAdapter = new TestNbtAdapter();

        CisNetworkDecoder<String, String, String, String> decoder = new CisNetworkDecoder<>(
                registry, packer, stateAdapter, nbtAdapter, "minecraft:air");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        dos.writeInt(CisConstants.MAGIC);
        dos.writeInt(CisConstants.VERSION);

        // Global Palette: 2 entries so globalBits = 1 (needed for proper bit alignment)
        dos.writeInt(2);
        dos.writeUTF("minecraft:air");
        dos.writeUTF("minecraft:stone");
        dos.writeInt(0); // Properties

        // Section Data
        dos.writeShort(1);
        BitUtils.BitWriter writer = new BitUtils.BitWriter(8192);
        writer.writeZigZag(0, CisConstants.SECTION_Y_BITS);
        writer.write(CisConstants.SECTION_ENCODING_DENSE, 1);

        // Local Palette Size = 3
        writer.write(3, 8);

        // Local Palette Entries (globalBits = 1)
        writer.write(0, 1); // Local 0 -> Global 0 (Air)
        writer.write(1, 1); // Local 1 -> Global 1 (Stone)
        writer.write(0, 1); // Local 2 -> Global 0 (Air)

        // Block Data: bitsPerBlock = calculateBitsNeeded(3) = 2 (can encode 0,1,2,3)
        // Write localIndex=3 which is >= localSize(3), triggers reset to 0 -> Air ->
        // skipped
        writer.write(3, 2); // Block 0: OOB index 3 -> reset to 0 -> Air (skipped)
        // Write some Stone blocks to verify the test works
        writer.write(1, 2); // Block 1: local 1 -> Stone (added)
        // Fill rest with 0 (Air, skipped)
        for (int i = 2; i < 4096; i++) {
            writer.write(0, 2);
        }
        writer.flush();

        byte[] sectionData = writer.toByteArray();
        dos.writeInt(sectionData.length);
        dos.write(sectionData);

        dos.writeInt(0); // BE
        dos.writeInt(0); // Entities

        ChunkDelta<String, String> decoded = decoder.decode(baos.toByteArray());

        // Verify (0,0,0) is Air (because index 3 reset to index 0 which maps to Air)
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
        assertEquals(1, count[0], "Only Stone block should be added (OOB index -> Air skipped)");
    }

    @Test
    void testDenseSectionOneEntryPalette() throws IOException {
        TestBlockRegistry registry = new TestBlockRegistry();
        TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        PropertyPacker<String, String, String> packer = new PropertyPacker<>(stateAdapter);
        TestNbtAdapter nbtAdapter = new TestNbtAdapter();

        CisNetworkDecoder<String, String, String, String> decoder = new CisNetworkDecoder<>(
                registry, packer, stateAdapter, nbtAdapter, "minecraft:air");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        dos.writeInt(CisConstants.MAGIC);
        dos.writeInt(CisConstants.VERSION);

        // Global Palette: 1 entry (Stone)
        dos.writeInt(1);
        dos.writeUTF("minecraft:stone");
        dos.writeInt(0);

        // Section Data
        dos.writeShort(1);
        BitUtils.BitWriter writer = new BitUtils.BitWriter(8192);
        writer.writeZigZag(0, CisConstants.SECTION_Y_BITS);
        writer.write(CisConstants.SECTION_ENCODING_DENSE, 1);

        // Local Palette Size = 1
        writer.write(1, 8);
        // Local Entry 0 -> Global 0
        writer.write(0, 1);

        // Block Data: bitsPerBlock = 0. Write nothing.
        writer.flush();

        byte[] sectionData = writer.toByteArray();
        dos.writeInt(sectionData.length);
        dos.write(sectionData);

        dos.writeInt(0);
        dos.writeInt(0);

        ChunkDelta<String, String> decoded = decoder.decode(baos.toByteArray());

        // Verify all 4096 blocks are Stone
        int[] count = new int[1];
        decoded.accept(new ChunkDelta.DeltaVisitor<>() {
            @Override
            public void visitBlock(int x, int y, int z, String state) {
                if ("minecraft:stone".equals(state))
                    count[0]++;
            }

            @Override
            public void visitBlockEntity(int x, int y, int z, String nbt) {
            }

            @Override
            public void visitEntity(String nbt) {
            }
        });
        assertEquals(4096, count[0], "Should be full of Stone");
    }

    @Test
    void testDenseSectionExplicitAir() throws IOException {
        // This test covers the `isAir(state) == true` branch in decodeDenseSection
        TestBlockRegistry registry = new TestBlockRegistry();
        TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        PropertyPacker<String, String, String> packer = new PropertyPacker<>(stateAdapter);
        TestNbtAdapter nbtAdapter = new TestNbtAdapter();

        CisNetworkDecoder<String, String, String, String> decoder = new CisNetworkDecoder<>(
                registry, packer, stateAdapter, nbtAdapter, "minecraft:air");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        dos.writeInt(CisConstants.MAGIC);
        dos.writeInt(CisConstants.VERSION);

        // Global Palette: 2 entries (Stone, Air)
        dos.writeInt(2);
        dos.writeUTF("minecraft:stone");
        dos.writeUTF("minecraft:air");
        dos.writeInt(0); // No properties

        // Section Data
        dos.writeShort(1);
        BitUtils.BitWriter writer = new BitUtils.BitWriter(8192);
        writer.writeZigZag(0, CisConstants.SECTION_Y_BITS);
        writer.write(CisConstants.SECTION_ENCODING_DENSE, 1);

        // Local Palette Size = 2 (bitsPerBlock = 1)
        writer.write(2, 8);
        // Local 0 -> Global 0 (Stone)
        writer.write(0, 1);
        // Local 1 -> Global 1 (Air)
        writer.write(1, 1);

        // Block Data: bitsPerBlock = 1
        // First block = Stone (local 0), second block = Air (local 1), rest = Stone
        writer.write(0, 1); // Block 0: Stone (added)
        writer.write(1, 1); // Block 1: Air (skipped - covers isAir==true branch)
        for (int i = 2; i < 4096; i++) {
            writer.write(0, 1); // Stone
        }
        writer.flush();

        byte[] sectionData = writer.toByteArray();
        dos.writeInt(sectionData.length);
        dos.write(sectionData);

        dos.writeInt(0);
        dos.writeInt(0);

        ChunkDelta<String, String> decoded = decoder.decode(baos.toByteArray());

        // Should have 4095 blocks (air block at (1,0,0) is skipped)
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
        assertEquals(4095, count[0], "Air block should be skipped");
    }

    @Test
    void testSparseSectionExplicitAir() throws IOException {
        TestBlockRegistry registry = new TestBlockRegistry();
        TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        PropertyPacker<String, String, String> packer = new PropertyPacker<>(stateAdapter);
        TestNbtAdapter nbtAdapter = new TestNbtAdapter();

        CisNetworkDecoder<String, String, String, String> decoder = new CisNetworkDecoder<>(
                registry, packer, stateAdapter, nbtAdapter, "minecraft:air");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        dos.writeInt(CisConstants.MAGIC);
        dos.writeInt(CisConstants.VERSION);

        // Global Palette: Stone, Air
        dos.writeInt(2);
        ByteArrayOutputStream pBuf = new ByteArrayOutputStream();
        try (DataOutputStream pDos = new DataOutputStream(pBuf)) {
            pDos.writeUTF("minecraft:stone");
            pDos.writeUTF("minecraft:air");
        }
        dos.write(pBuf.toByteArray());

        dos.writeInt(0);

        // Section
        dos.writeShort(1);
        BitUtils.BitWriter writer = new BitUtils.BitWriter(1024);
        writer.writeZigZag(0, CisConstants.SECTION_Y_BITS);
        writer.write(CisConstants.SECTION_ENCODING_SPARSE, 1);

        writer.write(1, CisConstants.BLOCK_COUNT_BITS); // 1 block
        writer.write(0, 12); // Pos (0,0,0)

        // Global Idx 1 ("minecraft:air")
        // Global size 2 -> 1 bit.
        writer.write(1, 1);

        writer.flush();
        byte[] sectionData = writer.toByteArray();
        dos.writeInt(sectionData.length);
        dos.write(sectionData);
        dos.writeInt(0);
        dos.writeInt(0);

        ChunkDelta<String, String> decoded = decoder.decode(baos.toByteArray());

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
        assertEquals(0, count[0], "Explicit 'minecraft:air' should be skipped by !isAir check");
    }

    @Test
    void testSparseSectionNullState() throws IOException {
        TestBlockRegistry registry = new TestBlockRegistry();
        TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        PropertyPacker<String, String, String> packer = new PropertyPacker<>(stateAdapter);
        TestNbtAdapter nbtAdapter = new TestNbtAdapter();

        // Pass null as airState - makes getStateFromPalette return null for OOB indices
        CisNetworkDecoder<String, String, String, String> decoder = new CisNetworkDecoder<>(
                registry, packer, stateAdapter, nbtAdapter, null);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        dos.writeInt(CisConstants.MAGIC);
        dos.writeInt(CisConstants.VERSION);

        // Global Palette: 3 entries to get globalBits = 2 (can write OOB index 3)
        dos.writeInt(3);
        dos.writeUTF("minecraft:stone");
        dos.writeUTF("minecraft:dirt");
        dos.writeUTF("minecraft:cobblestone");
        dos.writeInt(0); // No properties

        // Section (sparse)
        dos.writeShort(1);
        BitUtils.BitWriter writer = new BitUtils.BitWriter(1024);
        writer.writeZigZag(0, CisConstants.SECTION_Y_BITS);
        writer.write(CisConstants.SECTION_ENCODING_SPARSE, 1);

        // 2 blocks: one valid (Stone), one OOB (null)
        writer.write(2, CisConstants.BLOCK_COUNT_BITS);

        // Block 0: position (0,0,0), global index 0 (Stone)
        writer.write(0, 12); // Pos
        writer.write(0, 2); // Global index 0 (Stone)

        // Block 1: position (1,0,0), global index 3 (OOB -> null)
        writer.write(1, 12); // Pos = x=1, z=0, y=0
        writer.write(3, 2); // Global index 3 (OOB, returns null since airState is null)

        writer.flush();
        byte[] sectionData = writer.toByteArray();
        dos.writeInt(sectionData.length);
        dos.write(sectionData);
        dos.writeInt(0);
        dos.writeInt(0);

        ChunkDelta<String, String> decoded = decoder.decode(baos.toByteArray());

        // Only 1 block should be added (Stone), the null one is skipped
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
        assertEquals(1, count[0], "Only Stone block should be added, null block skipped");
    }

    @Test
    void testBlockEntityDecodeFailure() throws IOException {
        TestBlockRegistry registry = new TestBlockRegistry();
        TestBlockStateAdapter stateAdapter = new TestBlockStateAdapter();
        PropertyPacker<String, String, String> packer = new PropertyPacker<>(stateAdapter);

        // Use a custom NBT adapter that throws IOException on read
        NbtAdapter<String> failingNbtAdapter = new NbtAdapter<String>() {
            @Override
            public void write(String nbt, DataOutput output) throws IOException {
                output.writeUTF(nbt);
            }

            @Override
            public String read(DataInput input) throws IOException {
                throw new IOException("Simulated NBT decode failure");
            }
        };

        CisNetworkDecoder<String, String, String, String> decoder = new CisNetworkDecoder<>(
                registry, packer, stateAdapter, failingNbtAdapter, "minecraft:air");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        dos.writeInt(CisConstants.MAGIC);
        dos.writeInt(CisConstants.VERSION);

        // Global Palette: 1 entry
        dos.writeInt(1);
        dos.writeUTF("minecraft:stone");
        dos.writeInt(0);

        // Empty sections
        dos.writeShort(0);
        dos.writeInt(0);

        // 1 Block Entity with malformed NBT
        dos.writeInt(1);
        dos.writeInt(0); // position (0,0,0)
        dos.writeUTF("fake_nbt_that_will_fail");

        dos.writeInt(0); // Entities

        // The IOException will be caught, logged, and swallowed
        // So just verify the decode completes and check the warning was logged
        ChunkDelta<String, String> decoded = decoder.decode(baos.toByteArray());

        // Decode should complete but with no block entities (since decoding failed)
        int[] beCount = new int[1];
        decoded.accept(new ChunkDelta.DeltaVisitor<>() {
            @Override
            public void visitBlock(int x, int y, int z, String state) {
            }

            @Override
            public void visitBlockEntity(int x, int y, int z, String nbt) {
                beCount[0]++;
            }

            @Override
            public void visitEntity(String nbt) {
            }
        });
        assertEquals(0, beCount[0], "Block entity should not be decoded due to IOException");
    }

    // Manual Stubs/Mocks (Reused from CisNetworkEncoderTest)

    static class TestBlockRegistry implements BlockRegistryAdapter<String> {
        @Override
        public String getId(String block) {
            return block;
        }

        @Override
        public String getBlock(String id) {
            return id;
        }

        @Override
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
        public String read(DataInput input) throws IOException {
            return input.readUTF();
        }
    }
}
