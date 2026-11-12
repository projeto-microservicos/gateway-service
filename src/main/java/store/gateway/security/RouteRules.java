package store.gateway.security;

import java.util.List;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

/**
 * Classifies a request as internal (blocked), open (no token) or secured (token required),
 * from the "METHOD /path" lists in application.yaml. Anything not listed is secured.
 */
@Component
public class RouteRules {

    private final List<Rule> open;
    private final List<Rule> internal;

    public RouteRules(GatewaySecurityProperties properties) {
        this.open = parse(properties.openRoutes());
        this.internal = parse(properties.internalRoutes());
    }

    public boolean isOpen(ServerHttpRequest request) {
        return open.stream().anyMatch(rule -> rule.matches(request));
    }

    public boolean isInternal(ServerHttpRequest request) {
        return internal.stream().anyMatch(rule -> rule.matches(request));
    }

    private static List<Rule> parse(List<String> entries) {
        return entries == null ? List.of() : entries.stream().map(Rule::parse).toList();
    }

    record Rule(String method, String path) {

        static Rule parse(String entry) {
            String[] parts = entry.trim().split("\\s+");
            if (parts.length != 2 || !parts[1].startsWith("/")) {
                throw new IllegalArgumentException("Expected 'METHOD /path', got: " + entry);
            }
            return new Rule(parts[0].toUpperCase(), parts[1]);
        }

        boolean matches(ServerHttpRequest request) {
            boolean methodMatches = method.equals("ANY") || method.equalsIgnoreCase(request.getMethod().name());
            String requested = request.getPath().pathWithinApplication().value();
            boolean pathMatches;
            if (path.endsWith("/**")) {
                String base = path.substring(0, path.length() - 3);
                pathMatches = requested.equals(base) || requested.startsWith(base + "/");
            } else {
                pathMatches = requested.equals(path) || requested.equals(path + "/");
            }
            return methodMatches && pathMatches;
        }

    }

}
