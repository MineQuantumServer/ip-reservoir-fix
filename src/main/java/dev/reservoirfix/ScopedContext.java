package dev.reservoirfix;

import java.util.Objects;

/** A worker-local context, restored even on failure or a nested invocation. */
public final class ScopedContext<T> {
    private final ThreadLocal<T> current = new ThreadLocal<>();

    public T current() {
        return current.get();
    }

    public void run(T context, Runnable action) {
        Objects.requireNonNull(context, "context");
        T previous = current.get();
        current.set(context);
        try {
            action.run();
        } finally {
            if (previous == null) {
                // Chunk workers are pooled; do not retain a region/world in them.
                current.remove();
            } else {
                current.set(previous);
            }
        }
    }
}
