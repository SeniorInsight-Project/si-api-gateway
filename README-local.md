# si-api-gateway

Gateway HTTP do SeniorInsight, implementado com Java 21, Spring Boot WebFlux e Spring Cloud Gateway. É a entrada externa para as APIs e encaminha as requisições para os serviços da rede Docker.

## Rotas locais

| Entrada | Serviço de destino | Caminho encaminhado |
|---|---|---|
| `/api/v1/identity/**` | `identity-service:8080` | `/v1/**` |
| `/api/v1/notification/**` | `notification-service:8080` | `/v1/**` |
| `/api/v1/telemetry/**` | `telemetry-service:8080` (`TELEMETRY_SERVICE_URI`) | `/v1/**` |
| `/api/v1/insight/**` | `insight-service:8080` (`INSIGHT_SERVICE_URI`) | `/v1/**` |
| `/api/v1/report/**` | `report-service:8080` (`REPORT_SERVICE_URI`) | `/v1/**` |
| `/api/v1/billing/**` | `billing-service:8080` (`BILLING_SERVICE_URI`) | `/v1/**` |
| `/api/v1/**` | `backend-core:8080` | preservado |
| `/actuator/health` | próprio gateway | health do gateway |

Nenhum endpoint `/internal/**` dos serviços é alcançável pelo gateway: toda rota tem o predicado `SemInternal`, que não casa se algum segmento do path for `internal` (inclusive codificado ou em maiúsculas), e a requisição recebe `404` sem chegar ao serviço. Rota nova precisa do predicado; o `GatewaySecurityTest` falha se faltar.

As rotas de `identity-service` e `notification-service` exigem JWT OIDC do Keycloak. O gateway valida assinatura, issuer, `exp` e `nbf` nesses caminhos, e encaminha o bearer token sem modificá-lo. Os endpoints antigos de autenticação do `backend-core` continuam públicos para preservar o fluxo existente; as outras rotas do backend são protegidas pelo filtro HMAC já implementado nele.

A URI interna do serviço de notificações é configurada por `NOTIFICATION_SERVICE_URI` (padrão local: `http://localhost:8083`; no Compose: `http://notification-service:8080`). A rota encaminha `/api/v1/notifications/{recurso}` como `/v1/{recurso}`. O esqueleto atual do serviço ainda não possui controllers HTTP; os endpoints precisam ser implementados nele para responder além de `404`.

Essa divisão é transitória: antes de migrar o `backend-core` para tokens Keycloak, não se deve impor o resource server Keycloak a todas as rotas, pois os JWTs HMAC emitidos hoje pelo backend são incompatíveis. A etapa de migração deve substituir emissão e validação de tokens de forma coordenada com os clientes e então aplicar autenticação Keycloak no gateway para todas as rotas protegidas.

## Executar

O Compose do [si-infra](../si-infra-main/README.md) constrói e inicia este serviço. A porta local padrão é `8080`, configurável com `GATEWAY_PORT`. As URLs internas e o issuer Keycloak são configurados por variáveis de ambiente no Compose.

O gateway adiciona ou propaga `X-Correlation-Id`, limita o valor recebido a 64 caracteres alfanuméricos e o devolve na resposta. CORS aceita por padrão `http://localhost:3000` e `http://localhost:5173`, configurável por `GATEWAY_CORS_ALLOWED_ORIGINS`. CSRF está desativado para a API stateless com bearer token; o BFF com cookie e CSRF ainda não faz parte desta implementação.

Todas as rotas `/api/v1/**` passam pelo filtro global de rate limit do Spring Cloud Gateway e usam Redis para compartilhar cotas entre réplicas. A chave é `gw:rl:{usuario}:{janela}` (identidade do `sub` validado no JWT); para o resgate público de pareamento, a identidade é o IP da conexão. `X-Forwarded-For` não é usado, pois só é confiável quando o proxy de entrada está configurado como confiável. O padrão é 600 requisições por janela de 60 segundos, configurável por `GATEWAY_RATE_LIMIT_REQUESTS_PER_WINDOW` e `GATEWAY_RATE_LIMIT_WINDOW_SECONDS`. Preflight `OPTIONS` não consome cota. Ao exceder, o Gateway responde `429`, `Retry-After` e os headers `X-RateLimit-Limit`, `X-RateLimit-Remaining` e `X-RateLimit-Reset`. Como toda janela fixa, o limite pode permitir rajadas na fronteira entre janelas; ajuste a duração e o total conforme os padrões observados.

O filtro registra `gateway.rate_limit.requests` com os resultados `allowed`, `limited` e `redis_error`; não inclui usuário nem path nas tags. Os timers nativos de rota permanecem ativos e a tag de path continua desativada para controlar cardinalidade e evitar IDs em URLs. O Actuator expõe somente health/info; para coletar métricas, configure um endpoint de scrape protegido na infraestrutura. Se Redis falhar, o filtro falha fechado com `503` e `Retry-After: 1`, e incrementa `redis_error`.

## Stack

- Java 21
- Spring Boot 3.5.16
- Spring Cloud 2025.0.3 (Gateway 4.3.5)
- Spring Security Resource Server com JWT

O Spring Cloud 2025.0.x é a linha compatível com Spring Boot 3.5; a última versão open source dessa linha encerrou suporte upstream em 30/06/2026. A versão foi escolhida para manter compatibilidade com os serviços atuais em Spring Boot 3.5. Planejar atualização coordenada para Boot 4/Spring Cloud 2025.1 em uma mudança separada.

Branches: `main` (principal, protegida) e `develop` (testes). Trabalhe em branches curtas por issue e abra PR para `develop`.
