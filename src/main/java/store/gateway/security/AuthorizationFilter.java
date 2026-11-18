package store.gateway.security;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Runs before every routed request:
 * <ol>
 *   <li>removes any client-supplied {@code id-account} header (downstream services trust it);</li>
 *   <li>blocks internal routes with 404;</li>
 *   <li>lets open routes through without a token;</li>
 *   <li>for everything else, resolves the token into an account id and forwards it as
 *       {@code id-account}, or answers 401.</li>
 * </ol>
 * Step 4 calls auth-service ({@code POST /auth/solve}) with a short timeout and fails closed: any
 * error, timeout or non-200 answer is 401, never a request let through without identity.
 */
@Component
public class AuthorizationFilter implements GlobalFilter, Ordered {

    public static final String ID_ACCOUNT = "id-account";
    /** auth-service only checks a signature; anything slower than this is treated as unavailable. */
    static final Duration SOLVE_TIMEOUT = Duration.ofSeconds(2);
    private static final Logger log = LoggerFactory.getLogger(AuthorizationFilter.class);

    private final RouteRules rules;
    private final GatewaySecurityProperties properties;
    private final WebClient auth;

    public AuthorizationFilter(RouteRules rules, GatewaySecurityProperties properties, WebClient.Builder webClient) {
        this.rules = rules;
        this.properties = properties;
        this.auth = webClient.build();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        ServerWebExchange clean = exchange.mutate()
            .request(builder -> builder.headers(headers -> headers.remove(ID_ACCOUNT)))
            .build();

        if (rules.isInternal(request)) {
            return reject(clean, HttpStatus.NOT_FOUND);
        }
        if (rules.isOpen(request)) {
            return chain.filter(clean);
        }
        Optional<String> token = extractToken(request);
        if (token.isEmpty()) {
            return reject(clean, HttpStatus.UNAUTHORIZED);
        }
        return resolveAccount(token.get())
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty())
            .flatMap(idAccount -> idAccount
                .map(id -> chain.filter(withAccount(clean, id)))
                .orElseGet(() -> reject(clean, HttpStatus.UNAUTHORIZED)));
    }

    /** Turns a token into the account id, or empty when the token is not valid (or auth-service fails). */
    Mono<String> resolveAccount(String token) {
        return auth.post()
            .uri(properties.authSolveUrl())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("token", token))
            .retrieve()
            .bodyToMono(SolvedToken.class)
            .timeout(SOLVE_TIMEOUT)
            .mapNotNull(SolvedToken::idAccount)
            .filter(id -> !id.isBlank())
            .onErrorResume(error -> {
                // 401 is the normal "bad token" answer; anything else means auth-service is in trouble.
                if (!(error instanceof WebClientResponseException.Unauthorized)) {
                    log.warn("auth-service could not resolve a token ({})", error.getClass().getSimpleName());
                }
                return Mono.empty();
            });
    }

    /** Body of auth-service's {@code /auth/solve} answer. */
    record SolvedToken(String idAccount) {}

    /** The cookie set by auth-service, or an "Authorization: Bearer ..." header (scripts, load tests). */
    Optional<String> extractToken(ServerHttpRequest request) {
        HttpCookie cookie = request.getCookies().getFirst(properties.cookieName());
        if (cookie != null && !cookie.getValue().isBlank()) {
            return Optional.of(cookie.getValue().trim());
        }
        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
            && authorization.length() > 7) {
            return Optional.of(authorization.substring(7).trim());
        }
        return Optional.empty();
    }

    private static ServerWebExchange withAccount(ServerWebExchange exchange, String idAccount) {
        return exchange.mutate().request(builder -> builder.header(ID_ACCOUNT, idAccount)).build();
    }

    private static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }

    /** Before the routing filters, so the forwarded request already carries the identity. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

}
