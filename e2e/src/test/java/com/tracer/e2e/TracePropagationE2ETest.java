package com.tracer.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Black-box test against the real stack (docker compose up). Base URLs default to the
 * compose host ports; override with -Dgateway.url=... -Daggregator.url=...
 */
class TracePropagationE2ETest {

    static final String GATEWAY = System.getProperty("gateway.url", "http://localhost:8080");
    static final String AGGREGATOR = System.getProperty("aggregator.url", "http://localhost:8084");
    static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final ObjectMapper json = new ObjectMapper();

    static HttpResponse<String> placeOrder(String inboundTraceId) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(GATEWAY + "/orders/place"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"customerId\":\"E2E\",\"items\":\"sku-001,sku-002\"}"));
        if (inboundTraceId != null) {
            b.header("X-Trace-Id", inboundTraceId);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Polls the aggregator until the trace has 6 entries (log shipping may be asynchronous) or 15 s pass. */
    static List<JsonNode> fetchTrace(String traceId) throws Exception {
        List<JsonNode> entries = List.of();
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        do {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(AGGREGATOR + "/traces/" + traceId))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(res.statusCode()).isEqualTo(200);
            entries = new ArrayList<>();
            for (JsonNode n : json.readTree(res.body())) {
                entries.add(n);
            }
            if (entries.size() >= 6) {
                Thread.sleep(300); // catch any extra (unexpected) entries before asserting "exactly 6"
                return fetchTraceOnce(traceId);
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        return entries;
    }

    static List<JsonNode> fetchTraceOnce(String traceId) throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(AGGREGATOR + "/traces/" + traceId))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode n : json.readTree(res.body())) {
            out.add(n);
        }
        return out;
    }

    static void assertCompleteCausalTrace(String traceId, List<JsonNode> entries) {
        assertThat(entries).as("entries for trace %s", traceId).hasSize(6);

        Set<String> ids = new HashSet<>();
        Map<String, Integer> perService = new HashMap<>();
        Map<String, Long> ts = new HashMap<>();
        for (JsonNode e : entries) {
            ids.add(e.get("traceId").asText());
            String service = e.get("serviceName").asText();
            perService.merge(service, 1, Integer::sum);
            String phase = e.get("message").asText().startsWith("Received") ? "received" : "completed";
            assertThat(ts.put(service + ":" + phase, e.get("timestamp").asLong()))
                    .as("duplicate %s %s entry", service, phase).isNull();
        }
        assertThat(ids).containsExactly(traceId);
        assertThat(perService).containsOnlyKeys("api-gateway", "orders-service", "inventory-service")
                .allSatisfy((service, count) -> assertThat(count).as(service).isEqualTo(2));

        String[] causal = {
                "api-gateway:received", "orders-service:received", "inventory-service:received",
                "inventory-service:completed", "orders-service:completed", "api-gateway:completed"};
        for (int i = 1; i < causal.length; i++) {
            assertThat(ts.get(causal[i - 1]))
                    .as("%s (%d) <= %s (%d)", causal[i - 1], ts.get(causal[i - 1]), causal[i], ts.get(causal[i]))
                    .isLessThanOrEqualTo(ts.get(causal[i]));
        }
    }

    @Test
    void oneRequestProducesOneCausallyOrderedTraceAcrossThreeServices() throws Exception {
        HttpResponse<String> res = placeOrder(null);
        assertThat(res.statusCode()).isEqualTo(200);
        String traceId = res.headers().firstValue("X-Trace-Id").orElseThrow();
        assertThat(traceId).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
        assertThat(json.readTree(res.body()).get("orderId").asText()).isNotBlank();

        assertCompleteCausalTrace(traceId, fetchTrace(traceId));
    }

    @Test
    void wellFormedInboundTraceIdIsHonoredEndToEnd() throws Exception {
        String inbound = UUID.randomUUID().toString();
        HttpResponse<String> res = placeOrder(inbound);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().firstValue("X-Trace-Id")).hasValue(inbound);

        assertCompleteCausalTrace(inbound, fetchTrace(inbound));
    }
}
