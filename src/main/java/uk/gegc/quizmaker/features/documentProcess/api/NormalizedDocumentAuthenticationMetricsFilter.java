package uk.gegc.quizmaker.features.documentProcess.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
import uk.gegc.quizmaker.features.documentProcess.application.NormalizedDocumentAccessMetrics;

import java.io.IOException;

@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RequiredArgsConstructor
@Slf4j
public class NormalizedDocumentAuthenticationMetricsFilter extends OncePerRequestFilter {

    static final String PATH_PREFIX = "/api/v1/documentProcess/documents";

    private final NormalizedDocumentAccessMetrics metrics;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestPath = request.getRequestURI().substring(request.getContextPath().length());
        return !requestPath.equals(PATH_PREFIX) && !requestPath.startsWith(PATH_PREFIX + "/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (response.getStatus() == HttpServletResponse.SC_UNAUTHORIZED) {
                recordUnauthenticated();
            }
        }
    }

    private void recordUnauthenticated() {
        try {
            metrics.record(NormalizedDocumentAccessMetrics.Outcome.UNAUTHENTICATED);
        } catch (RuntimeException telemetryFailure) {
            log.warn("Normalized-document authentication metric could not be recorded");
        }
    }
}
