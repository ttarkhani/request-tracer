package com.tracer.gateway.controller;

import org.springframework.beans.factory.annotation.Autowired;
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

    private static final String ORDERS_SERVICE_URL = "http://localhost:8081/api/orders";

    @PostMapping("/place")
    public Object placeOrder(@RequestBody OrderRequest request) {
        String traceId = UUID.randomUUID().toString();
        System.out.println("[API-GATEWAY] [" + traceId + "] Received order request: " + request);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Trace-Id", traceId);

        HttpEntity<OrderRequest> httpEntity = new HttpEntity<>(request, headers);

        System.out.println("[API-GATEWAY] [" + traceId + "] Calling Orders service...");
        Object response = restTemplate.postForObject(ORDERS_SERVICE_URL, httpEntity, Object.class);
        System.out.println("[API-GATEWAY] [" + traceId + "] Received response from Orders service");

        return response;
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