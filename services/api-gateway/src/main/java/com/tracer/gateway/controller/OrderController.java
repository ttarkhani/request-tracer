package com.tracer.gateway.controller;

import com.tracer.gateway.LogEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

@RestController
@RequestMapping("/orders")
public class OrderController {

    @Autowired
    private RestTemplate restTemplate;

    @Value("${orders.service.url}")
    private String ordersServiceUrl;

    @Value("${aggregator.url}")
    private String aggregatorUrl;

    @PostMapping("/place")
    public Object placeOrder(@RequestBody OrderRequest request) {
        String traceId = UUID.randomUUID().toString();
        long receivedAt = System.currentTimeMillis();

        System.out.println("[API-GATEWAY] [" + traceId + "] Received order request: " + request);
        shipLog(traceId, "Received order request", "success", receivedAt);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Trace-Id", traceId);

        HttpEntity<OrderRequest> httpEntity = new HttpEntity<>(request, headers);

        System.out.println("[API-GATEWAY] [" + traceId + "] Calling Orders service...");
        Object response = restTemplate.postForObject(ordersServiceUrl, httpEntity, Object.class);

        long completedAt = System.currentTimeMillis();
        System.out.println("[API-GATEWAY] [" + traceId + "] Received response from Orders service");
        shipLog(traceId, "Completed - response received from Orders", "success", completedAt);

        return response;
    }

    private void shipLog(String traceId, String message, String status, long timestamp) {
        try {
            LogEntry entry = new LogEntry(traceId, "api-gateway", message, status, timestamp);
            restTemplate.postForObject(aggregatorUrl, entry, String.class);
        } catch (Exception e) {
            System.out.println("[API-GATEWAY] Failed to ship log to aggregator: " + e.getMessage());
        }
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
}
