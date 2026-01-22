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
 * High-performance decoder for the Chunkis format.
 * Refactored to eliminate stream overhead and reduce GC pressure during chunk
 * loading.
 */
public class CisDecoder {

    // Reusable objects to prevent allocation per-decode or per-microcube.
    // Assuming single-threaded usage per instance or synchronized external access.
    // If used concurrently, these should be method-local or ThreadLocal.
    private final BitReader bitReader = new BitReader(new byte[0]);
    private final int[] paletteBuffer = new int[256]; // Max palette size buffer (reused)

    public CisDecoder() {
    }

    public ChunkDelta decode(byte[] data, CisMapping mapping) throws IOException {
        // Use a manual cursor instead of DataInputStream for the binary header.
        // DataInputStream is synchronized and allocates objects.
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
            throw new IOException("Unsupported CIS version: " + version);
        }

        ChunkDelta delta = new ChunkDelta();
        Palette<BlockState> palette = delta.getBlockPalette();

        // 2. Global Palette (Mapping IDs + Facing Data)
        int globalPaletteSize = readIntBE(data, offset);
        offset += 4;

        // Pre-allocate list to exact size to avoid resizing
        List<BlockState> globalPaletteList = new ArrayList<>(globalPaletteSize);

        for (int i = 0; i < globalPaletteSize; i++) {
            int mappingId = readShortBE(data, offset) & 0xFFFF;
            offset += 2;
            byte facingData = data[offset++];

            BlockState state = mapping.getBlockState(mappingId, facingData);

            globalPaletteList.add(state);
            palette.getOrAdd(state);
        }

        // 3. Instructions
        int instLength = readIntBE(data, offset);
        offset += 4;

        // Zero-copy: Use a sub-view logic or just pass offset/length if BitReader
        // supports it.
        // Current BitReader takes a byte array. For safety and API boundaries,
        // we might create a subarray here, OR refactor BitReader to support offsets.
        // For now, assuming the BitReader takes a raw array, let's just pass the whole
        // array
        // and add an offset capability to BitReader, or simple System.arraycopy for
        // safety.
        // Optimization: System.arraycopy is very fast intrinsic.
        byte[] instData = new byte[instLength];
        System.arraycopy(data, offset, instData, 0, instLength);
        offset += instLength;

        bitReader.setData(instData);
        decodeInstructions(bitReader, delta, globalPaletteList);

        // 4. Block Entities
        // Only wrap in DataInputStream for NBT reading if we actually have BEs.
        int beCount = readIntBE(data, offset);
        offset += 4;

        if (beCount > 0) {
            // Create the stream starting exactly where we are
            try (ByteArrayInputStream bais = new ByteArrayInputStream(data, offset, data.length - offset);
                    DataInputStream dis = new DataInputStream(bais)) {

                for (int i = 0; i < beCount; i++) {
                    byte x = dis.readByte();
                    int y = dis.readInt();
                    byte z = dis.readByte();
                    // NbtIo.readCompound handles the complexity of NBT parsing
                    NbtCompound nbt = NbtIo.readCompound(dis);
                    delta.addBlockEntityData(x, y, z, nbt);
                }
            }
        }

        delta.markSaved();
        return delta;
    }

    private void decodeInstructions(BitReader reader, ChunkDelta delta, List<BlockState> globalPalette) {
        // Read Section Count
        int sectionCount = (int) reader.read(CisConstants.SECTION_COUNT_BITS);

        for (int s = 0; s < sectionCount; s++) {
            int sectionY = reader.readZigZag(CisConstants.SECTION_Y_BITS);

            // Unrolled loop for 64 Micro-Cubes (4x4x4)
            // Flattening this removes loop overhead, though micro-cubes are data-driven
            // anyway.
            // Keeping nested loops for clarity as the JIT unrolls this effectively.
            for (int my = 0; my < 4; my++) {
                for (int mz = 0; mz < 4; mz++) {
                    for (int mx = 0; mx < 4; mx++) {
                        decodeMicroCube(reader, delta, sectionY, mx, my, mz, globalPalette);
                    }
                }
            }
        }
    }

    private void decodeMicroCube(BitReader reader, ChunkDelta delta, int sectionY, int mx, int my, int mz,
            List<BlockState> globalPalette) {
        // 1. Local Palette Header
        int paletteSize = (int) reader.read(CisConstants.PALETTE_SIZE_BITS);
        if (paletteSize == 0)
            return;

        // 2. Read Local IDs
        // Optimization: Reuse the class-level paletteBuffer instead of allocating `new
        // int[]`
        // We only need to clear or overwrite it.
        if (paletteSize > paletteBuffer.length) {
            // Should arguably never happen if PALETTE_SIZE_BITS constraints it,
            // but safety check:
            throw new RuntimeException("Palette size " + paletteSize + " exceeds buffer capacity");
        }

        int globalIdBits = CisConstants.GLOBAL_ID_BITS;
        for (int i = 0; i < paletteSize; i++) {
            paletteBuffer[i] = (int) reader.read(globalIdBits);
        }

        // bitsPerIndex calculation: standard ceil(log2(n))
        int bitsPerIndex = (paletteSize > 1) ? (32 - Integer.numberOfLeadingZeros(paletteSize - 1)) : 0;

        int lastLy = 0;
        int lastLxz = 0;
        int lastLid = 0;

        int blockCount = (int) reader.read(CisConstants.BLOCK_COUNT_BITS);

        for (int i = 0; i < blockCount; i++) {
            // Read Flags
            boolean newY = reader.readBool();
            int dy = newY ? reader.readZigZag(CisConstants.Y_DELTA_BITS) : 0;

            boolean newXZ = reader.readBool();
            int lxz = newXZ ? (int) reader.read(8) : lastLxz;

            int lid = lastLid;
            if (paletteSize > 1) {
                if (reader.readBool()) { // newID flag
                    lid = (int) reader.read(bitsPerIndex);
                }
            }

            // Calculation
            int ly = lastLy + dy;

            // Validate bounds to prevent logic errors silently corrupting data
            if (lid < 0 || lid >= paletteSize) {
                handleCorruption(mx, my, mz, sectionY, i, blockCount, lid, paletteSize);
            }

            int globalId = paletteBuffer[lid];

            // Resolve State
            // Unsafe get is slightly faster than check bounds if we trust input,
            // but for safety in mods, bounds check is cheap.
            BlockState state = (globalId >= 0 && globalId < globalPalette.size())
                    ? globalPalette.get(globalId)
                    : Blocks.AIR.getDefaultState();

            // Coordinate unpacking
            // lxz packs X in lower 4 bits, Z in upper 4 bits
            int absX = lxz & 15;
            int absY = (sectionY << 4) + ly;
            int absZ = (lxz >>> 4) & 15;

            delta.addBlockChange((byte) absX, absY, (byte) absZ, state);

            // Update context
            lastLy = ly;
            lastLxz = lxz;
            lastLid = lid;
        }
    }

    // --- Helpers ---

    private void handleCorruption(int mx, int my, int mz, int sectionY, int blockIndex, int count, int lid, int size) {
        String msg = String.format(
                "CIS Decode Error: LID %d out of bounds (size %d) at MicroCube [%d,%d,%d] SectionY=%d Block %d/%d",
                lid, size, mx, my, mz, sectionY, blockIndex, count);
        io.liparakis.chunkis.ChunkisMod.LOGGER.error(msg);
        throw new ArrayIndexOutOfBoundsException(msg);
    }

    // Manual Big-Endian integer reader (avoids DataInputStream allocation)
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