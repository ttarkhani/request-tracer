package com.tracer.aggregator.store;

import com.tracer.aggregator.model.LogEntry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory trace store. Thread-safe: services post entries for the same and
 * different traces concurrently. Unbounded (see README "Known limitations").
 */
@Component
public class TraceStore {

    private final Map<String, List<LogEntry>> traces = new ConcurrentHashMap<>();

    public void add(LogEntry entry) {
        traces.computeIfAbsent(entry.traceId, k -> new CopyOnWriteArrayList<>()).add(entry);
    }

    /** Entries for one trace, sorted by timestamp (stable: ties keep arrival order). */
    public List<LogEntry> get(String traceId) {
        List<LogEntry> entries = traces.get(traceId);
        if (entries == null) {
            return List.of();
        }
        List<LogEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingLong(e -> e.timestamp));
        return sorted;
    }

    public int traceCount() {
        return traces.size();
    }

    public long entryCount() {
        long total = 0;
        for (List<LogEntry> entries : traces.values()) {
            total += entries.size();
        }
        return total;
    }
}
