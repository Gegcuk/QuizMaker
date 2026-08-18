package uk.gegc.quizmaker.features.documentProcess.api;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import uk.gegc.quizmaker.features.documentProcess.application.NormalizedDocumentAccessMetrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("Normalized document authentication metrics filter")
class NormalizedDocumentAuthenticationMetricsFilterTest {

    @Mock
    private NormalizedDocumentAccessMetrics metrics;
    @Mock
    private FilterChain filterChain;

    @Test
    @DisplayName("records an unauthenticated outcome for a protected normalized-document request")
    void recordsNormalizedDocumentUnauthorizedResponse() throws Exception {
        MockHttpServletRequest request = request("/api/v1/documentProcess/documents/document-id/text");
        MockHttpServletResponse response = new MockHttpServletResponse();
        doAnswer(invocation -> {
            response.setStatus(401);
            return null;
        }).when(filterChain).doFilter(request, response);

        filter().doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(metrics).record(NormalizedDocumentAccessMetrics.Outcome.UNAUTHENTICATED);
    }

    @Test
    @DisplayName("does not count owner-denied or successful responses as unauthenticated")
    void ignoresNonUnauthorizedResponse() throws Exception {
        MockHttpServletRequest request = request("/api/v1/documentProcess/documents/document-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        doAnswer(invocation -> {
            response.setStatus(404);
            return null;
        }).when(filterChain).doFilter(request, response);

        filter().doFilter(request, response, filterChain);

        verify(metrics, never()).record(NormalizedDocumentAccessMetrics.Outcome.UNAUTHENTICATED);
    }

    @Test
    @DisplayName("does not label unrelated API authentication failures as document access")
    void ignoresOtherApiPaths() throws Exception {
        MockHttpServletRequest request = request("/api/v1/quizzes/private");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(request, response, filterChain);

        verify(metrics, never()).record(NormalizedDocumentAccessMetrics.Outcome.UNAUTHENTICATED);
    }

    @Test
    @DisplayName("telemetry failure preserves the original unauthorized response")
    void telemetryFailureDoesNotChangeResponse() throws Exception {
        MockHttpServletRequest request = request("/api/v1/documentProcess/documents");
        MockHttpServletResponse response = new MockHttpServletResponse();
        doAnswer(invocation -> {
            response.setStatus(401);
            return null;
        }).when(filterChain).doFilter(request, response);
        doThrow(new IllegalStateException("metrics unavailable"))
                .when(metrics).record(NormalizedDocumentAccessMetrics.Outcome.UNAUTHENTICATED);

        filter().doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    private NormalizedDocumentAuthenticationMetricsFilter filter() {
        return new NormalizedDocumentAuthenticationMetricsFilter(metrics);
    }

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }
}
