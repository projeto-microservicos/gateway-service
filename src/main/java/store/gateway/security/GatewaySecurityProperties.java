package store.gateway.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed view of {@code store.gateway.*} in application.yaml. */
@ConfigurationProperties("store.gateway")
public record GatewaySecurityProperties(
    String authSolveUrl,
    String cookieName,
    List<String> openRoutes,
    List<String> internalRoutes
) {}
