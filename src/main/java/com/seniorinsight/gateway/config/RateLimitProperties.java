package com.seniorinsight.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway.rate-limit")
public record RateLimitProperties(int requestsPerWindow, int windowSeconds) {
    public RateLimitProperties {
        if (requestsPerWindow < 1 || windowSeconds < 1) {
            throw new IllegalArgumentException("Rate limit and window must be positive");
        }
    }
}
