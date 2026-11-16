package com.seniorinsight.gateway.application;

import java.time.Clock;

import org.springframework.stereotype.Service;

import com.seniorinsight.gateway.application.port.out.RateLimitStore;
import com.seniorinsight.gateway.config.RateLimitProperties;

import reactor.core.publisher.Mono;

/** Applies the per-identity, shared fixed-window quota policy. */
@Service
public class RateLimitService {

    private final RateLimitStore store;
    private final RateLimitProperties properties;
    private final Clock clock;

    public RateLimitService(RateLimitStore store, RateLimitProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    public Mono<Decision> consume(String identity) {
        long now = clock.instant().getEpochSecond();
        long window = Math.floorDiv(now, properties.windowSeconds());
        long resetAt = (window + 1) * properties.windowSeconds();
        int ttlSeconds = Math.toIntExact(resetAt - now);
        String key = "gw:rl:" + identity + ":" + window;
        return store.consume(key, ttlSeconds, properties.requestsPerWindow())
                .map(quota -> new Decision(quota.allowed(), quota.remaining(), properties.requestsPerWindow(),
                        resetAt, quota.ttlSeconds()));
    }

    public record Decision(
            boolean allowed,
            long remaining,
            int limit,
            long resetAtEpochSeconds,
            long retryAfterSeconds) { }
}
