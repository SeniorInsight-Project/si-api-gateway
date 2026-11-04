package com.seniorinsight.gateway.filter;

import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * Garante um X-Correlation-Id em toda requisição: reaproveita o do cliente se for válido,
 * senão gera um UUID. O id segue para o serviço de destino e volta na resposta, inclusive em 401.
 * É um WebFilter (e não GlobalFilter) para rodar antes do Spring Security.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter implements WebFilter {

    public static final String HEADER = "X-Correlation-Id";
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String suppliedId = exchange.getRequest().getHeaders().getFirst(HEADER);
        String correlationId = suppliedId != null && VALID_ID.matcher(suppliedId).matches()
                ? suppliedId
                : UUID.randomUUID().toString();

        ServerWebExchange forwardedExchange = exchange.mutate()
                .request(request -> request.headers(headers -> headers.set(HEADER, correlationId)))
                .build();
        forwardedExchange.getResponse().getHeaders().set(HEADER, correlationId);
        return chain.filter(forwardedExchange);
    }
}
