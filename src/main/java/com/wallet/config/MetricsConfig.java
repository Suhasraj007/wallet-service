package com.wallet.config;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the Prometheus registry as an ordinary bean instead of relying on
 * Boot's export auto-configuration.
 *
 * <p>The auto-configured registry exists only while metrics export is enabled,
 * and Spring Boot switches export off inside every @SpringBootTest - which
 * would leave MetricsController's constructor unsatisfiable and fail the whole
 * context. An explicit bean is unconditional: /metrics serves real Prometheus
 * output in production and in the integration gate alike, and Boot's
 * auto-configuration backs off on its own @ConditionalOnMissingBean.
 *
 * <p>Boot still treats this registry like any other MeterRegistry bean:
 * meter filters from application.yml (the http.server.requests percentile
 * histograms) and the built-in binders are applied to it unchanged.
 */
@Configuration
public class MetricsConfig {

    @Bean
    public PrometheusMeterRegistry prometheusMeterRegistry() {
        return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }
}
