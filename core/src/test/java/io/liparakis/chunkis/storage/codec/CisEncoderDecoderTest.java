package io.liparakis.chunkis.storage.codec;

import io.liparakis.chunkis.core.BlockInstruction;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.Palette;
import io.liparakis.chunkis.spi.BlockStateAdapter;
import io.liparakis.chunkis.spi.NbtAdapter;
import io.liparakis.chunkis.storage.BitUtils.BitReader;
import io.liparakis.chunkis.storage.BitUtils.BitWriter;
import io.liparakis.chunkis.storage.CisAdapter;
import io.liparakis.chunkis.storage.PropertyPacker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link CisEncoder} and {@link CisDecoder} ensuring round-trip
 * correctness.
 * Uses a simplified mock state type (Integer) for testing.
 */
@ExtendWith(MockitoExtension.class)
class CisEncoderDecoderTest {

        // State type = Integer, NBT type = byte[] (simple representation)
        @Mock
        private CisAdapter<Integer> mockCisAdapter;
        @Mock
        private BlockStateAdapter<String, Integer, String> mockStateAdapter;
        @Mock
        private NbtAdapter<byte[]> mockNbtAdapter;

        private static final Integer AIR_STATE = 0;
        private static final Integer STONE_STATE = 1;
        private static final Integer DIRT_STATE = 2;

        @BeforeEach
        void setUp() throws IOException {
                // Air is always ID 0
                lenient().when(mockCisAdapter.getBlockId(AIR_STATE)).thenReturn(0);
                lenient().when(mockCisAdapter.getBlockId(STONE_STATE)).thenReturn(1);
                lenient().when(mockCisAdapter.getBlockId(DIRT_STATE)).thenReturn(2);

                // Air check
                lenient().when(mockStateAdapter.isAir(AIR_STATE)).thenReturn(true);
                lenient().when(mockStateAdapter.isAir(STONE_STATE)).thenReturn(false);
                lenient().when(mockStateAdapter.isAir(DIRT_STATE)).thenReturn(false);

                // Property writing/reading does nothing for these simple states
                lenient().doAnswer(inv -> null).when(mockCisAdapter).writeStateProperties(any(BitWriter.class),
                                anyInt());
                lenient().when(mockCisAdapter.readStateProperties(any(BitReader.class), eq(0))).thenReturn(AIR_STATE);
                lenient().when(mockCisAdapter.readStateProperties(any(BitReader.class), eq(1))).thenReturn(STONE_STATE);
                lenient().when(mockCisAdapter.readStateProperties(any(BitReader.class), eq(2))).thenReturn(DIRT_STATE);
        }

        @Test
        void encodeDecodeEmptyDelta() throws IOException {
                ChunkDelta<Integer, byte[]> original = new ChunkDelta<>();

                CisEncoder<Integer, byte[]> encoder = new CisEncoder<>(mockCisAdapter, mockStateAdapter, mockNbtAdapter,
                                AIR_STATE);
                byte[] encoded = encoder.encode(original);

                // Should produce minimal header + empty palette + empty sections
                assertThat(encoded).isNotNull();
                assertThat(encoded.length).isGreaterThan(8); // At least header

                CisDecoder<Integer, byte[]> decoder = new CisDecoder<>(mockCisAdapter, mockStateAdapter, mockNbtAdapter,
                                AIR_STATE);
                ChunkDelta<Integer, byte[]> decoded = decoder.decode(encoded);

                assertThat(decoded.isEmpty()).isTrue();
        }

        @Test
        void encodeDecodeSimpleBlockChanges() throws IOException {
                ChunkDelta<Integer, byte[]> original = new ChunkDelta<>();
                original.addBlockChange(0, 64, 0, STONE_STATE);
                original.addBlockChange(1, 64, 0, DIRT_STATE);
                original.addBlockChange(15, 0, 15, STONE_STATE);

                CisEncoder<Integer, byte[]> encoder = new CisEncoder<>(mockCisAdapter, mockStateAdapter, mockNbtAdapter,
                                AIR_STATE);
                byte[] encoded = encoder.encode(original);

                CisDecoder<Integer, byte[]> decoder = new CisDecoder<>(mockCisAdapter, mockStateAdapter, mockNbtAdapter,
                                AIR_STATE);
                ChunkDelta<Integer, byte[]> decoded = decoder.decode(encoded);

                // Verify block count
                List<BlockInstruction> instructions = decoded.getBlockInstructions();
                assertThat(instructions).hasSize(3);

                // Verify palette contains our blocks
                Palette<Integer> palette = decoded.getBlockPalette();
                assertThat(palette.getAll().size()).isGreaterThanOrEqualTo(2); // At least STONE and DIRT
        }

