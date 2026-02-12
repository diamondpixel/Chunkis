package io.liparakis.chunkis.storage;

import io.liparakis.chunkis.core.CisChunkPos;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Region file handler for 32x32 chunks.
 * Uses a simple offset/length header for chunk locations.
 */
final class RegionFile implements AutoCloseable {
    private static final int REGION_MASK = 31;
    private static final int CHUNKS_PER_REGION = 1024;
    private static final int HEADER_SIZE = 8192;
    private static final int HEADER_ENTRY_SIZE = 8;

    private final Path path;
    private FileChannel channel;
    final int[] offsets = new int[CHUNKS_PER_REGION]; // Package-private for compactor
    final int[] lengths = new int[CHUNKS_PER_REGION];
    private final ByteBuffer headerBuffer = ByteBuffer.allocateDirect(HEADER_ENTRY_SIZE);
    private boolean dirty = false;

    /**
     * Opens or creates a region file.
     *
     * @param dir     the parent directory
     * @param regionX region X coordinate
     * @param regionZ region Z coordinate
     * @throws IOException if the file cannot be opened
     */
    RegionFile(Path dir, int regionX, int regionZ) throws IOException {
        this.path = dir.resolve(String.format("r.%d.%d.cis", regionX, regionZ));
        this.channel = FileChannel.open(path,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE);

        if (channel.size() < HEADER_SIZE) {
            initializeNewRegion();
        } else {
            loadHeader();
        }
    }

    /**
     * Initializes a new region file with an empty header.
     */
    private void initializeNewRegion() throws IOException {
        channel.write(ByteBuffer.allocate(HEADER_SIZE), 0);
    }

    /**
     * Loads the header from an existing region file.
     */
    private void loadHeader() throws IOException {
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
        channel.read(header, 0);
        header.flip();

        for (int i = 0; i < CHUNKS_PER_REGION; i++) {
            offsets[i] = header.getInt();
            lengths[i] = header.getInt();
        }
    }

    /**
     * Reads chunk data from the region file.
     *
     * @param pos the chunk position
     * @return the raw bytes, or {@code null} if the chunk is not present
     * @throws IOException if a read error occurs
     */
    synchronized byte[] read(CisChunkPos pos) throws IOException {
        int index = getChunkIndex(pos);

        if (offsets[index] == 0) {
            return null;
        }

        ByteBuffer buffer = ByteBuffer.allocate(lengths[index]);
        channel.read(buffer, offsets[index]);
        return buffer.array();
    }

    /**
     * Writes chunk data to the region file.
     *
     * @param pos  the chunk position
     * @param data the data to write, or {@code null} to clear the chunk
     * @throws IOException if a write error occurs
     */
    synchronized void write(CisChunkPos pos, byte[] data) throws IOException {
        int index = getChunkIndex(pos);
        int dataLength = (data == null) ? 0 : data.length;

        int offset = calculateWriteOffset(index, dataLength);

        if (dataLength > 0) {
            channel.write(ByteBuffer.wrap(data), offset);
        }

        updateHeader(index, offset, dataLength);
        dirty = true;
    }

    /**
     * Calculates the offset where data should be written.
     * Reuses existing space if it fits, otherwise appends to the end.
     *
     * @param index      the chunk index
     * @param dataLength the length of the data to be written
     * @return the file offset
     * @throws IOException if a file error occurs
     */
    private int calculateWriteOffset(int index, int dataLength) throws IOException {
        if (dataLength <= lengths[index] && offsets[index] != 0) {
            return offsets[index];
        }
        return (int) channel.size();
    }

    /**
     * Updates the header entry for a chunk in memory and on disk.
     *
     * @param index  the chunk index
     * @param offset the new offset
     * @param length the new length
     * @throws IOException if a write error occurs
     */
    private void updateHeader(int index, int offset, int length) throws IOException {
        offsets[index] = (length == 0) ? 0 : offset;
        lengths[index] = length;

        headerBuffer.clear();
        headerBuffer.putInt(offsets[index]);
        headerBuffer.putInt(lengths[index]);
        headerBuffer.flip();

        channel.write(headerBuffer, (long) index * HEADER_ENTRY_SIZE);
    }

    /**
     * Gets the index of a chunk within the region file header (0-1023).
     *
     * @param pos the chunk position
     * @return the header index
     */
    private static int getChunkIndex(CisChunkPos pos) {
        return (pos.x() & REGION_MASK) + (pos.z() & REGION_MASK) * 32;
    }

    /**
     * Flushes pending writes to disk.
     */
    void flush() {
        if (dirty) {
            try {
                if (channel.isOpen()) {
                    channel.force(false);
                }
                dirty = false;
            } catch (IOException e) {
                io.liparakis.chunkis.Chunkis.LOGGER.warn("Failed to flush region file", e);
            }
        }
    }

    /**
     * Compacts the region file by rewriting it contiguously.
     * This operation is synchronized to prevent concurrent writes.
     */
    synchronized void compact() {
        try {
            flush();
            Path tempPath = path.resolveSibling(path.getFileName().toString() + ".tmp");

            try (FileChannel dest = FileChannel.open(tempPath, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE)) {

                // placeholder header
                dest.write(ByteBuffer.allocate(HEADER_SIZE));

                int currentOffset = HEADER_SIZE;
                ByteBuffer newHeader = ByteBuffer.allocate(HEADER_SIZE);

                for (int i = 0; i < CHUNKS_PER_REGION; i++) {
                    if (offsets[i] != 0 && lengths[i] > 0) {
                        try {
                            ByteBuffer chunkData = ByteBuffer.allocate(lengths[i]);
                            channel.read(chunkData, offsets[i]);
                            chunkData.flip();
                            dest.write(chunkData);

                            newHeader.putInt(currentOffset);
                            newHeader.putInt(lengths[i]);
                            currentOffset += lengths[i];
                        } catch (IOException e) {
                            newHeader.putInt(0);
                            newHeader.putInt(0);
                        }
                    } else {
                        newHeader.putInt(0);
                        newHeader.putInt(0);
                    }
                }

                newHeader.flip();
                dest.position(0);
                dest.write(newHeader);
                dest.force(true);
            }

            // Close current channel to allow swap
            channel.close();

            // Swap files
            Files.move(tempPath, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            // Re-open channel
            channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE);

            // Refresh header in memory
            loadHeader();
        } catch (IOException e) {
            io.liparakis.chunkis.Chunkis.LOGGER.error("Failed to compact region {}", path, e);
            // Try to reopen if we failed closed
            if (!channel.isOpen()) {
                try {
                    channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE,
                            StandardOpenOption.CREATE);
                } catch (IOException ex) {
                    io.liparakis.chunkis.Chunkis.LOGGER
                            .error("CRITICAL: Failed to reopen region after failed compaction", ex);
                }
            }
        }
    }

    /**
     * Closes the region file.
     */
    @Override
    public void close() {
        flush();
        try {
            channel.close();
        } catch (IOException e) {
            io.liparakis.chunkis.Chunkis.LOGGER.warn("Failed to close region file", e);
        }
    }
}
