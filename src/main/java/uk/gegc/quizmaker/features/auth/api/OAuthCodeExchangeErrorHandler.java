package uk.gegc.quizmaker.features.auth.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import uk.gegc.quizmaker.features.auth.domain.exception.OAuthExchangeRejectedException;
import uk.gegc.quizmaker.features.auth.domain.exception.OAuthExchangeRequestException;
import uk.gegc.quizmaker.features.auth.domain.exception.OAuthExchangeStoreUnavailableException;
import uk.gegc.quizmaker.features.auth.domain.model.OAuthExchangeRejectionReason;
import uk.gegc.quizmaker.shared.api.problem.ErrorTypes;
import uk.gegc.quizmaker.shared.api.problem.ProblemDetailBuilder;
import uk.gegc.quizmaker.shared.exception.RateLimitExceededException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = OAuthCodeExchangeController.class)
public class OAuthCodeExchangeErrorHandler extends ResponseEntityExceptionHandler {

    private static final String INVALID_REQUEST_DETAIL = "OAuth exchange request is not valid.";
    private static final String INVALID_CODE_DETAIL = "OAuth sign-in code is invalid or expired. Restart sign-in.";

    @ExceptionHandler(OAuthExchangeRequestException.class)
    public ResponseEntity<ProblemDetail> handleInvalidRequest(
            OAuthExchangeRequestException exception,
            HttpServletRequest request
    ) {
        return noStore(HttpStatus.BAD_REQUEST).body(ProblemDetailBuilder.create(
                HttpStatus.BAD_REQUEST,
                ErrorTypes.OAUTH_EXCHANGE_INVALID_REQUEST,
                "Invalid OAuth Exchange Request",
                INVALID_REQUEST_DETAIL,
                request
        ));
    }

    @ExceptionHandler(OAuthExchangeRejectedException.class)
    public ResponseEntity<ProblemDetail> handleRejectedCode(
            OAuthExchangeRejectedException exception,
            HttpServletRequest request
    ) {
        if (exception.getReason() == OAuthExchangeRejectionReason.REPLAYED) {
            return noStore(HttpStatus.CONFLICT).body(ProblemDetailBuilder.create(
                    HttpStatus.CONFLICT,
                    ErrorTypes.OAUTH_EXCHANGE_REPLAYED,
                    "OAuth Sign-In Code Already Used",
                    "OAuth sign-in code has already been used. Restart sign-in.",
                    request
            ));
        }

        return noStore(HttpStatus.UNAUTHORIZED).body(ProblemDetailBuilder.create(
                HttpStatus.UNAUTHORIZED,
                ErrorTypes.OAUTH_EXCHANGE_INVALID,
                "Invalid OAuth Sign-In Code",
                INVALID_CODE_DETAIL,
                request
        ));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimit(
            RateLimitExceededException exception,
            HttpServletRequest request
    ) {
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.TOO_MANY_REQUESTS,
                ErrorTypes.RATE_LIMIT_EXCEEDED,
                "OAuth Exchange Rate Limit Exceeded",
                "Too many OAuth exchange attempts. Please retry later.",
                request
        );
        problem.setProperty("retryAfterSeconds", exception.getRetryAfterSeconds());
        return noStore(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(exception.getRetryAfterSeconds()))
                .body(problem);
    }

    @ExceptionHandler(OAuthExchangeStoreUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleUnavailable(
            OAuthExchangeStoreUnavailableException exception,
            HttpServletRequest request
    ) {
        return noStore(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "3")
                .body(ProblemDetailBuilder.create(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        ErrorTypes.OAUTH_EXCHANGE_UNAVAILABLE,
                        "OAuth Exchange Temporarily Unavailable",
                        "OAuth exchange is temporarily unavailable. Please retry or restart sign-in.",
                        request
                ));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        ProblemDetail problem = ProblemDetailBuilder.create(
                HttpStatus.BAD_REQUEST,
                ErrorTypes.OAUTH_EXCHANGE_INVALID_REQUEST,
                "Invalid OAuth Exchange Request",
                INVALID_REQUEST_DETAIL,
                request
        );
        HttpHeaders safeHeaders = new HttpHeaders();
        safeHeaders.putAll(headers);
        safeHeaders.setCacheControl(CacheControl.noStore());
        safeHeaders.setPragma("no-cache");
        return new ResponseEntity<>(problem, safeHeaders, HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity.BodyBuilder noStore(HttpStatus status) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache");
    }
}
