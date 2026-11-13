package com.tracer.gateway;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ships log entries to the aggregator off the request path.
 *
 * <ul>
 *   <li>{@link #ship} never blocks: it offers to a bounded queue and returns.</li>
 *   <li>Overflow policy: <b>drop the newest</b> entry (the one being offered) when the queue is full; counted
 *       as {@code dropped}. Entries already queued are kept.</li>
 *   <li>Worker threads (daemon) POST each entry with explicit connect/read timeouts. No retries: a failed POST is
 *       counted as {@code failed} (a read timeout may race a delivery the aggregator did store).</li>
 *   <li>Counters ({@code log.shipper.events}, tag {@code outcome}): {@code enqueued} = every entry handed to
 *       {@link #ship}; at quiescence {@code enqueued == shipped + dropped + failed}. Gauge
 *       {@code log.shipper.queue.depth}.</li>
 *   <li>Shutdown: stop accepting, drain for at most {@code flush-timeout}; whatever is still queued after that is
 *       counted as {@code dropped}.</li>
 * </ul>
 * Duplicated per service on purpose (services share no code).
 */
@Component
public class LogShipper {

    private final String aggregatorUrl;
    private final RestTemplate client;
    private final BlockingQueue<LogEntry> queue;
    private final long flushTimeoutMs;
    private final List<Thread> workers = new ArrayList<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile boolean accepting = true;
    private volatile boolean running = true;

    private final Counter enqueued;
    private final Counter shipped;
    private final Counter dropped;
    private final Counter failed;

    public LogShipper(
            @Value("${aggregator.url}") String aggregatorUrl,
            @Value("${log.shipper.queue-capacity:10000}") int capacity,
            @Value("${log.shipper.workers:2}") int workerCount,
            @Value("${log.shipper.connect-timeout-ms:500}") int connectTimeoutMs,
            @Value("${log.shipper.read-timeout-ms:2000}") int readTimeoutMs,
            @Value("${log.shipper.flush-timeout-ms:3000}") long flushTimeoutMs,
            MeterRegistry registry) {
        this.aggregatorUrl = aggregatorUrl;
        this.flushTimeoutMs = flushTimeoutMs;
        this.queue = new ArrayBlockingQueue<>(capacity);

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        this.client = new RestTemplate(factory);

        this.enqueued = counter(registry, "enqueued");
        this.shipped = counter(registry, "shipped");
        this.dropped = counter(registry, "dropped");
        this.failed = counter(registry, "failed");
        Gauge.builder("log.shipper.queue.depth", queue, BlockingQueue::size)
                .description("Log entries waiting to be shipped").register(registry);

        for (int i = 0; i < workerCount; i++) {
            Thread t = new Thread(this::workLoop, "log-shipper-" + i);
            t.setDaemon(true);
            t.start();
            workers.add(t);
        }
    }

    private static Counter counter(MeterRegistry registry, String outcome) {
        return Counter.builder("log.shipper.events").tag("outcome", outcome)
                .description("Log entries by shipping outcome").register(registry);
    }

    /** Non-blocking. The entry's timestamp must already be the time of the event, not of sending. */
    public void ship(LogEntry entry) {
        enqueued.increment();
        if (!accepting || !queue.offer(entry)) {
            dropped.increment();
        }
    }

    private void workLoop() {
        while (running || !queue.isEmpty()) {
            LogEntry entry;
            try {
                entry = queue.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (entry == null) {
                continue;
            }
            inFlight.incrementAndGet();
            try {
                client.postForObject(aggregatorUrl, entry, String.class);
                shipped.increment();
            } catch (Exception e) {
                failed.increment();
            } finally {
                inFlight.decrementAndGet();
            }
        }
    }

    public int queueDepth() {
        return queue.size();
    }

    public int inFlight() {
        return inFlight.get();
    }

    @PreDestroy
    public void shutdown() throws InterruptedException {
        accepting = false;
        long deadline = System.currentTimeMillis() + flushTimeoutMs;
        while ((!queue.isEmpty() || inFlight.get() > 0) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        running = false;
        List<LogEntry> leftover = new ArrayList<>();
        queue.drainTo(leftover);
        if (!leftover.isEmpty()) {
            dropped.increment(leftover.size());
        }
        for (Thread t : workers) {
            t.join(Math.max(1, deadline - System.currentTimeMillis()) + 100);
        }
        leftover.clear();
        queue.drainTo(leftover); // an offer that raced the first drain
        if (!leftover.isEmpty()) {
            dropped.increment(leftover.size());
        }
    }
}
