package com.wallet.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.auth.AuthFilter;
import com.wallet.auth.CallerArgumentResolver;
import com.wallet.auth.JwtService;
import com.wallet.metrics.WalletMetrics;
import com.wallet.web.CorrelationIdFilter;
import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Filter order matters: correlation id first (so even auth failures are
 * traceable), then authentication. Filters are registered here explicitly,
 * not component-scanned, to keep the order deliberate and visible.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration =
                new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(0);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    public FilterRegistrationBean<AuthFilter> authFilter(JwtService jwtService,
                                                         ObjectMapper objectMapper,
                                                         WalletMetrics metrics) {
        FilterRegistrationBean<AuthFilter> registration =
                new FilterRegistrationBean<>(new AuthFilter(jwtService, objectMapper, metrics));
        registration.setOrder(1);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CallerArgumentResolver());
    }
}
