package uk.gegc.quizmaker.shared.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PublicApiRoutesTest {

    @ParameterizedTest
    @MethodSource("publicRoutes")
    void isPublic_recognizesRuntimePermitAllRoutes(HttpMethod method, String path) {
        assertThat(PublicApiRoutes.isPublic(method, path)).isTrue();
    }

    @ParameterizedTest
    @MethodSource("protectedRoutes")
    void isPublic_doesNotBroadenProtectedRoutes(HttpMethod method, String path) {
        assertThat(PublicApiRoutes.isPublic(method, path)).isFalse();
    }

    private static Stream<Arguments> publicRoutes() {
        return Stream.of(
                Arguments.of(HttpMethod.POST, "/api/v1/auth/register"),
                Arguments.of(HttpMethod.POST, "/api/v1/auth/login"),
                Arguments.of(HttpMethod.POST, "/api/v1/auth/refresh"),
                Arguments.of(HttpMethod.POST, "/api/v1/auth/forgot-password"),
                Arguments.of(HttpMethod.POST, "/api/v1/auth/reset-password"),
                Arguments.of(HttpMethod.GET, "/oauth2/authorization/google"),
                Arguments.of(HttpMethod.POST, "/api/v1/bug-reports"),
                Arguments.of(HttpMethod.POST, "/api/v1/billing/stripe/webhook"),
                Arguments.of(HttpMethod.POST, "/api/v1/billing/webhooks"),
                Arguments.of(HttpMethod.GET, "/api/v1/billing/config"),
                Arguments.of(HttpMethod.GET, "/api/v1/articles/public"),
                Arguments.of(HttpMethod.GET, "/api/v1/articles/public/slug/retrieval-practice"),
                Arguments.of(HttpMethod.GET, "/api/v1/articles/sitemap"),
                Arguments.of(HttpMethod.GET, "/api/v1/quizzes/export"),
                Arguments.of(HttpMethod.GET, "/api/v1/quizzes/public/quiz-id"),
                Arguments.of(HttpMethod.GET, "/api/v1/quizzes/shared/share-token"),
                Arguments.of(HttpMethod.POST, "/api/v1/quizzes/shared/share-token/attempts"),
                Arguments.of(HttpMethod.GET, "/api/v1/tags/123"),
                Arguments.of(HttpMethod.GET, "/api/v1/categories/123"),
                Arguments.of(HttpMethod.GET, "/api/v1/questions/schemas"),
                Arguments.of(HttpMethod.GET, "/api/v1/questions/schemas/FILL_GAP"),
                Arguments.of(HttpMethod.GET, "/sitemap.xml"),
                Arguments.of(HttpMethod.HEAD, "/robots.txt"),
                Arguments.of(HttpMethod.GET, "/v3/api-docs/articles"),
                Arguments.of(HttpMethod.GET, "/swagger-ui/index.html"),
                Arguments.of(HttpMethod.GET, "/api/v1/api-summary"),
                Arguments.of(HttpMethod.GET, "/api/v1/diagnostic/springdoc-groups"),
                Arguments.of(HttpMethod.GET, "/api/v1/health"),
                Arguments.of(HttpMethod.GET, "/actuator/health/readiness")
        );
    }

    private static Stream<Arguments> protectedRoutes() {
        return Stream.of(
                Arguments.of(HttpMethod.GET, "/api/v1/articles"),
                Arguments.of(HttpMethod.POST, "/api/v1/articles/public"),
                Arguments.of(HttpMethod.POST, "/api/v1/quizzes"),
                Arguments.of(HttpMethod.GET, "/api/v1/quizzes/share-links"),
                Arguments.of(HttpMethod.DELETE, "/api/v1/quizzes/shared/share-token"),
                Arguments.of(HttpMethod.POST, "/api/v1/questions/schemas"),
                Arguments.of(HttpMethod.POST, "/api/v1/tags"),
                Arguments.of(HttpMethod.GET, "/api/documents")
        );
    }
}
