package com.tracer.aggregator;

import com.tracer.aggregator.model.LogEntry;
import com.tracer.aggregator.store.TraceStore;
import org.junit.jupiter.api.RepeatedTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Many threads write many traces at once; every write must be kept.
 * Each repetition: 16 threads x 200 traces x 5 entries = 16,000 entries, 80 per trace.
 */
class TraceStoreConcurrencyTest {

    static final int THREADS = 16;
    static final int TRACES = 200;
    static final int ENTRIES_PER_THREAD_PER_TRACE = 5;

    @RepeatedTest(25)
    void concurrentWritesAreNeverLost() throws Exception {
        TraceStore store = new TraceStore();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < THREADS; t++) {
                final int thread = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < ENTRIES_PER_THREAD_PER_TRACE; i++) {
                        for (int trace = 0; trace < TRACES; trace++) {
                            store.add(new LogEntry("trace-" + trace, "thread-" + thread, "m" + i, "success", i));
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS); // rethrows any exception from a writer thread
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(store.traceCount()).isEqualTo(TRACES);
        assertThat(store.entryCount()).isEqualTo((long) THREADS * TRACES * ENTRIES_PER_THREAD_PER_TRACE);
        for (int trace = 0; trace < TRACES; trace++) {
            assertThat(store.get("trace-" + trace))
                    .as("entries for trace-%d", trace)
                    .hasSize(THREADS * ENTRIES_PER_THREAD_PER_TRACE);
        }
    }
}
