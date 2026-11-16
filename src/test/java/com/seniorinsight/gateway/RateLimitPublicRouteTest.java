package com.seniorinsight.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.seniorinsight.gateway.application.port.out.RateLimitStore;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import reactor.core.publisher.Mono;

/**
 * Garante que a rota pública de renovação de sessão passa pelo RateLimitGlobalFilter
 * usando chave por IP e responde 429 ao exceder a cota.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class RateLimitPublicRouteTest {

    private static final MockWebServer BACKEND = iniciarBackend();
    private static final List<String> CHAVES_CONSUMIDAS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger CONTADOR = new AtomicInteger(0);

    @Autowired
    WebTestClient client;

    @TestConfiguration
    static class StoreEmMemoriaConfig {
        @Bean
        @Primary
        RateLimitStore rateLimitStoreEmMemoria() {
            return (key, ttlSeconds, limit) -> {
                CHAVES_CONSUMIDAS.add(key);
                int atual = CONTADOR.incrementAndGet();
                long remaining = Math.max(limit - atual, 0);
                return Mono.just(new RateLimitStore.Quota(atual <= limit, remaining, Math.max(ttlSeconds, 1)));
            };
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        String base = BACKEND.url("").toString().replaceAll("/$", "");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://keycloak.test/realms/seniorinsight");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> base + "/certs");
        registry.add("gateway.rate-limit.enabled", () -> true);
        registry.add("gateway.rate-limit.requests-per-window", () -> 2);
        registry.add("gateway.rate-limit.window-seconds", () -> 60);
        registry.add("IDENTITY_SERVICE_URI", () -> base);
    }

    @BeforeEach
    void limparEstado() throws InterruptedException {
        CHAVES_CONSUMIDAS.clear();
        CONTADOR.set(0);
        while (BACKEND.takeRequest(10, TimeUnit.MILLISECONDS) != null) {
            // descarta requisições anteriores
        }
    }

    @AfterAll
    static void parar() throws IOException {
        BACKEND.shutdown();
    }

    @Test
    void renovacaoSemTokenPassaPeloRateLimitPorIpEResponde429AoExceder() throws Exception {
        String sessaoId = UUID.randomUUID().toString();
        String caminho = "/api/v1/identity/pareamentos/sessoes/" + sessaoId + "/renovacoes";

        client.post().uri(caminho)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .bodyValue("{\"refreshToken\":\"rt-teste\"}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-RateLimit-Limit", "2")
                .expectHeader().valueEquals("X-RateLimit-Remaining", "1");

        client.post().uri(caminho)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .bodyValue("{\"refreshToken\":\"rt-teste\"}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-RateLimit-Limit", "2")
                .expectHeader().valueEquals("X-RateLimit-Remaining", "0");

        client.post().uri(caminho)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .bodyValue("{\"refreshToken\":\"rt-teste\"}")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER)
                .expectHeader().valueEquals("X-RateLimit-Limit", "2")
                .expectHeader().valueEquals("X-RateLimit-Remaining", "0")
                .expectBody().json("{\"status\":429,\"error\":\"rate_limit_exceeded\"}");

        assertThat(CHAVES_CONSUMIDAS).hasSize(3)
                .allSatisfy(chave -> assertThat(chave).startsWith("gw:rl:ip:"));

        RecordedRequest r1 = BACKEND.takeRequest(500, TimeUnit.MILLISECONDS);
        RecordedRequest r2 = BACKEND.takeRequest(500, TimeUnit.MILLISECONDS);
        RecordedRequest r3 = BACKEND.takeRequest(100, TimeUnit.MILLISECONDS);
        assertThat(r1).isNotNull();
        assertThat(r2).isNotNull();
        assertThat(r3).isNull();
    }

    private static MockWebServer iniciarBackend() {
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setHeader("Content-Type", "application/json").setBody("{}");
            }
        });
        try {
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return server;
    }
}
