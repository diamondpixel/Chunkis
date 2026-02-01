package io.liparakis.chunkis.core;

/**
 * Thread-local context tracker for leaf tick operations.
 * <p>
 * This utility class provides a thread-safe mechanism to track whether the current
 * thread is executing within a "leaf tick" context. A leaf tick typically refers to
 * a specific phase of chunk or block updates where certain operations should be
 * handled differently.
 * </p>
 * <p>
 * The context is stored per-thread using {@link ThreadLocal}, ensuring that:
 * <ul>
 *   <li>Multiple threads can have different context states simultaneously</li>
 *   <li>No synchronization overhead is required for reads/writes</li>
 *   <li>Context state doesn't leak between different operations on the same thread</li>
 * </ul>
 * </p>
 * <p>
 * Typical usage pattern:
 * <pre>{@code
 * LeafTickContext.set(true);
 * try {
 *     // Perform operations that need to know they're in a leaf tick context
 *     performLeafTickOperations();
 * } finally {
 *     LeafTickContext.set(false);
 * }
 * }</pre>
 * </p>
 * <p>
 * <b>Note:</b> Users of this class must ensure proper cleanup by resetting the
 * context to {@code false} after use, preferably in a finally block, to prevent
 * context pollution for subsequent operations on the same thread.
 * </p>
 *
 * @see ThreadLocal
 */
public class LeafTickContext {

    /**
     * Thread-local storage for the active state of leaf tick context.
     * Defaults to {@code false} for each thread.
     */
    private static final ThreadLocal<Boolean> active = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * Sets whether the current thread is in a leaf tick context.
     * <p>
     * This method should be called to mark the beginning or end of a leaf tick
     * operation. It's important to reset the context to {@code false} when the
     * operation completes to avoid affecting subsequent operations.
     * </p>
     *
     * @param isActive {@code true} to mark the thread as being in a leaf tick context,
     *                 {@code false} to mark it as not being in such a context
     */
    public static void set(boolean isActive) {
        active.set(isActive);
    }

    /**
     * Checks whether the current thread is in a leaf tick context.
     * <p>
     * This method can be called from any code that needs to determine if it's
     * executing within a leaf tick operation and adjust its behavior accordingly.
     * </p>
     *
     * @return {@code true} if the current thread is in a leaf tick context,
     *         {@code false} otherwise (default state)
     */
    public static boolean get() {
        return active.get();
    }
}