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
                        .requestMatchers(PublicApiRoutes.anyMethodPatterns()).permitAll()
                        .requestMatchers(HttpMethod.GET, PublicApiRoutes.patternsFor(HttpMethod.GET)).permitAll()
                        .requestMatchers(HttpMethod.POST, PublicApiRoutes.patternsFor(HttpMethod.POST)).permitAll()
                        .requestMatchers(HttpMethod.HEAD, PublicApiRoutes.patternsFor(HttpMethod.HEAD)).permitAll()
                        // Billing endpoints require authentication and billing permissions (handled by @PreAuthorize)
                        .requestMatchers("/api/v1/billing/balance").authenticated()
                        .requestMatchers("/api/v1/billing/transactions").authenticated()
                        .requestMatchers("/api/v1/billing/estimate/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/me").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/me").authenticated()
                        // Quiz endpoints - tighten permissions
                        .requestMatchers("/api/v1/quizzes/**").authenticated()
                        // Question endpoints - require authentication for general access
                        .requestMatchers("/api/v1/questions/**").authenticated()
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
