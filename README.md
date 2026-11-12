# gateway-service

API Gateway da loja (Java 25 / Spring Boot 4.1 / Spring Cloud Gateway WebFlux). Único ponto de entrada público: roteia para os serviços internos, autentica o usuário via `auth-service` e repassa a identidade no header `id-account`.

## Rotas

| Caminho | Serviço | Acesso |
|---|---|---|
| `/auth/**` | `AUTH_URL` (auth) | `register`, `login`, `logout` abertos; demais exigem token |
| `/accounts/**` | `ACCOUNT_URL` (account) | token |
| `/orders/**` | `ORDER_URL` (order) | token |
| `/products/**` | `PRODUCT_URL` (product) | token |
| `/exchanges/**` | `EXCHANGE_URL` (exchange) | token |
| `/auth/solve`, `/accounts/login` | — | **404**: só serviço a serviço |

O token vem do cookie `__store_jwt_token` (navegador) ou de `Authorization: Bearer <token>` (scripts, testes de carga). Rotas abertas e internas ficam em `store.gateway.*` no [`application.yaml`](src/main/resources/application.yaml).

## Estado: boilerplate

| Pronto | Seu (`TODO(gateway)`) |
|---|---|
| Roteamento, CORS, métricas | [`AuthorizationFilter.resolveAccount`](src/main/java/store/gateway/security/AuthorizationFilter.java): chamar `POST /auth/solve` e devolver o `idAccount` |
| **Remove `id-account` enviado pelo cliente** (ninguém forja identidade) | Timeout/resiliência dessa chamada |
| Bloqueio das rotas internas, rotas abertas, extração do token | |
| Falha fechada: sem `resolveAccount`, toda rota protegida responde 401 | |

[`GatewayRoutingTest`](src/test/java/store/gateway/GatewayRoutingTest.java) prova essas garantias com um serviço simulado; o teste do token válido está com `@Disabled` até você implementar `resolveAccount`.

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

O boilerplate deste repositório (estrutura, rotas, filtro de segurança sem a chamada ao auth, testes, Docker/k8s/CI) foi gerado com assistência de IA (Claude) e revisado pela equipe. A resolução do token (`TODO(gateway)`) é implementada pela equipe. Registre aqui o que cada integrante fez.
