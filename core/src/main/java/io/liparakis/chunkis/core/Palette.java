package io.liparakis.chunkis.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A bidirectional mapping between objects of type T and compact integer IDs.
 * <p>
 * This class implements a palette data structure commonly used for data compression
 * in chunk storage systems. It maintains a one-to-one correspondence between unique
 * objects and sequential integer IDs, allowing repeated objects to be represented
 * by small integer references instead of storing the full object multiple times.
 * </p>
 * <p>
 * The palette is particularly useful for BlockStates in Minecraft chunks, where:
 * <ul>
 *   <li>Many blocks in a chunk share the same state (e.g., multiple stone blocks)</li>
 *   <li>Storing a single BlockState reference plus many integer IDs is more memory-efficient</li>
 *   <li>Integer IDs can be packed into compact bit arrays for further compression</li>
 * </ul>
 * </p>
 * <p>
 * Implementation details:
 * <ul>
 *   <li>IDs are assigned sequentially starting from 0</li>
 *   <li>Lookups by object are O(1) via HashMap</li>
 *   <li>Lookups by ID are O(1) via ArrayList</li>
 *   <li>IDs are stable - once assigned, they never change for a given object</li>
 *   <li>Not thread-safe - external synchronization required for concurrent access</li>
 * </ul>
 * </p>
 * <p>
 * Memory characteristics:
 * <ul>
 *   <li>Initial capacity: 32 entries (tuned for typical chunk diversity)</li>
 *   <li>Grows dynamically as needed</li>
 *   <li>Memory overhead: ~2 references per unique entry (List + Map)</li>
 * </ul>
 * </p>
 *
 * @param <T> the type of objects to be indexed in the palette
 * @see ChunkDelta
 * @see BlockInstruction
 */
public class Palette<T> {
    /** Initial capacity optimized for typical chunk block diversity */
    private static final int INITIAL_CAPACITY = 32;

    /** Sequential list mapping IDs to entries (ID → Entry) */
    private final List<T> idToEntry = new ArrayList<>(INITIAL_CAPACITY);

    /** Reverse lookup mapping entries to their assigned IDs (Entry → ID) */
    private final Map<T, Integer> entryToId = new HashMap<>(INITIAL_CAPACITY);

    /**
     * Retrieves the ID for the given entry, adding it to the palette if not present.
     * <p>
     * This method ensures that each unique object gets a consistent ID. If the object
     * is already in the palette, its existing ID is returned. Otherwise, a new ID is
     * assigned sequentially.
     * </p>
     * <p>
     * Example usage:
     * <pre>{@code
     * Palette<BlockState> palette = new Palette<>();
     * int stoneId = palette.getOrAdd(Blocks.STONE.getDefaultState());  // Returns 0
     * int dirtId = palette.getOrAdd(Blocks.DIRT.getDefaultState());    // Returns 1
     * int stoneId2 = palette.getOrAdd(Blocks.STONE.getDefaultState()); // Returns 0 (same as before)
     * }</pre>
     * </p>
     *
     * @param entry the object to look up or add to the palette
     * @return the ID associated with this entry (existing or newly assigned)
     * @throws IllegalArgumentException if entry is null
     */
    public int getOrAdd(T entry) {
        if (entry == null) {
            throw new IllegalArgumentException("Cannot add null to Palette");
        }

        Integer existingId = entryToId.get(entry);
        if (existingId != null) {
            return existingId;
        }

        int id = idToEntry.size();
        idToEntry.add(entry);
        entryToId.put(entry, id);
        return id;
    }

    /**
     * Retrieves the entry associated with the given ID.
     * <p>
     * This is a fast O(1) lookup operation. If the ID is out of bounds,
     * {@code null} is returned instead of throwing an exception for performance reasons.
     * </p>
     *
     * @param id the palette ID to look up
     * @return the entry associated with this ID, or {@code null} if the ID is invalid
     */
    public T get(int id) {
        return (id >= 0 && id < idToEntry.size()) ? idToEntry.get(id) : null;
    }

    /**
     * Returns the complete list of all entries in the palette.
     * <p>
     * The returned list is the internal list used by the palette. Entries are
     * ordered by their assigned IDs (index 0 = ID 0, index 1 = ID 1, etc.).
     * </p>
     * <p>
     * <b>Warning:</b> Modifying the returned list will corrupt the palette's state.
     * This method returns the internal list for performance reasons (avoiding copies
     * during serialization), so callers must treat it as read-only.
     * </p>
     *
     * @return the internal list of palette entries in ID order
     */
    public List<T> getAll() {
        return idToEntry;
    }
}