package com.tracer.gateway.controller;

import com.tracer.gateway.LogEntry;
import com.tracer.gateway.LogShipper;
import com.tracer.gateway.TraceIds;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@RestController
@RequestMapping("/orders")
@CrossOrigin(origins = "*", exposedHeaders = TraceIds.HEADER)
public class OrderController {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private LogShipper logShipper;

    @Value("${orders.service.url}")
    private String ordersServiceUrl;

    @PostMapping("/place")
    public ResponseEntity<Object> placeOrder(
            @RequestBody OrderRequest request,
            @RequestHeader(value = TraceIds.HEADER, required = false) String inboundTraceId) {
        String traceId = TraceIds.resolve(inboundTraceId);
        long receivedAt = System.currentTimeMillis();

        System.out.println("[API-GATEWAY] [" + traceId + "] Received order request: " + request);
        shipLog(traceId, "Received order request", "success", receivedAt);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(TraceIds.HEADER, traceId);

        HttpEntity<OrderRequest> httpEntity = new HttpEntity<>(request, headers);

        System.out.println("[API-GATEWAY] [" + traceId + "] Calling Orders service...");
        Object response;
        try {
            response = restTemplate.postForObject(ordersServiceUrl, httpEntity, Object.class);
        } catch (HttpStatusCodeException e) {
            // Orders answered with an error: pass its status and body through, still tagged with the trace ID.
            System.out.println("[API-GATEWAY] [" + traceId + "] Orders service returned " + e.getStatusCode());
            return ResponseEntity.status(e.getStatusCode()).header(TraceIds.HEADER, traceId)
                    .contentType(MediaType.APPLICATION_JSON).body(e.getResponseBodyAsString());
        } catch (RestClientException e) {
            // Orders unreachable (connection refused, I/O error).
            System.out.println("[API-GATEWAY] [" + traceId + "] Orders service unreachable: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).header(TraceIds.HEADER, traceId)
                    .body(Map.of("error", "orders service unreachable", "traceId", traceId));
        }

        long completedAt = System.currentTimeMillis();
        System.out.println("[API-GATEWAY] [" + traceId + "] Received response from Orders service");
        shipLog(traceId, "Completed - response received from Orders", "success", completedAt);

        return ResponseEntity.ok().header(TraceIds.HEADER, traceId).body(response);
    }

    /** Non-blocking: hands the entry (timestamped at the event) to the async shipper. */
    private void shipLog(String traceId, String message, String status, long timestamp) {
        logShipper.ship(new LogEntry(traceId, "api-gateway", message, status, timestamp));
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
