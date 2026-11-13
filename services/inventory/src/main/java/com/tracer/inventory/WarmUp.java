package com.tracer.inventory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Cold-start mitigation: once the app is ready, send a few real HTTP requests to this service itself so
 * Tomcat, the DispatcherServlet, Jackson and the HTTP client load their classes before the first user request
 * (profiling showed ~460-850 classes loaded lazily on the first request; see bench/RESULTS.md, Phase 3).
 * It does not call other services and ships no trace logs. Logs "Warm-up completed" when done.
 */
@Component
public class WarmUp {

    private final boolean enabled;
    private final String path;
    private final int requests;

    public WarmUp(@Value("${warmup.enabled:true}") boolean enabled,
                  @Value("${warmup.path:/actuator/health}") String path,
                  @Value("${warmup.requests:3}") int requests) {
        this.enabled = enabled;
        this.path = path;
        this.requests = requests;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp(ApplicationReadyEvent event) {
        if (!enabled || !(event.getApplicationContext() instanceof WebServerApplicationContext web)) {
            return;
        }
        long start = System.nanoTime();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(5000);
        RestTemplate client = new RestTemplate(factory);
        String url = "http://localhost:" + web.getWebServer().getPort() + path;
        int ok = 0;
        for (int i = 0; i < requests; i++) {
            try {
                client.getForObject(url, String.class);
                ok++;
            } catch (Exception e) {
                System.out.println("[WARM-UP] request failed: " + e.getMessage());
            }
        }
        System.out.println("[WARM-UP] Warm-up completed in " + (System.nanoTime() - start) / 1_000_000
                + " ms (" + ok + "/" + requests + " ok, GET " + path + ")");
    }
}
