package com.tracer.orders.controller;

import com.tracer.orders.LogEntry;
import com.tracer.orders.LogShipper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
public class OrderServiceController {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private LogShipper logShipper;

    @Value("${inventory.service.url}")
    private String inventoryServiceUrl;

    @PostMapping
    public OrderResponse createOrder(
            @RequestBody OrderRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String inboundTraceId) {

        // A request without trace context starts a new trace at this hop; the new ID is still
        // forwarded downstream so the rest of the chain stays correlated (never ship a null/blank key).
        String traceId = (inboundTraceId == null || inboundTraceId.isBlank())
                ? UUID.randomUUID().toString()
                : inboundTraceId;
        long receivedAt = System.currentTimeMillis();
        System.out.println("[ORDERS-SERVICE] [" + traceId + "] Creating order: " + request);
        shipLog(traceId, "Received order request", "success", receivedAt);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Trace-Id", traceId);

        InventoryCheckRequest inventoryRequest = new InventoryCheckRequest();
        inventoryRequest.items = request.items;

        HttpEntity<InventoryCheckRequest> httpEntity = new HttpEntity<>(inventoryRequest, headers);

        System.out.println("[ORDERS-SERVICE] [" + traceId + "] Calling Inventory service...");
        restTemplate.postForObject(inventoryServiceUrl, httpEntity, Object.class);

        long completedAt = System.currentTimeMillis();
        System.out.println("[ORDERS-SERVICE] [" + traceId + "] Received response from Inventory service");
        shipLog(traceId, "Completed - response received from Inventory", "success", completedAt);

        return new OrderResponse(
            "ORD-99999",
            "Order created at orders service",
            "created"
        );
    }

    /** Non-blocking: hands the entry (timestamped at the event) to the async shipper. */
    private void shipLog(String traceId, String message, String status, long timestamp) {
        logShipper.ship(new LogEntry(traceId, "orders-service", message, status, timestamp));
    }

    static class OrderRequest {
        public String customerId;
        public String items;

        public OrderRequest() {}

        @Override
        public String toString() {
            return "OrderRequest{customerId='" + customerId + "', items='" + items + "'}";
        }
    }

    static class InventoryCheckRequest {
        public String items;
    }

    static class OrderResponse {
        public String orderId;
        public String message;
        public String status;

        public OrderResponse(String orderId, String message, String status) {
            this.orderId = orderId;
            this.message = message;
            this.status = status;
        }
    }
}
