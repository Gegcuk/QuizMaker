package uk.gegc.quizmaker.features.learning.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpServletMapping;
import org.springframework.web.util.ServletRequestPathUtils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.MappingMatch;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LearningSetQueryPrivacyFilterTest {
    @Test
    void masksOnlyTheRawQueryOnSetRoutesAndPreservesParsedParametersAndContextPath() throws Exception {
        for (String root : List.of("/api/v1/learning", "/%61pi/v1/learning", "/api/%76%31/learning", "/api/v1/%6cearning")) {
          for (String path : List.of("/sets", "/%73ets", "/sets/set-id/members", "/sets/set-id/%6dembers",
                  "/sets/set-id/items", "/sets/set-id/%69tems", "/quiz-items", "/%71uiz-items")) {
            var request = new MockHttpServletRequest("GET", "/app" + root + path);
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
                assertThat(actual.getAttribute(ServletRequestPathUtils.PATH_ATTRIBUTE)).isNull();
            });
            assertThat(request.getQueryString()).isEqualTo("limit=1&after=private%2Bcursor&after=second");
          }
        }
    }

    @Test
    void leavesMissingQueriesUnchanged() throws Exception {
        for (String path : List.of("/api/v1/learning/sets", "/api/v1/learning/quiz-items")) {
            var request = new MockHttpServletRequest("GET", path);
            new LearningSetQueryPrivacyFilter().doFilter(request, new MockHttpServletResponse(), (filtered, response) -> {
                assertThat(((HttpServletRequest) filtered).getQueryString()).isNull();
            });
        }
    }

    @Test
    void passesUnrelatedRoutesThroughWithTheOriginalRequestAndNonEmptyQuery() throws Exception {
        for (String path : List.of("/api/v1/learning/sets-other", "/api/v1/learning/due", "/api/v1/auth/verify",
                "/api%2Fv1/learning/sets", "/%2561pi/v1/learning/sets")) {
            var request = new MockHttpServletRequest("GET", path);
            request.setQueryString("after=original-cursor&limit=1");
            request.addParameter("after", "original-cursor");
            request.addParameter("limit", "1");
            new LearningSetQueryPrivacyFilter().doFilter(request, new MockHttpServletResponse(), (filtered, response) -> {
                assertThat(filtered).as("Unrelated route %s",path).isSameAs(request);
                assertThat(((HttpServletRequest) filtered).getQueryString()).isEqualTo("after=original-cursor&limit=1");
            });
        }
    }

    @Test
    void usesTheMvcServletPrefixAndRestoresAnExistingParsedPath() throws Exception {
        var request = new MockHttpServletRequest("GET", "/app/gateway/%61pi/v1/learning/sets/set-id/items");
        request.setContextPath("/app");
        request.setServletPath("/gateway");
        request.setHttpServletMapping(new MockHttpServletMapping("api/v1/learning/sets/set-id/items",
                "/gateway/*", "dispatcherServlet", MappingMatch.PATH));
        request.setQueryString("after=private-cursor");
        request.addParameter("after", "private-cursor");
        var previousPath = ServletRequestPathUtils.parseAndCache(request);
        new LearningSetQueryPrivacyFilter().doFilter(request, new MockHttpServletResponse(), (filtered, response) -> {
            var actual = (HttpServletRequest) filtered;
            assertThat(actual.getQueryString()).isEqualTo("[redacted]");
            assertThat(actual.getParameter("after")).isEqualTo("private-cursor");
            assertThat(actual.getAttribute(ServletRequestPathUtils.PATH_ATTRIBUTE)).isSameAs(previousPath);
        });
        assertThat(request.getAttribute(ServletRequestPathUtils.PATH_ATTRIBUTE)).isSameAs(previousPath);
    }

    @Test
    void masksAnUnparseableQueryAndLeavesTheResponseToTheExistingChain() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/learning/%XX");
        request.setQueryString("after=private-cursor");
        var response = new MockHttpServletResponse();
        new LearningSetQueryPrivacyFilter().doFilter(request, response, (filtered, downstreamResponse) -> {
            var actual = (HttpServletRequest) filtered;
            assertThat(actual.getQueryString()).isEqualTo("[redacted]");
            assertThat(actual.getRequestURI()).isEqualTo(request.getRequestURI());
            ((jakarta.servlet.http.HttpServletResponse) downstreamResponse).setStatus(400);
        });
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(request.getAttribute(ServletRequestPathUtils.PATH_ATTRIBUTE)).isNull();
    }
}
