package com.tracer.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracer.gateway.testsupport.StubHttpServer;
import com.tracer.gateway.testsupport.StubHttpServer.Recorded;
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
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contract: Gateway -> Orders (X-Trace-Id) and Gateway -> aggregator (two entries), against real HTTP stubs. */
@SpringBootTest
@AutoConfigureMockMvc
class GatewayContractTest {

    static final String UUID_REGEX = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";
    static final String ORDERS_BODY = "{\"orderId\":\"ORD-1\",\"message\":\"from stub\",\"status\":\"created\"}";
    static final String REQUEST = "{\"customerId\":\"C-1\",\"items\":\"sku-1\"}";
    static final Duration WAIT = Duration.ofSeconds(5);

    static final StubHttpServer orders = new StubHttpServer().reply("/api/orders", 200, ORDERS_BODY);
    static final StubHttpServer aggregator = new StubHttpServer();
    static final ObjectMapper json = new ObjectMapper();

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry r) {
        r.add("orders.service.url", () -> orders.url("/api/orders"));
        r.add("aggregator.url", () -> aggregator.url("/logs"));
    }

    @AfterAll
    static void stop() {
        orders.close();
        aggregator.close();
    }

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void reset() {
        orders.clear();
        aggregator.clear();
        orders.reply("/api/orders", 200, ORDERS_BODY);
    }

    private List<JsonNode> shippedFor(String traceId, int expected) throws Exception {
        List<Recorded> recs = aggregator.await("/logs", r -> r.body().contains(traceId), expected, WAIT);
        Thread.sleep(200); // let any unexpected extra entries arrive before asserting "exactly"
        recs = aggregator.await("/logs", r -> r.body().contains(traceId), 0, Duration.ZERO);
        List<JsonNode> out = new java.util.ArrayList<>();
        for (Recorded r : recs) {
            out.add(json.readTree(r.body()));
        }
        return out;
    }

    @Test
    void generatesUuidPropagatesItToOrdersShipsTwoEntriesAndPassesResponseThrough() throws Exception {
        MvcResult result = mvc.perform(post("/orders/place").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk())
                .andExpect(content().json(ORDERS_BODY, true))
                .andReturn();

        String returned = result.getResponse().getHeader(TraceIds.HEADER);
        assertThat(returned).matches(UUID_REGEX);

        List<Recorded> toOrders = orders.requests("/api/orders");
        assertThat(toOrders).hasSize(1);
        assertThat(toOrders.get(0).header("X-Trace-Id")).isEqualTo(returned);
        assertThat(json.readTree(toOrders.get(0).body()).get("customerId").asText()).isEqualTo("C-1");

        List<JsonNode> shipped = shippedFor(returned, 2);
        assertThat(shipped).hasSize(2);
        assertThat(shipped).allSatisfy(e -> {
            assertThat(e.get("traceId").asText()).isEqualTo(returned);
            assertThat(e.get("serviceName").asText()).isEqualTo("api-gateway");
        });
        assertThat(shipped).extracting(e -> e.get("message").asText())
                .containsExactlyInAnyOrder("Received order request", "Completed - response received from Orders");
        assertThat(aggregator.requests("/logs")).hasSize(2);
    }

    @Test
    void honorsWellFormedInboundTraceId() throws Exception {
        String inbound = UUID.randomUUID().toString();
        mvc.perform(post("/orders/place").contentType(MediaType.APPLICATION_JSON).content(REQUEST)
                        .header("X-Trace-Id", inbound))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", inbound));
        assertThat(orders.requests("/api/orders").get(0).header("X-Trace-Id")).isEqualTo(inbound);
        assertThat(shippedFor(inbound, 2)).hasSize(2);
    }

    @Test
    void normalizesUpperCaseInboundUuid() throws Exception {
        String inbound = UUID.randomUUID().toString();
        mvc.perform(post("/orders/place").contentType(MediaType.APPLICATION_JSON).content(REQUEST)
                        .header("X-Trace-Id", inbound.toUpperCase()))
                .andExpect(header().string("X-Trace-Id", inbound));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "abc", "1-1-1-1-1", "123e4567e89b12d3a456426614174000",
            "123e4567-e89b-12d3-a456-42661417400", "123e4567-e89b-12d3-a456-4266141740000",
            "{123e4567-e89b-12d3-a456-426614174000}", "123e4567-e89b-12d3-a456-426614174000 x",
            "zzzzzzzz-e89b-12d3-a456-426614174000"})
    void replacesMalformedInboundTraceIdWithFreshUuid(String inbound) throws Exception {
        MvcResult result = mvc.perform(post("/orders/place").contentType(MediaType.APPLICATION_JSON).content(REQUEST)
                        .header("X-Trace-Id", inbound))
                .andExpect(status().isOk())
                .andReturn();
        String returned = result.getResponse().getHeader("X-Trace-Id");
        assertThat(returned).matches(UUID_REGEX).isNotEqualTo(inbound.trim());
        assertThat(orders.requests("/api/orders").get(0).header("X-Trace-Id")).isEqualTo(returned);
    }

    @Test
    void corsExposesTraceIdHeaderToBrowsers() throws Exception {
        mvc.perform(options("/orders/place")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "*"));

        mvc.perform(post("/orders/place").contentType(MediaType.APPLICATION_JSON).content(REQUEST)
                        .header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Expose-Headers", org.hamcrest.Matchers.containsString("X-Trace-Id")));
    }

    @Test
    void ordersErrorStatusIsPassedThroughWithTraceIdHeader() throws Exception {
        orders.reply("/api/orders", 500, "{\"error\":\"boom\"}");
        MvcResult result = mvc.perform(post("/orders/place").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isInternalServerError())
                .andReturn();
        assertThat(result.getResponse().getHeader("X-Trace-Id")).matches(UUID_REGEX);
    }
}
