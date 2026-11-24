package uk.gegc.quizmaker.features.learning.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LearningSetQueryPrivacyFilterTest {
    @Test
    void masksOnlyTheRawQueryOnSetRoutesAndPreservesParsedParametersAndContextPath() throws Exception {
        for (String path : List.of("/sets", "/sets/set-id/members", "/sets/set-id/items", "/quiz-items")) {
            var request = new MockHttpServletRequest("GET", "/app/api/v1/learning" + path);
            request.setContextPath("/app");
            request.setQueryString("limit=1&after=private%2Bcursor&after=second");
            request.addParameter("limit", "1");
            request.addParameter("after", "private+cursor", "second");
            var parameters = request.getParameterMap();
            new LearningSetQueryPrivacyFilter().doFilter(request, new MockHttpServletResponse(), (filtered, response) -> {
                var actual = (HttpServletRequest) filtered;
                assertThat(actual.getQueryString()).isEqualTo("[redacted]");
                assertThat(actual.getParameter("after")).isEqualTo("private+cursor");
                assertThat(actual.getParameterValues("after")).containsExactly("private+cursor", "second");
                assertThat(actual.getParameterMap()).isEqualTo(parameters);
                assertThat(actual.getRequestURI()).isEqualTo(request.getRequestURI());
                assertThat(actual.getContextPath()).isEqualTo("/app");
            });
            assertThat(request.getQueryString()).isEqualTo("limit=1&after=private%2Bcursor&after=second");
        }
    }

    @Test
    void leavesMissingQueriesAndUnrelatedRoutesUnchanged() throws Exception {
        for (String path : List.of("/api/v1/learning/sets", "/api/v1/learning/quiz-items",
                "/api/v1/learning/sets-other", "/api/v1/learning/due", "/api/v1/auth/verify")) {
            var request = new MockHttpServletRequest("GET", path);
            boolean unrelated = !path.endsWith("/sets") && !path.endsWith("/quiz-items");
            if (unrelated) request.setQueryString("value=original");
            new LearningSetQueryPrivacyFilter().doFilter(request, new MockHttpServletResponse(), (filtered, response) -> {
                assertThat(((HttpServletRequest) filtered).getQueryString()).isEqualTo(request.getQueryString());
                if (unrelated) assertThat(filtered).isSameAs(request);
            });
        }
    }
}
