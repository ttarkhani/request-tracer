package com.tracer.orders.controller;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderServiceController {

    @PostMapping
    public OrderResponse createOrder(@RequestBody OrderRequest request) {
        System.out.println("[ORDERS-SERVICE] Creating order: " + request);
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