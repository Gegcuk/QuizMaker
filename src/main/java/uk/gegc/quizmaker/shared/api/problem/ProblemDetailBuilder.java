package uk.gegc.quizmaker.shared.api.problem;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.context.request.WebRequest;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

/**
 * Helper functions for building RFC 7807 {@link ProblemDetail} instances in a consistent way.
 */
public final class ProblemDetailBuilder {

    private ProblemDetailBuilder() {
        throw new AssertionError("Utility class - do not instantiate");
    }

    /**
     * Creates a {@link ProblemDetail} using the provided HTTP request to populate the {@code instance} field.
     */
    public static ProblemDetail create(
            HttpStatus status,
            URI type,
            String title,
            String detail,
            HttpServletRequest request
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(title);
        if (request != null) {
            problem.setInstance(URI.create(request.getRequestURI()));
        }
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * Creates a {@link ProblemDetail} using Spring's {@link WebRequest} to populate the instance field.
     * Useful inside Spring MVC override methods where an {@link HttpServletRequest} is not available.
     */
    public static ProblemDetail create(
            HttpStatus status,
            URI type,
            String title,
            String detail,
            WebRequest request
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(title);
        if (request != null) {
            String description = request.getDescription(false);
            if (description != null) {
                String uri = description.startsWith("uri=") ? description.substring(4) : description;
                problem.setInstance(URI.create(uri));
            }
        }
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * Creates a {@link ProblemDetail} without any request context.
     */
    public static ProblemDetail create(
            HttpStatus status,
            URI type,
            String title,
            String detail
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(title);
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * Creates a minimal {@link ProblemDetail} with status + detail only.
     */
    public static ProblemDetail createSimple(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * Creates a {@link ProblemDetail} and applies the supplied custom properties in a single call.
     */
    public static ProblemDetail createWithProperties(
            HttpStatus status,
            URI type,
            String title,
            String detail,
            HttpServletRequest request,
            Map<String, Object> properties
    ) {
        ProblemDetail problem = create(status, type, title, detail, request);
        if (properties != null) {
            properties.forEach(problem::setProperty);
        }
        return problem;
    }
}
