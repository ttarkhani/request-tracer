package com.tracer.inventory;

import com.tracer.inventory.testsupport.StubHttpServer;
import com.tracer.inventory.testsupport.StubHttpServer.HangingServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LogShipperTest {

    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final List<AutoCloseable> closeables = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        for (AutoCloseable c : closeables) {
            c.close();
        }
    }

    LogShipper shipper(String url, int capacity, int workers, int readTimeoutMs, long flushMs) {
        return new LogShipper(url, capacity, workers, 500, readTimeoutMs, flushMs, registry);
    }

    double count(String outcome) {
        return registry.get("log.shipper.events").tag("outcome", outcome).counter().count();
    }

    double depth() {
        return registry.get("log.shipper.queue.depth").gauge().value();
    }

    static LogEntry entry(int i) {
        return new LogEntry("trace-" + i, "test", "m", "success", 1000L + i);
    }

    static void waitUntil(java.util.function.BooleanSupplier cond, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!cond.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    @Test
    void shipsEveryEntryAndBooksBalance() throws Exception {
        StubHttpServer agg = new StubHttpServer();
        closeables.add(agg);
        LogShipper s = shipper(agg.url("/logs"), 10_000, 2, 2000, 3000);
        for (int i = 0; i < 500; i++) {
            s.ship(entry(i));
        }
        waitUntil(() -> count("shipped") == 500, Duration.ofSeconds(20));
        s.shutdown();

        assertThat(count("enqueued")).isEqualTo(500);
        assertThat(count("shipped")).isEqualTo(500);
        assertThat(count("dropped")).isZero();
        assertThat(count("failed")).isZero();
        assertThat(agg.requests("/logs")).hasSize(500);
        assertThat(agg.requests("/logs").get(0).body()).contains("\"timestamp\":");
    }

    @Test
    void fullQueueDropsNewestWithoutBlockingAndCountsIt() throws Exception {
        HangingServer agg = new HangingServer();
        closeables.add(agg);
        LogShipper s = shipper(agg.url("/logs"), 5, 1, 10_000, 100);
        s.ship(entry(0));
        waitUntil(() -> s.inFlight() == 1, Duration.ofSeconds(5)); // worker is now stuck on the hung aggregator

        long start = System.nanoTime();
        for (int i = 1; i < 20; i++) {
            s.ship(entry(i));
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).as("ship() must not block").isLessThan(1000);
        assertThat(count("enqueued")).isEqualTo(20);
        assertThat(depth()).isEqualTo(5);
        assertThat(count("dropped")).isEqualTo(14); // 20 - 1 in flight - 5 queued
        assertThat(count("enqueued")).isEqualTo(count("shipped") + count("dropped") + count("failed") + depth() + s.inFlight());
    }

    @Test
    void connectionRefusedIsCountedAsFailed() throws Exception {
        LogShipper s = shipper("http://127.0.0.1:" + StubHttpServer.closedPort() + "/logs", 100, 2, 2000, 3000);
        for (int i = 0; i < 10; i++) {
            s.ship(entry(i));
        }
        waitUntil(() -> count("failed") == 10, Duration.ofSeconds(10));
        s.shutdown();
        assertThat(count("failed")).isEqualTo(10);
        assertThat(count("enqueued")).isEqualTo(count("shipped") + count("dropped") + count("failed"));
    }

    @Test
    void readTimeoutBoundsAHungAggregator() throws Exception {
        HangingServer agg = new HangingServer();
        closeables.add(agg);
        LogShipper s = shipper(agg.url("/logs"), 100, 1, 300, 100);
        long start = System.nanoTime();
        s.ship(entry(0));
        waitUntil(() -> count("failed") == 1, Duration.ofSeconds(5));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(count("failed")).isEqualTo(1);
        assertThat(elapsedMs).isLessThan(3000);
    }

    @Test
    void shutdownDeliversQueuedEntriesWithinTheFlushBound() throws Exception {
        StubHttpServer agg = new StubHttpServer();
        closeables.add(agg);
        LogShipper s = shipper(agg.url("/logs"), 10_000, 2, 2000, 5000);
        for (int i = 0; i < 200; i++) {
            s.ship(entry(i));
        }
        s.shutdown();
        assertThat(count("shipped")).isEqualTo(200);
        assertThat(agg.requests("/logs")).hasSize(200);
        s.ship(entry(999)); // after shutdown: refused, counted
        assertThat(count("dropped")).isEqualTo(1);
        assertThat(count("enqueued")).isEqualTo(count("shipped") + count("dropped") + count("failed"));
    }

    @Test
    void shutdownIsBoundedWhenAggregatorHangs() throws Exception {
        HangingServer agg = new HangingServer();
        closeables.add(agg);
        LogShipper s = shipper(agg.url("/logs"), 100, 1, 10_000, 300);
        for (int i = 0; i < 10; i++) {
            s.ship(entry(i));
        }
        waitUntil(() -> s.inFlight() == 1, Duration.ofSeconds(5));
        long start = System.nanoTime();
        s.shutdown();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isLessThan(2000);
        assertThat(count("dropped")).isEqualTo(9); // still queued at the flush deadline
        assertThat(count("enqueued")).isEqualTo(count("shipped") + count("dropped") + count("failed") + s.inFlight());
    }
}
