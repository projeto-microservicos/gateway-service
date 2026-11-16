# gateway-service

API Gateway da loja (Java 25 / Spring Boot 4.1 / Spring Cloud Gateway WebFlux). Único ponto de entrada público: roteia para os serviços internos, autentica o usuário via `auth-service` e repassa a identidade no header `id-account`.

## Rotas

| Caminho | Serviço | Acesso |
|---|---|---|
| `/auth/**` | `AUTH_URL` (auth) | `register`, `login`, `logout` abertos; demais exigem token |
| `/accounts/**` | — | **404**: somente serviço a serviço |
| `/orders/**` | `ORDER_URL` (order) | token |
| `/products/**` | `PRODUCT_URL` (product) | token |
| `/exchanges/**` | `EXCHANGE_URL` (exchange) | token |
| `/auth/solve` | — | **404**: só serviço a serviço |

O token vem do cookie `__store_jwt_token` (navegador) ou de `Authorization: Bearer <token>` (scripts, testes de carga). Rotas abertas e internas ficam em `store.gateway.*` no [`application.yaml`](src/main/resources/application.yaml).

## Estado da implementação local

O filtro consulta `/auth/solve` por WebClient com timeout de 2 segundos e falha com 401 se a validação não for concluída. Remove identidades enviadas pelo cliente e só encaminha a conta resolvida. `/accounts/**` e `/auth/solve` retornam 404 externamente. Todos os testes de roteamento estão ativos.

A validação integrada usa o Compose da raiz do agregador. Essa alteração local ainda precisa ser publicada no repositório do serviço antes de atualizar o gitlink.

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

O Service do gateway é `ClusterIP`. Nesta etapa, use port-forward para o teste funcional; o exemplo de Load Balancer cria um recurso pago e não configura HTTPS.

## Uso de IA

O boilerplate deste repositório (estrutura, rotas, filtro de segurança sem a chamada ao auth, testes, Docker/k8s/CI) foi gerado com assistência de IA (Claude) e revisado pela equipe. A resolução do token (`TODO(gateway)`) é implementada pela equipe. Registre aqui o que cada integrante fez.

A implementação funcional e a ativação dos testes nesta revisão foram feitas com assistência do Codex em 16/11/2026. As referências a TODO no histórico de geração acima descrevem o boilerplate original.
