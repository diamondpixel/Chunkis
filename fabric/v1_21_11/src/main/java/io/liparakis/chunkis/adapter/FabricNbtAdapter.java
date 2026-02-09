package io.liparakis.chunkis.adapter;

import io.liparakis.chunkis.spi.NbtAdapter;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public class FabricNbtAdapter implements NbtAdapter<NbtCompound> {

    @Override
    public void write(NbtCompound nbt, DataOutput output) throws IOException {
        // Capture compressed NBT to a byte array to ensure stream isolation
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        NbtIo.writeCompressed(nbt, baos);
        byte[] data = baos.toByteArray();

        // Write length-prefixed data
        output.writeInt(data.length);
        output.write(data);
    }

    @Override
    public NbtCompound read(DataInput input) throws IOException {
        // Read length-prefixed data
        int len = input.readInt();
        if (len < 0)
            throw new IOException("Invalid NBT data length: " + len);
        if (len > 2 * 1024 * 1024)
            throw new IOException("NBT data too large: " + len + " bytes"); // Safety cap 2MB

        byte[] data = new byte[len];
        input.readFully(data);

        // Read from isolated stream
        try (java.io.InputStream bais = new java.io.ByteArrayInputStream(data)) {
            return NbtIo.readCompressed(bais, NbtSizeTracker.ofUnlimitedBytes());
        }
    }
}
