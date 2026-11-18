# gateway-service

API Gateway da loja (Java 25 / Spring Boot 4.1 / Spring Cloud Gateway WebFlux). Único ponto de entrada público: roteia para os serviços internos, autentica o usuário via `auth-service` e repassa a identidade no header `id-account`.

## Rotas

| Caminho | Serviço | Acesso |
|---|---|---|
| `/auth/**` | `AUTH_URL` (auth) | `register`, `login`, `logout` e `refresh` abertos; demais exigem token |
| `/accounts/**` | `ACCOUNT_URL` (account) | token |
| `/orders/**` | `ORDER_URL` (order) | token |
| `/products/**` | `PRODUCT_URL` (product) | token |
| `/exchanges/**` e `/exchange/**` | `EXCHANGE_URL` (exchange) | token (o enunciado usa os dois prefixos) |
| `GET /gateway/health-check` | o próprio gateway | aberto; 200 sem corpo. As probes de startup e liveness chamam este endpoint |
| `/auth/solve`, `/accounts/login` | — | **404**: só serviço a serviço |

O token vem do cookie `__store_jwt_token` (navegador) ou de `Authorization: Bearer <token>` (scripts, testes de carga). Rotas abertas e internas ficam em `store.gateway.*` no [`application.yaml`](src/main/resources/application.yaml).

## Estado: implementado (17/11/2026)

- **Roteamento:** CORS, métricas e o `GET /gateway/health-check`.
- **Identidade:** remove o `id-account` enviado pelo cliente, então ninguém forja identidade.
- **Rotas:** bloqueia as internas (404) e deixa passar as abertas: `register`, `login`, `logout` e `refresh` do auth.
- **Token:** [`AuthorizationFilter.resolveAccount`](src/main/java/store/gateway/security/AuthorizationFilter.java) chama `POST /auth/solve` com timeout de 2 s e repassa o `idAccount` no header `id-account`.
- **Falha fechada:** token recusado, timeout ou auth fora do ar dão 401, e a requisição nunca chega ao serviço sem identidade.

[`GatewayRoutingTest`](src/test/java/store/gateway/GatewayRoutingTest.java) prova essas garantias com um serviço simulado: 12 testes, nenhum desativado.

## Rodar

```bash
mvn -B -ntp verify
# ou, sem Maven/JDK 25 local:
docker run --rm -v "$PWD":/w -w /w maven:3.9-eclipse-temurin-25 mvn -B -ntp verify

python3 scripts/init-env.py
docker network create store-dev      # uma vez
docker compose up --build -d --wait  # suba os demais serviços na mesma rede
curl -i -X POST localhost:8080/auth/login -H 'Content-Type: application/json' -d '{"email":"a@b.c","password":"x"}'
```

## CI/CD (GitHub Actions)

**verify** em todo push/PR; **deploy** para o ECR `gateway-service` e EKS em push na `main`, quando a variável de repositório `AWS_ROLE_ARN` existe (OIDC, sem chave AWS no GitHub).

### Preparação única no cluster

```bash
kubectl -n store apply -f k8s/deployer-rbac.yaml
cp k8s/config.example.yaml k8s/config.local.yaml && kubectl -n store apply -f k8s/config.local.yaml
```

O Service do gateway é `ClusterIP`. Para acesso público, aplique **à mão** [`k8s/public.example.yaml`](k8s/public.example.yaml), que cria um Network Load Balancer na AWS (custo enquanto existir).

## Uso de IA

O boilerplate deste repositório (estrutura, rotas, filtro de segurança sem a chamada ao auth, testes, Docker/k8s/CI) foi gerado com assistência de IA (Claude) e revisado pela equipe. A resolução do token (`resolveAccount`), a rota aberta de refresh e os testes correspondentes foram implementados por decisão do João (D-01, 17/11/2026), escritas pelo agente do João (Claude) e revisadas pelo João antes do commit.
