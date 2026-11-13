package com.tracer.aggregator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LogControllerTest {

    @Autowired
    MockMvc mvc;

    private static String entry(String traceIdJson, String service, String message, long ts) {
        return "{\"traceId\":" + traceIdJson + ",\"serviceName\":\"" + service + "\",\"message\":\"" + message
                + "\",\"status\":\"success\",\"timestamp\":" + ts + "}";
    }

    @Test
    void storesValidEntry() throws Exception {
        String id = UUID.randomUUID().toString();
        mvc.perform(post("/logs").contentType(MediaType.APPLICATION_JSON).content(entry("\"" + id + "\"", "svc", "m", 1)))
                .andExpect(status().isOk())
                .andExpect(content().string("stored"));
        mvc.perform(get("/traces/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].traceId").value(id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"\"", "\"   \""})
    void rejectsNullOrBlankTraceIdWith400(String traceIdJson) throws Exception {
        mvc.perform(post("/logs").contentType(MediaType.APPLICATION_JSON).content(entry(traceIdJson, "svc", "m", 1)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMissingTraceIdFieldWith400() throws Exception {
        mvc.perform(post("/logs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceName\":\"svc\",\"message\":\"m\",\"status\":\"success\",\"timestamp\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsEntriesSortedByTimestampNotArrivalOrder() throws Exception {
        String id = UUID.randomUUID().toString();
        for (long ts : new long[]{300, 100, 200}) {
            mvc.perform(post("/logs").contentType(MediaType.APPLICATION_JSON)
                            .content(entry("\"" + id + "\"", "svc", "ts" + ts, ts)))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/traces/" + id))
                .andExpect(jsonPath("$[0].timestamp").value(100))
                .andExpect(jsonPath("$[1].timestamp").value(200))
                .andExpect(jsonPath("$[2].timestamp").value(300));
    }

    @Test
    void statsCountsTracesAndEntries() throws Exception {
        String body = mvc.perform(get("/stats")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode before = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        String id = UUID.randomUUID().toString();
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/logs").contentType(MediaType.APPLICATION_JSON).content(entry("\"" + id + "\"", "svc", "m", i)))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/stats"))
                .andExpect(jsonPath("$.traces").value(before.get("traces").asLong() + 1))
                .andExpect(jsonPath("$.entries").value(before.get("entries").asLong() + 3));
    }

    @Test
    void unknownTraceReturnsEmptyArray() throws Exception {
        mvc.perform(get("/traces/does-not-exist"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }
}
