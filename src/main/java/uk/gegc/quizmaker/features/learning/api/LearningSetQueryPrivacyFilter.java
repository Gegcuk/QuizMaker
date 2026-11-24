package uk.gegc.quizmaker.features.learning.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;

/** Keep cursor/source query strings out of MVC request diagnostics; parsed parameters stay intact. */
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class LearningSetQueryPrivacyFilter extends OncePerRequestFilter {
    private static final PathPattern SETS = PathPatternParser.defaultInstance.parse("/api/v1/learning/sets/**");
    private static final PathPattern QUIZ_ITEMS = PathPatternParser.defaultInstance.parse("/api/v1/learning/quiz-items");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        Object previousPath = request.getAttribute(ServletRequestPathUtils.PATH_ATTRIBUTE);
        try {
            var path = ServletRequestPathUtils.parseAndCache(request).pathWithinApplication();
            return !SETS.matches(path) && !QUIZ_ITEMS.matches(path);
        } catch (IllegalArgumentException invalidPath) {
            // Mask malformed paths conservatively; existing security/routing still decides rejection.
            return false;
        } finally {
            // Matching must not replace a path cached by another filter or seed MVC's later cache.
            request.setAttribute(ServletRequestPathUtils.PATH_ATTRIBUTE, previousPath);
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                   FilterChain chain) throws ServletException, IOException {
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public String getQueryString() {
                return super.getQueryString() == null ? null : "[redacted]";
            }
        }, response);
    }
}
