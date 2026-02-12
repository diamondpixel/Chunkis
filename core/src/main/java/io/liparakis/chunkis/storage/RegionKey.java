package io.liparakis.chunkis.storage;

import org.jetbrains.annotations.NotNull;

/**
 * Region coordinate key for caching.
 *
 * @param x the region's X coordinate
 * @param z the region's Z coordinate
 */
record RegionKey(int x, int z) {
    @Override
    public @NotNull String toString() {
        return "r." + x + "." + z;
    }
}
