package com.seniorinsight.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * Sobe o gateway de verdade com um MockWebServer fazendo o papel do Keycloak (JWKS de teste)
 * e dos serviços. Cada teste confere o que chegou ao serviço.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class GatewaySecurityTest {

    private static final String ISSUER = "http://keycloak.test/realms/seniorinsight";
    private static final String JWKS_PATH = "/realms/seniorinsight/protocol/openid-connect/certs";
    private static final RSAKey KEY = gerarChave();
    private static final MockWebServer BACKEND = iniciarBackend();

    @Autowired
    WebTestClient client;

    @Autowired
    RouteDefinitionLocator rotas;

    @LocalServerPort
    int porta;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        String base = BACKEND.url("").toString().replaceAll("/$", "");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> base + JWKS_PATH);
        registry.add("IDENTITY_SERVICE_URI", () -> base);
        registry.add("NOTIFICATION_SERVICE_URI", () -> base);
        registry.add("CARE_SERVICE_URI", () -> base);
        registry.add("TELEMETRY_SERVICE_URI", () -> base);
        registry.add("INSIGHT_SERVICE_URI", () -> base);
        registry.add("REPORT_SERVICE_URI", () -> base);
        registry.add("BILLING_SERVICE_URI", () -> base);
    }

    @BeforeEach
    void limparFila() throws InterruptedException {
        while (BACKEND.takeRequest(10, TimeUnit.MILLISECONDS) != null) {
            // descarta requisições de testes anteriores (inclusive JWKS)
        }
    }

    @AfterAll
    static void parar() throws IOException {
        BACKEND.shutdown();
    }

    @Test
    void semTokenRecebe401ENaoChegaAoServico() throws Exception {
        client.get().uri("/api/v1/idosos").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists("X-Correlation-Id");
        client.get().uri("/api/v1/sos").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/identity/me").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/notification/push-tokens").exchange().expectStatus().isUnauthorized();

        assertThat(proximaRequisicaoDeServico()).isNull();
    }

    @Test
    void tokenComChaveOuIssuerErradoRecebe401() throws Exception {
        client.get().uri("/api/v1/idosos")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(gerarChave(), ISSUER))
                .exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/idosos")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(KEY, "http://outro/realms/x"))
                .exchange().expectStatus().isUnauthorized();

        assertThat(proximaRequisicaoDeServico()).isNull();
    }

    @Test
    void comTokenChegaAoCareComCorrelationIdGerado() throws Exception {
        String devolvido = client.get().uri("/api/v1/idosos")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(KEY, ISSUER))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class).getResponseHeaders().getFirst("X-Correlation-Id");

        RecordedRequest recebida = proximaRequisicaoDeServico();
        assertThat(recebida.getPath()).isEqualTo("/api/v1/idosos");
        assertThat(recebida.getHeader("X-Correlation-Id")).isNotBlank().isEqualTo(devolvido);
        assertThat(recebida.getHeader(HttpHeaders.AUTHORIZATION)).startsWith("Bearer ");
    }

    @Test
    void comTokenRepassaCorrelationIdDoClienteAoIdentity() throws Exception {
        client.get().uri("/api/v1/identity/pareamentos/codigos")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(KEY, ISSUER))
                .header("X-Correlation-Id", "abc-123")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Correlation-Id", "abc-123");

        RecordedRequest recebida = proximaRequisicaoDeServico();
        assertThat(recebida.getPath()).isEqualTo("/v1/pareamentos/codigos");
        assertThat(recebida.getHeader("X-Correlation-Id")).isEqualTo("abc-123");
    }

    @Test
    void notificationReescreveParaV1() throws Exception {
        client.get().uri("/api/v1/notification/push-tokens")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(KEY, ISSUER))
                .exchange().expectStatus().isOk();

        assertThat(proximaRequisicaoDeServico().getPath()).isEqualTo("/v1/push-tokens");
    }

    @Test
    void resgateDePareamentoSemTokenChegaAoIdentity() throws Exception {
        client.post().uri("/api/v1/identity/pareamentos/resgates")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .bodyValue("{\"codigo\":\"123456\"}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Correlation-Id");

        RecordedRequest recebida = proximaRequisicaoDeServico();
        assertThat(recebida.getMethod()).isEqualTo("POST");
        assertThat(recebida.getPath()).isEqualTo("/v1/pareamentos/resgates");
        assertThat(recebida.getHeader("X-Correlation-Id")).isNotBlank();
    }

    @Test
    void getNoResgateContinuaExigindoToken() {
        client.get().uri("/api/v1/identity/pareamentos/resgates").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void healthEPreflightSaoPublicos() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
        client.options().uri("/api/v1/idosos")
                .header(HttpHeaders.ORIGIN, "http://localhost:8081")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:8081");
        client.options().uri("/api/v1/idosos")
                .header(HttpHeaders.ORIGIN, "https://www.seniorinsight.com.br")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://www.seniorinsight.com.br");
    }

    @Test
    void raizNaoEhMaisRoteada() throws Exception {
        client.get().uri("/").exchange().expectStatus().isUnauthorized();
        assertThat(proximaRequisicaoDeServico()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "/api/v1/telemetry/idosos/42/hoje, /v1/idosos/42/hoje",
            "/api/v1/insight/idosos/42/score, /v1/idosos/42/score",
            "/api/v1/report/relatorios?semana=2026-W45, /v1/relatorios?semana=2026-W45",
            "/api/v1/billing/assinaturas/minha, /v1/assinaturas/minha"
    })
    void comTokenChegaAosServicosNovosReescritoParaV1(String caminho, String esperado) throws Exception {
        String devolvido = client.get().uri(cru(caminho))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(KEY, ISSUER))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class).getResponseHeaders().getFirst("X-Correlation-Id");

        RecordedRequest recebida = proximaRequisicaoDeServico();
        assertThat(recebida.getPath()).isEqualTo(esperado);
        assertThat(recebida.getHeader(HttpHeaders.AUTHORIZATION)).startsWith("Bearer ");
        assertThat(recebida.getHeader("X-Correlation-Id")).isNotBlank().isEqualTo(devolvido);
    }

    @ParameterizedTest
    @ValueSource(strings = {"telemetry", "insight", "report", "billing"})
    void servicosNovosSemTokenRecebem401ENaoChegamAoServico(String servico) throws Exception {
        client.get().uri("/api/v1/" + servico + "/qualquer").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists("X-Correlation-Id");
        client.post().uri("/api/v1/billing/webhooks/stripe")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .bodyValue("{}")
                .exchange().expectStatus().isUnauthorized();

        assertThat(proximaRequisicaoDeServico()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"telemetry", "insight", "report", "billing"})
    void preflightCorsNosServicosNovos(String servico) {
        client.options().uri("/api/v1/" + servico + "/qualquer")
                .header(HttpHeaders.ORIGIN, "https://www.seniorinsight.com.br")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization,X-Correlation-Id")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://www.seniorinsight.com.br");
        client.options().uri("/api/v1/" + servico + "/qualquer")
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isForbidden();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/internal/idosos/42/vinculo/abc",
            "/api/v1/idosos/internal",
            "/api/v1/identity/internal/usuarios",
            "/api/v1/notification/internal/envios",
            "/api/v1/telemetry/internal/idosos/42",
            "/api/v1/insight/internal/scores",
            "/api/v1/report/internal/relatorios",
            "/api/v1/billing/internal/assinaturas",
            "/api/v1/telemetry/idosos/42/INTERNAL/x"
    })
    void internalComTokenRecebe404ENaoChegaAoServico(String caminho) throws Exception {
        client.get().uri(cru(caminho))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(KEY, ISSUER))
                .exchange()
                .expectStatus().isNotFound();
        client.post().uri(cru(caminho))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(KEY, ISSUER))
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .bodyValue("{}")
                .exchange()
                .expectStatus().isNotFound();

        assertThat(proximaRequisicaoDeServico()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/telemetry/%69nternal/idosos/42",
            "/api/v1/billing/inter%6Eal/assinaturas",
            "/api/v1/report/x/../internal/relatorios",
            "/api/v1/insight/x/%2e%2e/internal/scores",
            "/api/v1/telemetry/internal;v=1/idosos",
            "/api/v1/billing/x%2Finternal/assinaturas"
    })
    void internalDisfarcadoNaoChegaAoServico(String caminho) throws Exception {
        client.get().uri(cru(caminho))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(KEY, ISSUER))
                .exchange()
                .expectStatus().is4xxClientError();

        assertThat(proximaRequisicaoDeServico()).isNull();
    }

    @Test
    void internalForaDeApiV1NaoChegaAoServico() throws Exception {
        client.get().uri("/internal/idosos/42/vinculo/abc").exchange().expectStatus().isUnauthorized();
        client.get().uri("/internal/idosos/42/vinculo/abc")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(KEY, ISSUER))
                .exchange().expectStatus().isForbidden();

        assertThat(proximaRequisicaoDeServico()).isNull();
    }

    @Test
    void segmentoQueSoContemInternalContinuaRoteado() throws Exception {
        client.get().uri("/api/v1/report/internalizacoes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(KEY, ISSUER))
                .exchange().expectStatus().isOk();

        assertThat(proximaRequisicaoDeServico().getPath()).isEqualTo("/v1/internalizacoes");
    }

    @Test
    void todaRotaTemOPredicadoSemInternal() {
        var definicoes = rotas.getRouteDefinitions().collectList().block();

        assertThat(definicoes).extracting(RouteDefinition::getId).contains(
                "identity-service", "notification-service", "care-service",
                "telemetry-service", "insight-service", "report-service", "billing-service");
        assertThat(definicoes).allSatisfy(rota -> assertThat(rota.getPredicates())
                .as("predicados da rota %s", rota.getId())
                .anyMatch(predicado -> "SemInternal".equals(predicado.getName())));
    }

    /** URI absoluta, sem passar pelo template do WebTestClient, para o path chegar ao gateway como está. */
    private URI cru(String caminho) {
        return URI.create("http://localhost:" + porta + caminho);
    }

    private static RecordedRequest proximaRequisicaoDeServico() throws InterruptedException {
        RecordedRequest r;
        do {
            r = BACKEND.takeRequest(500, TimeUnit.MILLISECONDS);
        } while (r != null && JWKS_PATH.equals(r.getPath()));
        return r;
    }

    private static String token(RSAKey chave, String issuer) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject(UUID.randomUUID().toString())
                    .issueTime(new Date())
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(chave.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(chave));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey gerarChave() {
        try {
            return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static MockWebServer iniciarBackend() {
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (JWKS_PATH.equals(request.getPath())) {
                    return new MockResponse().setHeader("Content-Type", "application/json")
                            .setBody(new JWKSet(KEY.toPublicJWK()).toString());
                }
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
