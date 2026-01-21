package io.liparakis.chunkis.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A simple palette that maps objects of type T to integer IDs.
 * Used to compress repeated states in ChunkDelta.
 */
public class Palette<T> {
    private static final int INITIAL_CAPACITY = 32;

    private final List<T> idToEntry = new ArrayList<>(INITIAL_CAPACITY);
    private final Map<T, Integer> entryToId = new HashMap<>(INITIAL_CAPACITY);

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

    public T get(int id) {
        return (id >= 0 && id < idToEntry.size()) ? idToEntry.get(id) : null;
    }

    public List<T> getAll() {
        return idToEntry;
    }

    public int size() {
        return idToEntry.size();
    }
}