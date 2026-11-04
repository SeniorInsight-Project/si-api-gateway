package com.seniorinsight.gateway.predicate;

import java.util.function.Predicate;

import org.springframework.cloud.gateway.handler.predicate.AbstractRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.GatewayPredicate;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

/**
 * Predicado {@code SemInternal}: só deixa a rota casar se nenhum segmento do path for {@code internal}.
 * Os endpoints {@code /internal/**} dos serviços são para chamadas serviço a serviço e nunca podem
 * ser alcançados pelo gateway, seja qual for a reescrita da rota. Sem rota que case, o gateway responde 404.
 *
 * <p>O segmento é comparado já decodificado e sem parâmetros de matriz ({@code %69nternal},
 * {@code internal;x=1} e {@code INTERNAL} também são barrados), porque é assim que o serviço
 * de destino vai interpretá-lo.
 */
@Component
public class SemInternalRoutePredicateFactory
        extends AbstractRoutePredicateFactory<SemInternalRoutePredicateFactory.Config> {

    static final String SEGMENTO_BLOQUEADO = "internal";

    public SemInternalRoutePredicateFactory() {
        super(Config.class);
    }

    @Override
    public Predicate<ServerWebExchange> apply(Config config) {
        return new GatewayPredicate() {
            @Override
            public boolean test(ServerWebExchange exchange) {
                return !contemSegmentoInternal(exchange.getRequest().getPath().pathWithinApplication());
            }

            @Override
            public String toString() {
                return "SemInternal";
            }
        };
    }

    static boolean contemSegmentoInternal(PathContainer path) {
        return path.elements().stream()
                .filter(PathContainer.PathSegment.class::isInstance)
                .map(PathContainer.PathSegment.class::cast)
                .anyMatch(segmento -> SEGMENTO_BLOQUEADO.equalsIgnoreCase(segmento.valueToMatch().trim()));
    }

    public static class Config {
    }
}
