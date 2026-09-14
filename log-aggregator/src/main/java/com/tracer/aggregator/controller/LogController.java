package com.tracer.aggregator.controller;

import com.tracer.aggregator.model.LogEntry;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@RestController
public class LogController {

    private final Map<String, List<LogEntry>> traceStore = new ConcurrentHashMap<>();

    @PostMapping("/logs")
    public String receiveLog(@RequestBody LogEntry entry) {
        traceStore.computeIfAbsent(entry.traceId, k -> new CopyOnWriteArrayList<>()).add(entry);
        System.out.println("[LOG-AGGREGATOR] Stored log for trace " + entry.traceId + " from " + entry.serviceName);
        return "stored";
    }

    @GetMapping("/traces/{traceId}")
    public List<LogEntry> getTrace(@PathVariable String traceId) {
        return traceStore.getOrDefault(traceId, List.of());
    }
}