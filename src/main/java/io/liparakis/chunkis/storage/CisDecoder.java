package io.liparakis.chunkis.storage;

import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.Palette;
import io.liparakis.chunkis.storage.BitUtils.BitReader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * V5 Decoder for Chunkis format.
 * Decoding Paletted Sections and dynamic property packing.
 */
public class CisDecoder {

    // Reusable objects to prevent allocation
    private final BitReader propertyReader = new BitReader(new byte[0]);
    private final BitReader sectionReader = new BitReader(new byte[0]);
    private final int[] localPaletteBuffer = new int[4096]; // Max palette size buffer (reused)

    public CisDecoder() {
    }

    public ChunkDelta decode(byte[] data, CisMapping mapping) throws IOException {
        int offset = 0;

        // 1. Header Checks
        if (data.length < 8)
            throw new IOException("CIS file too short");

        int magic = readIntBE(data, offset);
        offset += 4;
        if (magic != CisConstants.MAGIC) {
            throw new IOException("Invalid CIS4 Magic");
        }

        int version = readIntBE(data, offset);
        offset += 4;
        if (version != CisConstants.VERSION) {
            throw new IOException("Unsupported CIS version: " + version + " (Expected " + CisConstants.VERSION + ")");
        }

        ChunkDelta delta = new ChunkDelta();
        Palette<BlockState> palette = delta.getBlockPalette();

        // 2. Global Palette
        int globalPaletteSize = readIntBE(data, offset);
        offset += 4;

        int[] blockIds = new int[globalPaletteSize];
        for (int i = 0; i < globalPaletteSize; i++) {
            blockIds[i] = readShortBE(data, offset) & 0xFFFF;
            offset += 2;
        }

        int propLength = readIntBE(data, offset);
        offset += 4;

        // Create zero-copy view for property bits if possible, or copy
        byte[] propData = new byte[propLength];
        System.arraycopy(data, offset, propData, 0, propLength);
        offset += propLength;

        propertyReader.setData(propData);

        // Reconstruct Global Palette
        List<BlockState> globalPaletteList = new ArrayList<>(globalPaletteSize);
        for (int i = 0; i < globalPaletteSize; i++) {
            int blockId = blockIds[i];
            BlockState state = mapping.readStateProperties(propertyReader, blockId);
            globalPaletteList.add(state);
            palette.getOrAdd(state);
        }

        // 3. Sections
        int sectionCount = readShortBE(data, offset) & 0xFFFF;
        offset += 2;

        int sectionDataLength = readIntBE(data, offset);
        offset += 4;

        byte[] sectionData = new byte[sectionDataLength];
        System.arraycopy(data, offset, sectionData, 0, sectionDataLength);
        offset += sectionDataLength;

        sectionReader.setData(sectionData);

        for (int i = 0; i < sectionCount; i++) {
            decodeSection(sectionReader, delta, globalPaletteList);
        }

        // 4. Block Entities & 5. Global Entities
        if (offset < data.length) {
            try (ByteArrayInputStream bais = new ByteArrayInputStream(data, offset, data.length - offset);
                    DataInputStream dis = new DataInputStream(bais)) {

                // --- Block Entities ---
                int beCount = dis.readInt();
                for (int i = 0; i < beCount; i++) {
                    byte x = dis.readByte();
                    int y = dis.readInt();
                    byte z = dis.readByte();
                    NbtCompound nbt = NbtIo.readCompound(dis);
                    delta.addBlockEntityData(x, y, z, nbt);
                }

                // --- Global Entities ---
                try {
                    int entityCount = dis.readInt();
                    List<NbtCompound> entities = new java.util.ArrayList<>(entityCount);
                    for (int i = 0; i < entityCount; i++) {
                        entities.add(net.minecraft.nbt.NbtIo.readCompound(dis));
                    }
                    delta.setEntities(entities, false);
                } catch (java.io.EOFException e) {
                    // End of stream okay
                }
            } catch (IOException e) {
                // Should not happen with ByteArrayInputStream
            }
        }

        delta.markSaved();
        return delta;
    }

    private void decodeSection(BitReader reader, ChunkDelta delta, List<BlockState> globalPalette) {
        // Section Y (ZigZag 8 bits)
        int sectionY = reader.readZigZag(8);

        // Local Palette Size (8 bits)
        int localSize = (int) reader.read(8);

        if (localSize == 0) {
            return; // Empty section
        }

        // Local Palette Entries
        // globalBits calculation: ceil(log2(globalPaletteSize))
        int globalBits = Math.max(1,
                32 - Integer.numberOfLeadingZeros(Math.max(1, globalPalette.size() - 1)));

        // Read Local mapping: LocalIndex -> GlobalIndex
        // Reuse buffer to store global indices
        for (int i = 0; i < localSize; i++) {
            localPaletteBuffer[i] = (int) reader.read(globalBits);
        }

        // Bits per block: ceil(log2(localSize))
        int bitsPerBlock = (localSize > 1) ? Math.max(1, 32 - Integer.numberOfLeadingZeros(localSize - 1)) : 0;

        // Read 4096 blocks
        // Loop order: Y, Z, X (matches Encoder)
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int localIndex = 0;
                    if (bitsPerBlock > 0) {
                        localIndex = (int) reader.read(bitsPerBlock);
                    }

                    if (localIndex < 0 || localIndex >= localSize) {
                        // Corruption or bug
                        localIndex = 0;
                    }

                    int globalIndex = localPaletteBuffer[localIndex];
                    BlockState state = (globalIndex >= 0 && globalIndex < globalPalette.size())
                            ? globalPalette.get(globalIndex)
                            : Blocks.AIR.getDefaultState();

                    // Skip air to save memory in Delta? Or usually delta needs to know about
                    // changes.
                    // Encoder only encodes if state != null and !isAir (mostly),
                    // but here we are decoding the Full Section state effectively.
                    // ChunkDelta.addBlockChange expects absolute coords.
                    if (!state.isAir()) {
                        int absY = (sectionY << 4) + y;
                        delta.addBlockChange((byte) x, absY, (byte) z, state);
                    }
                }
            }
        }
    }

    // Manual Big-Endian integer reader
    private static int readIntBE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) |
                ((b[off + 1] & 0xFF) << 16) |
                ((b[off + 2] & 0xFF) << 8) |
                (b[off + 3] & 0xFF);
    }

    // Manual Big-Endian short reader
    private static short readShortBE(byte[] b, int off) {
        return (short) (((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF));
    }
}