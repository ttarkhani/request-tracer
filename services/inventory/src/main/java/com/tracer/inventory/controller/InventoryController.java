package com.tracer.inventory.controller;

import com.tracer.inventory.LogEntry;
import com.tracer.inventory.LogShipper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    @Autowired
    private LogShipper logShipper;

    @PostMapping("/check")
    public InventoryCheckResponse checkInventory(
            @RequestBody InventoryCheckRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String inboundTraceId) {

        // A request without trace context starts a new trace at this hop (never ship a null/blank key).
        String traceId = (inboundTraceId == null || inboundTraceId.isBlank())
                ? UUID.randomUUID().toString()
                : inboundTraceId;
        long receivedAt = System.currentTimeMillis();
        System.out.println("[INVENTORY-SERVICE] [" + traceId + "] Checking inventory: " + request);
        shipLog(traceId, "Received inventory check request", "success", receivedAt);

        long completedAt = System.currentTimeMillis();
        System.out.println("[INVENTORY-SERVICE] [" + traceId + "] Inventory check complete");
        shipLog(traceId, "Completed - inventory check finished", "success", completedAt);

        return new InventoryCheckResponse(true, "All items in stock");
    }

    /** Non-blocking: hands the entry (timestamped at the event) to the async shipper. */
    private void shipLog(String traceId, String message, String status, long timestamp) {
        logShipper.ship(new LogEntry(traceId, "inventory-service", message, status, timestamp));
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
