package com.tracer.gateway.controller;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/orders")
public class OrderController {

    @PostMapping("/place")
    public OrderResponse placeOrder(@RequestBody OrderRequest request) {
        System.out.println("[API-GATEWAY] Received order request: " + request);
        return new OrderResponse(
            "ORD-12345",
            "Order placed successfully",
            "pending"
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