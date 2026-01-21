package io.liparakis.chunkis.core;

public class LeafTickContext {

    private static final ThreadLocal<Boolean> active = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public static void set(boolean isActive) {
        active.set(isActive);
    }

    public static boolean get() {
        return active.get();
    }
}
