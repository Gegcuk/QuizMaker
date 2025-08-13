package uk.gegc.quizmaker.security;

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
import uk.gegc.quizmaker.util.TrustedProxyUtil;

import java.io.IOException;

@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final TrustedProxyUtil trustedProxyUtil;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider, TrustedProxyUtil trustedProxyUtil) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.trustedProxyUtil = trustedProxyUtil;
    }

    // Backward-compatible constructor for any old usages/tests
    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
        this(jwtTokenProvider, new TrustedProxyUtil());
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (jwtTokenProvider.validateToken(token)) {
                Authentication authentication = jwtTokenProvider.getAuthentication(token);
                SecurityContextHolder.getContext().setAuthentication(authentication);
                log.debug("Successfully authenticated user: {}", authentication.getName());
            } else {
                // Log failed token validation with request details for security monitoring
                String clientIp = trustedProxyUtil != null ? trustedProxyUtil.getClientIp(request) : request.getRemoteAddr();
                log.warn("Invalid JWT token received from IP: {}, URI: {}, User-Agent: {}", 
                    clientIp, 
                    request.getRequestURI(),
                    request.getHeader("User-Agent"));
            }
        }

        filterChain.doFilter(request, response);
    }
}
