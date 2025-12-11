package uk.gegc.quizmaker.shared.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import java.io.IOException;
import org.springframework.web.cors.CorsConfigurationSource;
import uk.gegc.quizmaker.features.auth.infra.security.CustomOAuth2UserService;
import uk.gegc.quizmaker.features.auth.infra.security.JwtAuthenticationFilter;
import uk.gegc.quizmaker.features.auth.infra.security.JwtTokenService;
import uk.gegc.quizmaker.features.auth.infra.security.OAuth2AuthenticationFailureHandler;
import uk.gegc.quizmaker.features.auth.infra.security.OAuth2AuthenticationSuccessHandler;
import uk.gegc.quizmaker.shared.api.problem.ErrorTypes;
import uk.gegc.quizmaker.shared.api.problem.ProblemDetailBuilder;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;


@Configuration
@RequiredArgsConstructor
@EnableMethodSecurity
@EnableAspectJAutoProxy
public class SecurityConfig {

    private final JwtTokenService jwtTokenService;
    private final CorsConfigurationSource corsConfigurationSource;
    private final TrustedProxyUtil trustedProxyUtil;
    private final CustomOAuth2UserService customOAuth2UserService;
    private final OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler;
    private final OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity httpSecurity) throws Exception {
        httpSecurity
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(sessionManagement -> sessionManagement.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handler -> handler
                        .authenticationEntryPoint((request, response, ex) -> writeAuthResponse(request, response, false))
                        .accessDeniedHandler((request, response, ex) -> writeAuthResponse(request, response, true)))
                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(userInfo -> userInfo
                                .userService(customOAuth2UserService)
                        )
                        .successHandler(oAuth2AuthenticationSuccessHandler)
                        .failureHandler(oAuth2AuthenticationFailureHandler)
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/forgot-password",
                                "/api/v1/auth/reset-password",
                                "/api/v1/auth/2fa/setup",
                                "/api/v1/auth/2fa/verify",
                                "/oauth2/**",
                                "/login/oauth2/**"
                        ).permitAll()
                        // Stripe webhook must be callable by Stripe without authentication
                        .requestMatchers(HttpMethod.POST, "/api/v1/billing/stripe/webhook").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/billing/webhooks").permitAll()
                        // Billing config endpoint should be public for frontend integration
                        .requestMatchers(HttpMethod.GET, "/api/v1/billing/config").permitAll()
                        // Billing endpoints require authentication and billing permissions (handled by @PreAuthorize)
                        .requestMatchers("/api/v1/billing/balance").authenticated()
                        .requestMatchers("/api/v1/billing/transactions").authenticated()
                        .requestMatchers("/api/v1/billing/estimate/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/me").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/me").authenticated()
                        // Article public endpoints
                        .requestMatchers(HttpMethod.GET, "/api/v1/articles/public/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/articles/sitemap").permitAll()
                        // Quiz endpoints - tighten permissions
                        .requestMatchers(HttpMethod.GET, "/api/v1/quizzes/export").permitAll() // Public scope export
                        .requestMatchers(HttpMethod.GET, "/api/v1/quizzes/public/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/quizzes/shared/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/quizzes/shared/**").permitAll()
                        .requestMatchers("/api/v1/quizzes/**").authenticated()
                        // Tag and category endpoints
                        .requestMatchers(HttpMethod.GET, "/api/v1/tags/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/categories/**").permitAll()
                        // Question schemas - allow public access for documentation
                        .requestMatchers(HttpMethod.GET, "/api/v1/questions/schemas/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/questions/schemas").permitAll()
                        // Question endpoints - require authentication for general access
                        .requestMatchers("/api/v1/questions/**").authenticated()
                        // API Documentation - allow public access
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/swagger-ui/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/docs/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/api-summary").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/diagnostic/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health/**").permitAll()
                        .requestMatchers("/api/documents/**").authenticated()
                        .anyRequest().authenticated())
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtTokenService, trustedProxyUtil),
                        UsernamePasswordAuthenticationFilter.class
                )
                .cors(cors -> cors.configurationSource(corsConfigurationSource));

        return httpSecurity.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration cfg
    ) throws Exception {
        return cfg.getAuthenticationManager();
    }

    private void writeAuthResponse(HttpServletRequest request, HttpServletResponse response, boolean forbiddenFallback) throws IOException {
        // forbiddenFallback indicates the context:
        // - false = authenticationEntryPoint (not authenticated) -> return 401 Unauthorized
        // - true = accessDeniedHandler (authenticated but no permission) -> return 403 Forbidden
        
        HttpStatus status = forbiddenFallback ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED;
        
        // Create RFC 7807 Problem Detail
        ProblemDetail problemDetail = forbiddenFallback 
            ? ProblemDetailBuilder.create(
                HttpStatus.FORBIDDEN,
                ErrorTypes.ACCESS_DENIED,
                "Access Denied",
                "You do not have permission to access this resource",
                request
            )
            : ProblemDetailBuilder.create(
                HttpStatus.UNAUTHORIZED,
                ErrorTypes.UNAUTHORIZED,
                "Unauthorized",
                "Authentication is required to access this resource",
                request
            );
        
        // Write Problem Detail as JSON
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.getWriter().write(objectMapper.writeValueAsString(problemDetail));
    }

}
