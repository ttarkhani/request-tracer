package com.tracer.orders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracer.orders.testsupport.StubHttpServer;
import com.tracer.orders.testsupport.StubHttpServer.Recorded;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contract: Orders forwards the inbound X-Trace-Id to Inventory and ships its two entries under it. */
@SpringBootTest
@AutoConfigureMockMvc
class OrdersContractTest {

    static final String UUID_REGEX = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";
    static final String REQUEST = "{\"customerId\":\"C-1\",\"items\":\"sku-1,sku-2\"}";
    static final StubHttpServer inventory = new StubHttpServer()
            .reply("/api/inventory/check", 200, "{\"allInStock\":true,\"message\":\"stub\"}");
    static final StubHttpServer aggregator = new StubHttpServer();
    static final ObjectMapper json = new ObjectMapper();

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry r) {
        r.add("inventory.service.url", () -> inventory.url("/api/inventory/check"));
        r.add("aggregator.url", () -> aggregator.url("/logs"));
    }

    @AfterAll
    static void stop() {
        inventory.close();
        aggregator.close();
    }

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void reset() {
        inventory.clear();
        aggregator.clear();
    }

    private List<JsonNode> allShipped(int expected) throws Exception {
        aggregator.await("/logs", r -> true, expected, Duration.ofSeconds(5));
        Thread.sleep(200); // let any unexpected extra entries arrive before asserting "exactly"
        List<JsonNode> out = new ArrayList<>();
        for (Recorded r : aggregator.requests("/logs")) {
            out.add(json.readTree(r.body()));
        }
        return out;
    }

    private MockHttpServletRequestBuilder order() {
        return post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(REQUEST);
    }

    @Test
    void forwardsSameInboundIdAndShipsTwoEntries() throws Exception {
        String id = UUID.randomUUID().toString();
        mvc.perform(order().header("X-Trace-Id", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("created"));

        List<Recorded> toInventory = inventory.requests("/api/inventory/check");
        assertThat(toInventory).hasSize(1);
        assertThat(toInventory.get(0).header("X-Trace-Id")).isEqualTo(id);
        assertThat(json.readTree(toInventory.get(0).body()).get("items").asText()).isEqualTo("sku-1,sku-2");

        List<JsonNode> shipped = allShipped(2);
        assertThat(shipped).hasSize(2).allSatisfy(e -> {
            assertThat(e.get("traceId").asText()).isEqualTo(id);
            assertThat(e.get("serviceName").asText()).isEqualTo("orders-service");
        });
        assertThat(shipped).extracting(e -> e.get("message").asText())
                .containsExactlyInAnyOrder("Received order request", "Completed - response received from Inventory");
    }

    @Test
    void missingHeaderStartsNewTraceAtThisHop() throws Exception {
        mvc.perform(order()).andExpect(status().isOk());
        assertNewTraceStartedHere();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankHeaderStartsNewTraceAtThisHop(String blank) throws Exception {
        mvc.perform(order().header("X-Trace-Id", blank)).andExpect(status().isOk());
        assertNewTraceStartedHere();
    }

    private void assertNewTraceStartedHere() throws Exception {
        String forwarded = inventory.requests("/api/inventory/check").get(0).header("X-Trace-Id");
        assertThat(forwarded).matches(UUID_REGEX);
        List<JsonNode> shipped = allShipped(2);
        assertThat(shipped).hasSize(2)
                .allSatisfy(e -> assertThat(e.get("traceId").asText()).isEqualTo(forwarded));
    }
}
