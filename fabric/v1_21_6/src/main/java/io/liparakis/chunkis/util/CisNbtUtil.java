package io.liparakis.chunkis.util;

import io.liparakis.chunkis.core.ChunkDelta;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.ChunkPos;

/**
 * Utility class for Chunkis NBT operations and constants.
 */
public final class CisNbtUtil {

    public static final String CHUNKIS_DATA_KEY = "ChunkisData";
    public static final String STATUS_EMPTY = "minecraft:empty";
    public static final String DATA_VERSION_KEY = "DataVersion";
    public static final String STATUS_KEY = "Status";
    public static final String X_POS_KEY = "xPos";
    public static final String Z_POS_KEY = "zPos";
    public static final String HAS_DELTA_KEY = "HasDelta";

    private CisNbtUtil() {
        // Utility class
    }

    /**
     * Creates a minimal NBT structure for a chunk.
     * Sets status to EMPTY to ensure terrain regeneration on load if needed.
     *
     * @param pos         The chunk position
     * @param dataVersion The game data version
     * @return A new NBT compound with basic chunk data
     */
    public static NbtCompound createBaseNbt(ChunkPos pos, int dataVersion) {
        NbtCompound nbt = new NbtCompound();
        nbt.putInt(DATA_VERSION_KEY, dataVersion);
        nbt.putString(STATUS_KEY, STATUS_EMPTY);
        nbt.putInt(X_POS_KEY, pos.x);
        nbt.putInt(Z_POS_KEY, pos.z);
        return nbt;
    }

    /**
     * Puts a ChunkDelta marker into an NBT compound under the standard Chunkis key.
     * <p>
     * The actual delta data is stored separately via CisStorage. This method
     * only marks the presence of a delta for the chunk deserialization process.
     * </p>
     *
     * @param root  The root NBT compound to add the marker to
     * @param delta The ChunkDelta (used to check if data exists)
     */
    public static void putDelta(NbtCompound root, ChunkDelta<BlockState, NbtCompound> delta) {
        NbtCompound chunkisData = new NbtCompound();
        chunkisData.putBoolean(HAS_DELTA_KEY, delta != null && !delta.isEmpty());
        root.put(CHUNKIS_DATA_KEY, chunkisData);
    }
}
