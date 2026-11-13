package com.tracer.gateway;

import com.tracer.gateway.testsupport.StubHttpServer;
import com.tracer.gateway.testsupport.StubHttpServer.HangingServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** What a caller of the Gateway sees when a dependency is down, over a real socket. */
class GatewayDependencyDownTest {

    static final String ORDERS_BODY = "{\"orderId\":\"ORD-1\",\"message\":\"from stub\",\"status\":\"created\"}";
    static final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    static HttpResponse<String> placeOrder(int port, Duration timeout) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/orders/place"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"customerId\":\"C-1\",\"items\":\"sku-1\"}"))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class AggregatorConnectionRefused {
        static final StubHttpServer orders = new StubHttpServer().reply("/api/orders", 200, ORDERS_BODY);

        @DynamicPropertySource
        static void urls(DynamicPropertyRegistry r) {
            r.add("orders.service.url", () -> orders.url("/api/orders"));
            r.add("aggregator.url", () -> "http://127.0.0.1:" + StubHttpServer.closedPort() + "/logs");
        }

        @AfterAll
        static void stop() {
            orders.close();
        }

        @LocalServerPort
        int port;

        @Test
        void requestStillSucceedsWithOrdersResponse() throws Exception {
            HttpResponse<String> res = placeOrder(port, Duration.ofSeconds(10));
            assertThat(res.statusCode()).isEqualTo(200);
            assertThat(res.body()).isEqualTo(ORDERS_BODY);
            assertThat(res.headers().firstValue("X-Trace-Id")).isPresent();
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class AggregatorHung {
        static final StubHttpServer orders = new StubHttpServer().reply("/api/orders", 200, ORDERS_BODY);
        static final HangingServer aggregator = new HangingServer();

        @DynamicPropertySource
        static void urls(DynamicPropertyRegistry r) {
            r.add("orders.service.url", () -> orders.url("/api/orders"));
            r.add("aggregator.url", () -> aggregator.url("/logs"));
        }

        @AfterAll
        static void stop() throws Exception {
            aggregator.close(); // releases the request thread still blocked on the aggregator
            orders.close();
        }

        @LocalServerPort
        int port;

        /**
         * Flipped in Phase 2. Before: shipping was synchronous with no read timeout, so this request blocked
         * indefinitely (the test asserted an HttpTimeoutException after 3 s). Now shipping is asynchronous,
         * so a hung aggregator no longer delays the caller.
         */
        @Test
        void requestCompletesPromptlyWhileAggregatorHangs() throws Exception {
            long start = System.nanoTime();
            HttpResponse<String> res = placeOrder(port, Duration.ofSeconds(3));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(res.statusCode()).isEqualTo(200);
            assertThat(res.body()).isEqualTo(ORDERS_BODY);
            assertThat(elapsedMs).isLessThan(1000);
            assertThat(orders.requests("/api/orders")).hasSize(1);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class OrdersUnreachable {
        static final StubHttpServer aggregator = new StubHttpServer();

        @DynamicPropertySource
        static void urls(DynamicPropertyRegistry r) {
            r.add("orders.service.url", () -> "http://127.0.0.1:" + StubHttpServer.closedPort() + "/api/orders");
            r.add("aggregator.url", () -> aggregator.url("/logs"));
        }

        @AfterAll
        static void stop() {
            aggregator.close();
        }

        @LocalServerPort
        int port;

        @Test
        void returns502WithTraceIdHeader() throws Exception {
            HttpResponse<String> res = placeOrder(port, Duration.ofSeconds(10));
            assertThat(res.statusCode()).isEqualTo(502);
            String id = res.headers().firstValue("X-Trace-Id").orElseThrow();
            assertThat(res.body()).contains(id);
        }
    }
}
