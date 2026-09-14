package com.tracer.inventory.controller;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    @PostMapping("/check")
    public InventoryCheckResponse checkInventory(@RequestBody InventoryCheckRequest request) {
        System.out.println("[INVENTORY-SERVICE] Checking inventory: " + request);
        return new InventoryCheckResponse(true, "All items in stock");
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