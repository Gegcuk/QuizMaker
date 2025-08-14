package uk.gegc.quizmaker.features.quiz.infra.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages share link cookies with scoped paths and security attributes.
 * Provides secure cookie handling for share link tokens.
 */
@Component
@Slf4j
public class ShareLinkCookieManager {

    private static final String SHARE_TOKEN_COOKIE_NAME = "share_token";
    private static final String COOKIE_PATH_PREFIX = "/quizzes/";

    @Value("${quizmaker.share-links.cookie-ttl-seconds:3600}")
    private int cookieTtlSeconds;

    /**
     * Sets a secure share link cookie with scoped path and security attributes.
     * 
     * @param response The HTTP response to add the cookie to
     * @param token The share link token
     * @param quizId The quiz ID for path scoping
     */
    public void setShareLinkCookie(HttpServletResponse response, String token, UUID quizId) {
        ResponseCookie cookie = ResponseCookie.from(SHARE_TOKEN_COOKIE_NAME, token)
                .path(COOKIE_PATH_PREFIX + quizId) // Scoped to quiz viewer route
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .maxAge(Duration.ofSeconds(cookieTtlSeconds))
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        
        log.debug("Set share link cookie for quiz {} with path {}", quizId, cookie.getPath());
    }

    /**
     * Retrieves the share link token from the request cookies.
     * 
     * @param request The HTTP request to extract the cookie from
     * @return Optional containing the token if found, empty otherwise
     */
    public Optional<String> getShareLinkToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }

        return Arrays.stream(cookies)
                .filter(cookie -> SHARE_TOKEN_COOKIE_NAME.equals(cookie.getName()))
                .findFirst()
                .map(Cookie::getValue);
    }

    /**
     * Clears the share link cookie from the response.
     * 
     * @param response The HTTP response to clear the cookie from
     */
    public void clearShareLinkCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(SHARE_TOKEN_COOKIE_NAME, "")
                .path("/") // Clear from all paths
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .maxAge(Duration.ZERO) // Expire immediately
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        
        log.debug("Cleared share link cookie");
    }

    /**
     * Invalidates all share link cookies for a specific quiz.
     * This is useful when a quiz is unpublished or share links are revoked.
     * 
     * @param response The HTTP response to add invalidation cookies to
     * @param quizId The quiz ID to invalidate cookies for
     */
    public void invalidateCookiesForQuiz(HttpServletResponse response, UUID quizId) {
        // Clear cookie from the specific quiz path
        ResponseCookie cookie = ResponseCookie.from(SHARE_TOKEN_COOKIE_NAME, "")
                .path(COOKIE_PATH_PREFIX + quizId)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .maxAge(Duration.ZERO) // Expire immediately
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        
        log.debug("Invalidated share link cookies for quiz {}", quizId);
    }

    /**
     * Gets the configured cookie TTL in seconds.
     * 
     * @return The cookie TTL in seconds
     */
    public int getCookieTtlSeconds() {
        return cookieTtlSeconds;
    }
}
