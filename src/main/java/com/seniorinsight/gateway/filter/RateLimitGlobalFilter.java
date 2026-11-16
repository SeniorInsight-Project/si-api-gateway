package com.seniorinsight.gateway.filter;

import java.nio.charset.StandardCharsets;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.seniorinsight.gateway.application.RateLimitService;
import com.seniorinsight.gateway.application.RateLimitService.Decision;

import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Mono;

/** Applies rate limits at the gateway edge, after authentication and before backend routing. */
@Component
@ConditionalOnProperty(prefix = "gateway.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitGlobalFilter implements GlobalFilter, Ordered {

    private final RateLimitService rateLimitService;
    private final MeterRegistry metrics;

    public RateLimitGlobalFilter(RateLimitService rateLimitService, MeterRegistry metrics) {
        this.rateLimitService = rateLimitService;
        this.metrics = metrics;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!exchange.getRequest().getPath().value().startsWith("/api/v1/")
                || HttpMethod.OPTIONS.equals(exchange.getRequest().getMethod())) {
            return chain.filter(exchange);
        }

        return exchange.getPrincipal()
                .map(java.security.Principal::getName)
                .defaultIfEmpty("ip:" + remoteAddress(exchange))
                .flatMap(identity -> rateLimitService.consume(identity)
                        .onErrorResume(error -> {
                            metrics.counter("gateway.rate_limit.requests", "outcome", "redis_error").increment();
                            exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                            exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, "1");
                            return Mono.empty();
                        })
                        .flatMap(decision -> handleDecision(exchange, chain, decision)))
                .switchIfEmpty(Mono.defer(() -> exchange.getResponse().setComplete()));
    }

    private Mono<Void> handleDecision(
            ServerWebExchange exchange,
            GatewayFilterChain chain,
            Decision decision) {
        addRateLimitHeaders(exchange.getResponse().getHeaders(), decision);
        if (decision.allowed()) {
            metrics.counter("gateway.rate_limit.requests", "outcome", "allowed").increment();
            return chain.filter(exchange);
        }

        metrics.counter("gateway.rate_limit.requests", "outcome", "limited").increment();
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
        byte[] body = "{\"status\":429,\"error\":\"rate_limit_exceeded\"}"
                .getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    private void addRateLimitHeaders(HttpHeaders headers, Decision decision) {
        headers.set("X-RateLimit-Limit", Integer.toString(decision.limit()));
        headers.set("X-RateLimit-Remaining", Long.toString(decision.remaining()));
        headers.set("X-RateLimit-Reset", Long.toString(decision.resetAtEpochSeconds()));
    }

    private static String remoteAddress(ServerWebExchange exchange) {
        var address = exchange.getRequest().getRemoteAddress();
        return address == null ? "unknown" : address.getHostString();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
