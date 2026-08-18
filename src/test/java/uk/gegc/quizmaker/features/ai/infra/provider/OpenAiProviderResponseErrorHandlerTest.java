package uk.gegc.quizmaker.features.ai.infra.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;
import uk.gegc.quizmaker.features.ai.application.AiProviderHttpException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@DisplayName("OpenAI provider HTTP error handler")
class OpenAiProviderResponseErrorHandlerTest {

    private static final Instant NOW = Instant.parse("2026-08-18T10:00:00Z");
    private static final URI REQUEST_URI = URI.create("https://api.openai.com/v1/chat/completions");

    private final OpenAiProviderResponseErrorHandler handler =
            new OpenAiProviderResponseErrorHandler(
                    new ObjectMapper(),
                    Clock.fixed(NOW, ZoneOffset.UTC)
            );

    @Test
    @DisplayName("Classifies a temporary rate limit and retains Retry-After seconds")
    void temporaryRateLimitRetainsRetryAfterSeconds() {
        MockClientHttpResponse response = response(
                429,
                """
                        {"error":{"type":"requests","code":"rate_limit_exceeded"}}
                        """
        );
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, "3");

        AiProviderHttpException failure = handle(response);

        assertThat(failure.statusCode()).isEqualTo(429);
        assertThat(failure.failureKind())
                .isEqualTo(AiProviderHttpException.FailureKind.RATE_LIMIT);
        assertThat(failure.retryAfter()).contains(Duration.ofSeconds(3));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "credit_balance_exhausted",
            "organization_spend_limit_exceeded",
            "project_spend_limit_exceeded",
            "organization_usage_limit_exceeded",
            "insufficient_quota"
    })
    @DisplayName("Treats documented quota and spend-limit codes as terminal")
    void quotaAndSpendLimitCodesAreTerminal(String errorCode) {
        MockClientHttpResponse response = response(
                429,
                "{\"error\":{\"code\":\"" + errorCode + "\"}}"
        );
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, "5");

        AiProviderHttpException failure = handle(response);

        assertThat(failure.failureKind())
                .isEqualTo(AiProviderHttpException.FailureKind.QUOTA_EXHAUSTED);
        assertThat(failure.retryAfter()).isEmpty();
    }

    @Test
    @DisplayName("Treats legacy insufficient_quota error type as terminal")
    void legacyInsufficientQuotaTypeIsTerminal() {
        AiProviderHttpException failure = handle(response(
                429,
                "{\"error\":{\"type\":\"insufficient_quota\"}}"
        ));

        assertThat(failure.failureKind())
                .isEqualTo(AiProviderHttpException.FailureKind.QUOTA_EXHAUSTED);
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {408, 409, 500, 502, 503, 504})
    @DisplayName("Classifies temporary provider statuses as retryable")
    void temporaryStatusesAreRetryable(int statusCode) {
        AiProviderHttpException failure = handle(response(statusCode, "{}"));

        assertThat(failure.failureKind().retryable()).isTrue();
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {400, 401, 403, 404, 422})
    @DisplayName("Classifies non-rate-limit client statuses as terminal")
    void clientStatusesAreTerminal(int statusCode) {
        MockClientHttpResponse response = response(statusCode, "{}");
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, "2");

        AiProviderHttpException failure = handle(response);

        assertThat(failure.failureKind())
                .isEqualTo(AiProviderHttpException.FailureKind.CLIENT_ERROR);
        assertThat(failure.retryAfter()).isEmpty();
    }

    @Test
    @DisplayName("Parses an RFC 1123 Retry-After date against the injected clock")
    void retryAfterDateUsesInjectedClock() {
        MockClientHttpResponse response = response(503, "{}");
        String retryAt = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.ofInstant(NOW.plusSeconds(12), ZoneOffset.UTC)
        );
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, retryAt);

        AiProviderHttpException failure = handle(response);

        assertThat(failure.retryAfter()).contains(Duration.ofSeconds(12));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "not-a-delay",
            "-1",
            "999999999999999999999999999999999999",
            "Tue, 18 Aug 2026 09:59:59 GMT"
    })
    @DisplayName("Ignores malformed, negative, overflowing, and past Retry-After values")
    void invalidRetryAfterIsIgnored(String retryAfter) {
        MockClientHttpResponse response = response(503, "{}");
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, retryAfter);

        AiProviderHttpException failure = handle(response);

        assertThat(failure.retryAfter()).isEmpty();
    }

    @Test
    @DisplayName("Bounds error-body parsing and never exposes provider content")
    void oversizedErrorBodyIsNotRetainedOrExposed() {
        String sensitiveContent = "private-provider-detail";
        String oversizedBody = "{\"error\":{\"code\":\"insufficient_quota\",\"message\":\""
                + sensitiveContent
                + "\"}}"
                + "x".repeat(OpenAiProviderResponseErrorHandler.MAX_ERROR_BODY_BYTES);

        AiProviderHttpException failure = handle(response(429, oversizedBody));

        assertThat(failure.failureKind())
                .isEqualTo(AiProviderHttpException.FailureKind.RATE_LIMIT);
        assertThat(failure.getMessage()).doesNotContain(sensitiveContent);
    }

    @Test
    @DisplayName("Treats malformed error JSON safely without exposing its contents")
    void malformedErrorBodyUsesStatusOnly() {
        String sensitiveContent = "private-provider-detail";

        AiProviderHttpException failure = handle(response(
                429,
                "{not-json:" + sensitiveContent
        ));

        assertThat(failure.failureKind())
                .isEqualTo(AiProviderHttpException.FailureKind.RATE_LIMIT);
        assertThat(failure.getMessage()).doesNotContain(sensitiveContent);
    }

    @Test
    @DisplayName("Reports only HTTP error responses as errors")
    void hasErrorUsesHttpStatusClass() throws Exception {
        assertThat(handler.hasError(response(HttpStatus.OK.value(), "{}"))).isFalse();
        assertThat(handler.hasError(response(HttpStatus.BAD_REQUEST.value(), "{}"))).isTrue();
        assertThat(handler.hasError(response(HttpStatus.INTERNAL_SERVER_ERROR.value(), "{}"))).isTrue();
    }

    private AiProviderHttpException handle(MockClientHttpResponse response) {
        return catchThrowableOfType(
                () -> handler.handleError(REQUEST_URI, HttpMethod.POST, response),
                AiProviderHttpException.class
        );
    }

    private MockClientHttpResponse response(int statusCode, String body) {
        return new MockClientHttpResponse(
                body.getBytes(StandardCharsets.UTF_8),
                statusCode
        );
    }
}
