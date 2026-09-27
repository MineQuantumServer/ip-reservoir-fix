package dev.reservoirfix;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ScopedContextTest {
    @Test
    void restoresOuterContextAfterNestedFailureAndClearsWorker() {
        var context = new ScopedContext<String>();
        context.run("outer", () -> {
            assertEquals("outer", context.current());
            var error = new IllegalStateException("scan failed");
            assertSame(error, assertThrows(IllegalStateException.class, () ->
                context.run("inner", () -> {
                    assertEquals("inner", context.current());
                    throw error;
                })));
            assertEquals("outer", context.current());
        });
        assertNull(context.current());
    }

    @Test
    void clearsContextWhenOutermostScanThrows() {
        var context = new ScopedContext<String>();
        assertThrows(IllegalStateException.class, () -> context.run("world", () -> {
            throw new IllegalStateException("scan failed");
        }));
        assertNull(context.current());
    }

    @Test
    void simultaneousWorkersDoNotShareWorldsAndCanBeReused() throws Exception {
        var context = new ScopedContext<String>();
        var barrier = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> exerciseWorker(context, barrier, "overworld"));
            var second = workers.submit(() -> exerciseWorker(context, barrier, "nether"));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertNull(context.current());
    }

    private static void exerciseWorker(ScopedContext<String> context, CyclicBarrier barrier, String world) {
        for (int i = 0; i < 30; i++) {
            assertNull(context.current());
            context.run(world, () -> {
                try { barrier.await(5, TimeUnit.SECONDS); }
                catch (Exception e) { throw new AssertionError(e); }
                assertEquals(world, context.current());
            });
            assertNull(context.current());
        }
    }
}
