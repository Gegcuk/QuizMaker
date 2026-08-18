package uk.gegc.quizmaker.features.ai.infra.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;
import uk.gegc.quizmaker.features.ai.application.AiProviderHttpException;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static uk.gegc.quizmaker.features.ai.application.AiProviderHttpException.FailureKind.CLIENT_ERROR;
import static uk.gegc.quizmaker.features.ai.application.AiProviderHttpException.FailureKind.CONFLICT;
import static uk.gegc.quizmaker.features.ai.application.AiProviderHttpException.FailureKind.QUOTA_EXHAUSTED;
import static uk.gegc.quizmaker.features.ai.application.AiProviderHttpException.FailureKind.RATE_LIMIT;
import static uk.gegc.quizmaker.features.ai.application.AiProviderHttpException.FailureKind.REQUEST_TIMEOUT;
import static uk.gegc.quizmaker.features.ai.application.AiProviderHttpException.FailureKind.SERVER_ERROR;

/**
 * Converts OpenAI HTTP errors into bounded, safe metadata used by the application retry policy.
 */
public final class OpenAiProviderResponseErrorHandler implements ResponseErrorHandler {

    static final int MAX_ERROR_BODY_BYTES = 16 * 1024;
    private static final int MAX_ERROR_IDENTIFIER_LENGTH = 128;
    private static final Set<String> TERMINAL_QUOTA_CODES = Set.of(
            "credit_balance_exhausted",
            "organization_spend_limit_exceeded",
            "project_spend_limit_exceeded",
            "organization_usage_limit_exceeded",
            "insufficient_quota"
    );

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OpenAiProviderResponseErrorHandler(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public boolean hasError(ClientHttpResponse response) throws IOException {
        return response.getStatusCode().isError();
    }

    @Override
    public void handleError(
            URI url,
            HttpMethod method,
            ClientHttpResponse response
    ) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        ErrorIdentifiers identifiers = readErrorIdentifiers(response);
        AiProviderHttpException.FailureKind failureKind = classify(status.value(), identifiers);
        Duration retryAfter = failureKind.retryable()
                ? parseRetryAfter(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).orElse(null)
                : null;

        throw new AiProviderHttpException(status.value(), failureKind, retryAfter);
    }

    private ErrorIdentifiers readErrorIdentifiers(ClientHttpResponse response) {
        try {
            byte[] bytes = response.getBody().readNBytes(MAX_ERROR_BODY_BYTES + 1);
            if (bytes.length > MAX_ERROR_BODY_BYTES) {
                return ErrorIdentifiers.EMPTY;
            }
            JsonNode error = objectMapper.readTree(bytes).path("error");
            return new ErrorIdentifiers(
                    safeIdentifier(error.path("code")),
                    safeIdentifier(error.path("type"))
            );
        } catch (IOException | RuntimeException ignored) {
            return ErrorIdentifiers.EMPTY;
        }
    }

    private String safeIdentifier(JsonNode node) {
        if (!node.isTextual()) {
            return "";
        }
        String value = node.textValue().trim().toLowerCase(Locale.ROOT);
        if (value.length() > MAX_ERROR_IDENTIFIER_LENGTH
                || !value.matches("[a-z0-9_-]+")) {
            return "";
        }
        return value;
    }

    private AiProviderHttpException.FailureKind classify(
            int statusCode,
            ErrorIdentifiers identifiers
    ) {
        if (statusCode == 429) {
            if (TERMINAL_QUOTA_CODES.contains(identifiers.code())
                    || TERMINAL_QUOTA_CODES.contains(identifiers.type())) {
                return QUOTA_EXHAUSTED;
            }
            return RATE_LIMIT;
        }
        if (statusCode == 408) {
            return REQUEST_TIMEOUT;
        }
        if (statusCode == 409) {
            return CONFLICT;
        }
        if (statusCode >= 500 && statusCode <= 599) {
            return SERVER_ERROR;
        }
        return CLIENT_ERROR;
    }

    private Optional<Duration> parseRetryAfter(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return Optional.empty();
        }

        String value = headerValue.trim();
        if (value.matches("[0-9]+")) {
            try {
                return Optional.of(Duration.ofSeconds(Long.parseLong(value)));
            } catch (NumberFormatException | DateTimeException ignored) {
                return Optional.empty();
            }
        }

        try {
            Instant retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant();
            Instant now = clock.instant();
            if (retryAt.isBefore(now)) {
                return Optional.empty();
            }
            return Optional.of(Duration.between(now, retryAt));
        } catch (DateTimeParseException | ArithmeticException ignored) {
            return Optional.empty();
        }
    }

    private record ErrorIdentifiers(String code, String type) {

        private static final ErrorIdentifiers EMPTY = new ErrorIdentifiers("", "");
    }
}
