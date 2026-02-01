package io.liparakis.cisplugin.decoder;

/**
 * Simple block state representation for the standalone decoder.
 * Replaces Minecraft's BlockState without any dependencies.
 */
public record SimpleBlockState(int blockId, String blockName) {

    @Override
    public String toString() {
        return blockName != null ? blockName : "block:" + blockId;
    }
}
