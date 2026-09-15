package com.tracer.inventory.controller;

import com.tracer.inventory.LogEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    @Autowired
    private RestTemplate restTemplate;

    @Value("${aggregator.url}")
    private String aggregatorUrl;

    @PostMapping("/check")
    public InventoryCheckResponse checkInventory(
            @RequestBody InventoryCheckRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        long receivedAt = System.currentTimeMillis();
        System.out.println("[INVENTORY-SERVICE] [" + traceId + "] Checking inventory: " + request);
        shipLog(traceId, "Received inventory check request", "success", receivedAt);

        long completedAt = System.currentTimeMillis();
        System.out.println("[INVENTORY-SERVICE] [" + traceId + "] Inventory check complete");
        shipLog(traceId, "Completed - inventory check finished", "success", completedAt);

        return new InventoryCheckResponse(true, "All items in stock");
    }

    private void shipLog(String traceId, String message, String status, long timestamp) {
        try {
            LogEntry entry = new LogEntry(traceId, "inventory-service", message, status, timestamp);
            restTemplate.postForObject(aggregatorUrl, entry, String.class);
        } catch (Exception e) {
            System.out.println("[INVENTORY-SERVICE] Failed to ship log to aggregator: " + e.getMessage());
        }
    }

    static class InventoryCheckRequest {
        public String items;

        public InventoryCheckRequest() {}

        @Override
        public String toString() {
            return "InventoryCheckRequest{items='" + items + "'}";
        }
    }

    static class InventoryCheckResponse {
        public boolean allInStock;
        public String message;

        public InventoryCheckResponse(boolean allInStock, String message) {
            this.allInStock = allInStock;
            this.message = message;
        }
    }
}