        @Test
        void encodeDecodeWithMultipleSections() throws IOException {
                ChunkDelta<Integer, byte[]> original = new ChunkDelta<>();

                // Add blocks at different Y levels (different sections)
                original.addBlockChange(0, 0, 0, STONE_STATE); // Section Y=0
                original.addBlockChange(0, 16, 0, DIRT_STATE); // Section Y=1
                original.addBlockChange(0, 64, 0, STONE_STATE); // Section Y=4
                original.addBlockChange(0, -16, 0, DIRT_STATE); // Section Y=-1

                CisEncoder<Integer, byte[]> encoder = new CisEncoder<>(mockCisAdapter, mockStateAdapter, mockNbtAdapter,
                                AIR_STATE);
                byte[] encoded = encoder.encode(original);

                CisDecoder<Integer, byte[]> decoder = new CisDecoder<>(mockCisAdapter, mockStateAdapter, mockNbtAdapter,
                                AIR_STATE);
                ChunkDelta<Integer, byte[]> decoded = decoder.decode(encoded);

                assertThat(decoded.getBlockInstructions()).hasSize(4);
        }

        @Test
        void encodeDecodeWithBlockEntities() throws IOException {
                // Setup NBT adapter to write/read length-prefixed data
                doAnswer(inv -> {
                        byte[] nbt = inv.getArgument(0);
                        DataOutput out = inv.getArgument(1);
                        out.writeInt(nbt.length);
                        out.write(nbt);
                        return null;
                }).when(mockNbtAdapter).write(any(byte[].class), any(DataOutput.class));

                when(mockNbtAdapter.read(any(DataInput.class))).thenAnswer(inv -> {
                        DataInput in = inv.getArgument(0);
                        int len = in.readInt();
                        byte[] data = new byte[len];
                        in.readFully(data);
                        return data;
                });

                ChunkDelta<Integer, byte[]> original = new ChunkDelta<>();
                original.addBlockChange(5, 70, 5, STONE_STATE);
                original.addBlockEntityData(5, 70, 5, new byte[] { 1, 2, 3, 4, 5 });

                CisEncoder<Integer, byte[]> encoder = new CisEncoder<>(mockCisAdapter, mockStateAdapter, mockNbtAdapter,
                                AIR_STATE);
                byte[] encoded = encoder.encode(original);

                CisDecoder<Integer, byte[]> decoder = new CisDecoder<>(mockCisAdapter, mockStateAdapter, mockNbtAdapter,
                                AIR_STATE);
                ChunkDelta<Integer, byte[]> decoded = decoder.decode(encoded);

                assertThat(decoded.getBlockInstructions()).hasSize(1);
                assertThat(decoded.getBlockEntities()).hasSize(1);

                // Verify NBT content
                byte[] decodedNbt = decoded.getBlockEntities().values().iterator().next();
                assertThat(decodedNbt).containsExactly(1, 2, 3, 4, 5);
        }

        @Test
        void headerVersionValidation() throws IOException {
                // Create valid encoded data
                ChunkDelta<Integer, byte[]> original = new ChunkDelta<>();
                original.addBlockChange(0, 0, 0, STONE_STATE);

                CisEncoder<Integer, byte[]> encoder = new CisEncoder<>(mockCisAdapter, mockStateAdapter, mockNbtAdapter,
                                AIR_STATE);
                byte[] encoded = encoder.encode(original);

                // Corrupt the version number (bytes 4-7)
                encoded[4] = (byte) 0xFF;
                encoded[5] = (byte) 0xFF;
                encoded[6] = (byte) 0xFF;
                encoded[7] = (byte) 0xFF;

                CisDecoder<Integer, byte[]> decoder = new CisDecoder<>(mockCisAdapter, mockStateAdapter, mockNbtAdapter,
                                AIR_STATE);

                org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> decoder.decode(encoded));
        }
}
