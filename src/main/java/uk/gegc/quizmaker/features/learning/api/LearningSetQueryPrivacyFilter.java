package uk.gegc.quizmaker.features.learning.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Keep cursor/source query strings out of MVC request diagnostics; parsed parameters stay intact. */
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class LearningSetQueryPrivacyFilter extends OncePerRequestFilter {
    private static final String ROOT = "/api/v1/learning";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.equals(ROOT + "/sets") && !path.startsWith(ROOT + "/sets/")
                && !path.equals(ROOT + "/quiz-items");
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
