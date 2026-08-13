package com.wallet.web;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Prometheus scrape endpoint, served directly from the meter registry.
 *
 * <p>Actuator could expose the same data, but only by remapping its base path
 * and endpoint id to land on /metrics - two layers of configuration that fail
 * silently with a 404 when either is off. Scraping the registry in three lines
 * is deterministic: the route is an ordinary MVC mapping, and if the Prometheus
 * registry were ever missing from the classpath the application would fail to
 * start rather than quietly stop publishing metrics.
 *
 * <p>Output includes request counts and latency histograms (so p99 is
 * computable) alongside the business counters in WalletMetrics.
 */
@RestController
public class MetricsController {

    private static final String PROMETHEUS_TEXT = "text/plain;version=0.0.4;charset=utf-8";

    private final PrometheusMeterRegistry registry;

    public MetricsController(PrometheusMeterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping(value = "/metrics", produces = PROMETHEUS_TEXT)
    public String scrape() {
        return registry.scrape();
    }
}
