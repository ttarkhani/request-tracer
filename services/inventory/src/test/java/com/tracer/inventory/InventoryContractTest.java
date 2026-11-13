package com.tracer.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracer.inventory.testsupport.StubHttpServer;
import com.tracer.inventory.testsupport.StubHttpServer.Recorded;
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

/** Contract: Inventory ships its two entries under the inbound X-Trace-Id. */
@SpringBootTest
@AutoConfigureMockMvc
class InventoryContractTest {

    static final String UUID_REGEX = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";
    static final StubHttpServer aggregator = new StubHttpServer();
    static final ObjectMapper json = new ObjectMapper();

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry r) {
        r.add("aggregator.url", () -> aggregator.url("/logs"));
    }

    @AfterAll
    static void stop() {
        aggregator.close();
    }

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void reset() {
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

    private MockHttpServletRequestBuilder check() {
        return post("/api/inventory/check").contentType(MediaType.APPLICATION_JSON).content("{\"items\":\"sku-1\"}");
    }

    @Test
    void shipsTwoEntriesUnderInboundId() throws Exception {
        String id = UUID.randomUUID().toString();
        mvc.perform(check().header("X-Trace-Id", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allInStock").value(true));

        List<JsonNode> shipped = allShipped(2);
        assertThat(shipped).hasSize(2).allSatisfy(e -> {
            assertThat(e.get("traceId").asText()).isEqualTo(id);
            assertThat(e.get("serviceName").asText()).isEqualTo("inventory-service");
        });
        assertThat(shipped).extracting(e -> e.get("message").asText())
                .containsExactlyInAnyOrder("Received inventory check request", "Completed - inventory check finished");
    }

    @Test
    void missingHeaderStartsNewTraceAtThisHop() throws Exception {
        mvc.perform(check()).andExpect(status().isOk());
        assertNewTraceStartedHere();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankHeaderStartsNewTraceAtThisHop(String blank) throws Exception {
        mvc.perform(check().header("X-Trace-Id", blank)).andExpect(status().isOk());
        assertNewTraceStartedHere();
    }

    private void assertNewTraceStartedHere() throws Exception {
        List<JsonNode> shipped = allShipped(2);
        assertThat(shipped).hasSize(2);
        String id = shipped.get(0).get("traceId").asText();
        assertThat(id).matches(UUID_REGEX);
        assertThat(shipped.get(1).get("traceId").asText()).isEqualTo(id);
    }
}
