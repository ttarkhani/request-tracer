package com.tracer.orders.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("/api/orders")
public class OrderServiceController {

    @Autowired
    private RestTemplate restTemplate;

    private static final String INVENTORY_SERVICE_URL = "http://localhost:8082/api/inventory/check";

    @PostMapping
    public OrderResponse createOrder(
            @RequestBody OrderRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        System.out.println("[ORDERS-SERVICE] [" + traceId + "] Creating order: " + request);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Trace-Id", traceId);

        InventoryCheckRequest inventoryRequest = new InventoryCheckRequest();
        inventoryRequest.items = request.items;

        HttpEntity<InventoryCheckRequest> httpEntity = new HttpEntity<>(inventoryRequest, headers);

        System.out.println("[ORDERS-SERVICE] [" + traceId + "] Calling Inventory service...");
        restTemplate.postForObject(INVENTORY_SERVICE_URL, httpEntity, Object.class);
        System.out.println("[ORDERS-SERVICE] [" + traceId + "] Received response from Inventory service");

        return new OrderResponse(
            "ORD-99999",
            "Order created at orders service",
            "created"
        );
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