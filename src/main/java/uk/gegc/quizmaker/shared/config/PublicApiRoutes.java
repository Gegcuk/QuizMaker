package uk.gegc.quizmaker.shared.config;

import org.springframework.http.HttpMethod;
import org.springframework.lang.Nullable;
import org.springframework.util.AntPathMatcher;

import java.util.List;

/**
 * Single source of truth for application routes intentionally available without a bearer token.
 *
 * <p>SecurityConfig uses this registry for runtime authorization and
 * {@link PublicApiOpenApiCustomizer} uses it to publish the same access policy in OpenAPI.
 * Keep a new public route here instead of adding a standalone {@code permitAll()} matcher.</p>
 */
public final class PublicApiRoutes {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private static final List<PublicApiRoute> ROUTES = List.of(
            anyMethod("/api/v1/auth/register"),
            anyMethod("/api/v1/auth/login"),
            anyMethod("/api/v1/auth/refresh"),
            anyMethod("/api/v1/auth/forgot-password"),
            anyMethod("/api/v1/auth/reset-password"),
            anyMethod("/api/v1/auth/2fa/setup"),
            anyMethod("/api/v1/auth/2fa/verify"),
            anyMethod("/oauth2/**"),
            anyMethod("/login/oauth2/**"),
            route(HttpMethod.POST, "/api/v1/bug-reports"),
            route(HttpMethod.POST, "/api/v1/billing/stripe/webhook"),
            route(HttpMethod.POST, "/api/v1/billing/webhooks"),
            route(HttpMethod.GET, "/api/v1/billing/config"),
            route(HttpMethod.GET, "/api/v1/articles/public/**"),
            route(HttpMethod.GET, "/api/v1/articles/sitemap"),
            route(HttpMethod.GET, "/api/v1/quizzes/export"),
            route(HttpMethod.GET, "/api/v1/quizzes/public/**"),
            route(HttpMethod.GET, "/api/v1/quizzes/shared/**"),
            route(HttpMethod.POST, "/api/v1/quizzes/shared/**"),
            route(HttpMethod.GET, "/api/v1/tags/**"),
            route(HttpMethod.GET, "/api/v1/categories/**"),
            route(HttpMethod.GET, "/api/v1/questions/schemas"),
            route(HttpMethod.GET, "/api/v1/questions/schemas/**"),
            route(HttpMethod.GET, "/sitemap.xml"),
            route(HttpMethod.GET, "/sitemap_articles.xml"),
            route(HttpMethod.GET, "/robots.txt"),
            route(HttpMethod.HEAD, "/sitemap.xml"),
            route(HttpMethod.HEAD, "/sitemap_articles.xml"),
            route(HttpMethod.HEAD, "/robots.txt"),
            route(HttpMethod.GET, "/v3/api-docs/**"),
            route(HttpMethod.GET, "/swagger-ui/**"),
            route(HttpMethod.GET, "/api/v1/docs/**"),
            route(HttpMethod.GET, "/api/v1/api-docs/**"),
            route(HttpMethod.GET, "/api/v1/api-summary"),
            route(HttpMethod.GET, "/api/v1/diagnostic/**"),
            route(HttpMethod.GET, "/api/v1/health"),
            route(HttpMethod.GET, "/actuator/health"),
            route(HttpMethod.GET, "/actuator/health/**")
    );

    private PublicApiRoutes() {
    }

    public static String[] anyMethodPatterns() {
        return patternsFor(null);
    }

    public static String[] patternsFor(HttpMethod method) {
        return ROUTES.stream()
                .filter(route -> route.method() == method)
                .map(PublicApiRoute::pattern)
                .toArray(String[]::new);
    }

    public static boolean isPublic(HttpMethod method, String path) {
        return ROUTES.stream().anyMatch(route -> route.matches(method, path));
    }

    private static PublicApiRoute anyMethod(String pattern) {
        return new PublicApiRoute(null, pattern);
    }

    private static PublicApiRoute route(HttpMethod method, String pattern) {
        return new PublicApiRoute(method, pattern);
    }

    private record PublicApiRoute(@Nullable HttpMethod method, String pattern) {

        private boolean matches(HttpMethod requestMethod, String path) {
            return (method == null || method == requestMethod)
                    && PATH_MATCHER.match(pattern, path);
        }
    }
}
