package store.gateway;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * {@code GET /gateway/health-check}: 200 with an empty body while the gateway is up (Overview.md).
 *
 * <p>Answered by the gateway itself, never routed upstream. Router functions are matched before
 * the gateway routes and global filters, so it needs no token; it is also listed in
 * {@code store.gateway.open-routes} to keep that explicit.
 */
@Configuration(proxyBeanMethods = false)
class HealthCheck {

    @Bean
    RouterFunction<ServerResponse> gatewayHealthCheck() {
        return route(GET("/gateway/health-check"), request -> ServerResponse.ok().build());
    }

}
