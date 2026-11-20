package store.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Routes every service to one stub upstream that records the path and the id-account header
 * it received, then checks the gateway's security guarantees end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
class GatewayRoutingTest {

    /** "path id-account" for every request that reached the upstream. */
    static final List<String> received = new CopyOnWriteArrayList<>();
    static HttpServer upstream;

    @Value("${local.server.port}") int port;
    @Value("${local.management.port}") int managementPort;
    final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            // auth-service stub: "bad-token" is rejected (401) like a tampered or expired JWT.
            boolean rejected = path.equals("/auth/solve") && request.contains("bad-token");
            String body = path.equals("/auth/solve") && !rejected ? "{\"idAccount\":\"acc-1\"}" : "{}";
            if (!path.equals("/auth/solve")) {
                received.add(path + " " + exchange.getRequestHeaders().getFirst("id-account"));
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(rejected ? 401 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        upstream.start();
        String url = "http://127.0.0.1:" + upstream.getAddress().getPort();
        for (String service : List.of("AUTH_URL", "ACCOUNT_URL", "ORDER_URL", "PRODUCT_URL", "EXCHANGE_URL", "FRONTEND_URL")) {
            registry.add(service, () -> url);
        }
        registry.add("store.gateway.auth-solve-url", () -> url + "/auth/solve");
    }

    @AfterAll
    static void stop() { upstream.stop(0); }

    @BeforeEach
    void reset() { received.clear(); }

    HttpResponse<String> send(String method, String path, String... headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .method(method, method.equals("GET") ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString("{}"))
            .header("Content-Type", "application/json");
        if (headers.length > 0) builder.headers(headers);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void openRouteIsForwardedWithoutToken() throws Exception {
        assertThat(send("POST", "/auth/login").statusCode()).isEqualTo(200);
        assertThat(received).containsExactly("/auth/login null");
    }

    @Test
    void clientCannotForgeTheIdentityHeader() throws Exception {
        send("POST", "/auth/register", "id-account", "someone-else");
        assertThat(received).containsExactly("/auth/register null");
    }

    @Test
    void frontendIsServedWithoutTokenAndWithoutIdentity() throws Exception {
        assertThat(send("GET", "/app/", "id-account", "someone-else").statusCode()).isEqualTo(200);
        assertThat(send("GET", "/app/assets/index.js").statusCode()).isEqualTo(200);
        assertThat(received).containsExactly("/app/ null", "/app/assets/index.js null");
    }

    @Test
    void rootRedirectsToTheFrontend() throws Exception {
        var response = send("GET", "/");
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).hasValue("/app/");
        assertThat(received).isEmpty();
    }

    @Test
    void frontendRouteOnlyOpensReads() throws Exception {
        assertThat(send("POST", "/app/").statusCode()).isEqualTo(401);
        assertThat(received).isEmpty();
    }

    @Test
    void securedRouteWithoutTokenIs401AndNeverReachesTheService() throws Exception {
        assertThat(send("GET", "/orders").statusCode()).isEqualTo(401);
        assertThat(send("GET", "/accounts/acc-1", "id-account", "acc-1").statusCode()).isEqualTo(401);
        assertThat(received).isEmpty();
    }

    @Test
    void internalRoutesAreNotReachableFromOutside() throws Exception {
        assertThat(send("POST", "/auth/solve").statusCode()).isEqualTo(404);
        assertThat(send("POST", "/accounts/login").statusCode()).isEqualTo(404);
        assertThat(received).isEmpty();
    }

    @Test
    void unknownPathsAre404() throws Exception {
        assertThat(send("GET", "/nope").statusCode()).isEqualTo(404);
    }

    @Test
    void healthCheckAnswers200WithoutTokenAndIsNotRouted() throws Exception {
        var response = send("GET", "/gateway/health-check");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEmpty();
        assertThat(received).isEmpty();
    }

    /** 401 (not 404) proves the path matched a route; without a token nothing reaches the service. */
    @Test
    void exchangeIsRoutedInSingularAndPlural() throws Exception {
        assertThat(send("GET", "/exchanges/USD/EUR").statusCode()).isEqualTo(401);
        assertThat(send("GET", "/exchange/USD/EUR").statusCode()).isEqualTo(401);
        assertThat(send("GET", "/exchangesfoo/USD/EUR").statusCode()).isEqualTo(404);
        assertThat(received).isEmpty();
    }

    @Test
    void tokenRejectedByAuthIs401AndNeverReachesTheService() throws Exception {
        assertThat(send("GET", "/orders", "Authorization", "Bearer bad-token").statusCode()).isEqualTo(401);
        assertThat(send("GET", "/products", "Cookie", "__store_jwt_token=bad-token").statusCode()).isEqualTo(401);
        assertThat(received).isEmpty();
    }

    @Test
    void refreshIsOpenBecauseTheAccessTokenMayHaveExpired() throws Exception {
        assertThat(send("POST", "/auth/refresh").statusCode()).isEqualTo(200);
        assertThat(received).containsExactly("/auth/refresh null");
    }

    @Test
    void forgedIdentityIsReplacedByTheResolvedOne() throws Exception {
        send("GET", "/accounts/acc-1", "Authorization", "Bearer any-token", "id-account", "someone-else");
        assertThat(received).containsExactly("/accounts/acc-1 acc-1");
    }

    @Test
    void readinessProbeIsUpOnManagementPort() throws Exception {
        var response = http.send(HttpRequest.newBuilder(
            URI.create("http://localhost:" + managementPort + "/actuator/health/readiness")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("UP");
    }

    @Test
    void validTokenIsResolvedAndForwardedAsIdAccount() throws Exception {
        assertThat(send("GET", "/orders", "Cookie", "__store_jwt_token=any-token").statusCode()).isEqualTo(200);
        assertThat(send("GET", "/products", "Authorization", "Bearer any-token").statusCode()).isEqualTo(200);
        assertThat(send("GET", "/exchange/USD/EUR", "Authorization", "Bearer any-token").statusCode()).isEqualTo(200);
        assertThat(received).containsExactly("/orders acc-1", "/products acc-1", "/exchange/USD/EUR acc-1");
    }

}
