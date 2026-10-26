# si-api-gateway

Gateway HTTP do SeniorInsight, implementado com Java 21, Spring Boot WebFlux e Spring Cloud Gateway. É a entrada externa para as APIs e encaminha as requisições para os serviços da rede Docker.

## Rotas locais

| Entrada | Serviço de destino | Caminho encaminhado |
|---|---|---|
| `/api/v1/identity/**` | `identity-service:8080` | `/v1/**` |
| `/api/v1/notifications/**` | `notification-service:8080` | `/v1/**` |
| `/api/v1/**` | `backend-core:8080` | preservado |
| `/` | `backend-core:8080` | preservado |
| `/actuator/health` | próprio gateway | health do gateway |

As rotas de `identity-service` e `notification-service` exigem JWT OIDC do Keycloak. O gateway valida assinatura, issuer, `exp` e `nbf` nesses caminhos, e encaminha o bearer token sem modificá-lo. Os endpoints antigos de autenticação do `backend-core` continuam públicos para preservar o fluxo existente; as outras rotas do backend são protegidas pelo filtro HMAC já implementado nele.

A URI interna do serviço de notificações é configurada por `NOTIFICATION_SERVICE_URI` (padrão local: `http://localhost:8083`; no Compose: `http://notification-service:8080`). A rota encaminha `/api/v1/notifications/{recurso}` como `/v1/{recurso}`. O esqueleto atual do serviço ainda não possui controllers HTTP; os endpoints precisam ser implementados nele para responder além de `404`.

Essa divisão é transitória: antes de migrar o `backend-core` para tokens Keycloak, não se deve impor o resource server Keycloak a todas as rotas, pois os JWTs HMAC emitidos hoje pelo backend são incompatíveis. A etapa de migração deve substituir emissão e validação de tokens de forma coordenada com os clientes e então aplicar autenticação Keycloak no gateway para todas as rotas protegidas.

## Executar

O Compose do [si-infra](../si-infra-main/README.md) constrói e inicia este serviço. A porta local padrão é `8080`, configurável com `GATEWAY_PORT`. As URLs internas e o issuer Keycloak são configurados por variáveis de ambiente no Compose.

O gateway adiciona ou propaga `X-Correlation-Id`, limita o valor recebido a 64 caracteres alfanuméricos e o devolve na resposta. CORS aceita por padrão `http://localhost:3000` e `http://localhost:5173`, configurável por `GATEWAY_CORS_ALLOWED_ORIGINS`. CSRF está desativado para a API stateless com bearer token; o BFF com cookie e CSRF ainda não faz parte desta implementação.

## Stack

- Java 21
- Spring Boot 3.5.16
- Spring Cloud 2025.0.3 (Gateway 4.3.5)
- Spring Security Resource Server com JWT

O Spring Cloud 2025.0.x é a linha compatível com Spring Boot 3.5; a última versão open source dessa linha encerrou suporte upstream em 30/06/2026. A versão foi escolhida para manter compatibilidade com os serviços atuais em Spring Boot 3.5. Planejar atualização coordenada para Boot 4/Spring Cloud 2025.1 em uma mudança separada.

Branches: `main` (principal, protegida) e `develop` (testes). Trabalhe em branches curtas por issue e abra PR para `develop`.
