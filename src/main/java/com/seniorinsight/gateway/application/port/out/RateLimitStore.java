package com.seniorinsight.gateway.application.port.out;

import reactor.core.publisher.Mono;

/** Atomic counter storage used by the gateway rate-limit use case. */
public interface RateLimitStore {

    Mono<Quota> consume(String key, int ttlSeconds, int limit);

    record Quota(boolean allowed, long remaining, long ttlSeconds) { }
}
