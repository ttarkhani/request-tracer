package com.tracer.aggregator.controller;

import com.tracer.aggregator.model.LogEntry;
import com.tracer.aggregator.store.TraceStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@CrossOrigin(origins = "*")
public class LogController {

    private final TraceStore traceStore;

    public LogController(TraceStore traceStore) {
        this.traceStore = traceStore;
    }

    @PostMapping("/logs")
    public ResponseEntity<String> receiveLog(@RequestBody LogEntry entry) {
        if (entry.traceId == null || entry.traceId.isBlank()) {
            return ResponseEntity.badRequest().body("traceId is required");
        }
        traceStore.add(entry);
        System.out.println("[LOG-AGGREGATOR] Stored log for trace " + entry.traceId + " from " + entry.serviceName);
        return ResponseEntity.ok("stored");
    }

    /** Store totals, used by the load test's no-loss check. */
    @GetMapping("/stats")
    public Map<String, Long> stats() {
        return Map.of("traces", (long) traceStore.traceCount(), "entries", traceStore.entryCount());
    }

    /** Entries for one trace, sorted by timestamp. */
    @GetMapping("/traces/{traceId}")
    public List<LogEntry> getTrace(@PathVariable String traceId) {
        return traceStore.get(traceId);
    }
}
