# si-api-gateway

API Gateway (Spring Cloud Gateway): ponto único de entrada do app e do portal — validação de JWT, rate limit em Redis e BFF do portal (cookie httpOnly + CSRF).

Parte do SeniorInsight — ver o Documento de Arquitetura de Software (seção 6) para as convenções.

## Rotas públicas (sem JWT)

- `OPTIONS /**` — preflight CORS.
- `GET /actuator/health` (e `/actuator/health/**`) — health check do gateway.
- `POST /api/v1/identity/pareamentos/resgates` — resgate do código de pareamento do idoso (HU-007).
- `POST /api/v1/identity/pareamentos/sessoes/*/renovacoes` — renovação da sessão do aparelho do idoso (HU-007), protegida pelo refresh token no corpo e limitada por IP.

Demais rotas sob `/api/v1/**` exigem JWT do Keycloak.

Branches: `main` (principal, protegida) e `develop` (testes). Trabalhe em branches curtas por issue e abra PR para `develop`.
