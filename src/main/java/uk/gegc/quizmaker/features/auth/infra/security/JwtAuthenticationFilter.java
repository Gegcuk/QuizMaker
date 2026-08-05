package uk.gegc.quizmaker.features.auth.infra.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import uk.gegc.quizmaker.features.auth.application.AuthSessionService;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;

import java.io.IOException;

@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final AuthSessionService authSessionService;
    private final TrustedProxyUtil trustedProxyUtil;

    public JwtAuthenticationFilter(AuthSessionService authSessionService, TrustedProxyUtil trustedProxyUtil) {
        this.authSessionService = authSessionService;
        this.trustedProxyUtil = trustedProxyUtil;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                Authentication authentication = authSessionService.authenticateAccessToken(token);
                if (authentication != null) {
                SecurityContextHolder.getContext().setAuthentication(authentication);
                log.debug("Successfully authenticated user: {}", authentication.getName());
                } else {
                    logInvalidToken(request);
                }
            } catch (RuntimeException ex) {
                // A session-store outage must fail closed. Do not expose token data in logs or responses.
                log.error("Authentication session validation failed", ex);
            }
        }

        filterChain.doFilter(request, response);
    }

    private void logInvalidToken(HttpServletRequest request) {
        String clientIp = trustedProxyUtil != null ? trustedProxyUtil.getClientIp(request) : request.getRemoteAddr();
        log.warn("Invalid access token received from IP: {}, URI: {}, User-Agent: {}",
                clientIp,
                request.getRequestURI(),
                request.getHeader("User-Agent"));
    }
}
