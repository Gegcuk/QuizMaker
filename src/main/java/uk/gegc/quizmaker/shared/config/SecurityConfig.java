package uk.gegc.quizmaker.shared.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;
import uk.gegc.quizmaker.features.auth.infra.security.JwtAuthenticationFilter;
import uk.gegc.quizmaker.features.auth.infra.security.JwtTokenService;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;


@Configuration
@RequiredArgsConstructor
@EnableMethodSecurity
@EnableAspectJAutoProxy
public class SecurityConfig {

    private final JwtTokenService jwtTokenService;
    private final CorsConfigurationSource corsConfigurationSource;
    private final TrustedProxyUtil trustedProxyUtil;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity httpSecurity) throws Exception {
        httpSecurity
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sessionManagement -> sessionManagement.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/forgot-password",
                                "/api/v1/auth/reset-password",
                                "/api/v1/auth/2fa/setup",
                                "/api/v1/auth/2fa/verify"
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
                        // Quiz endpoints - tighten permissions
                        .requestMatchers(HttpMethod.GET, "/api/v1/quizzes/export").permitAll() // Public scope export
                        .requestMatchers(HttpMethod.GET, "/api/v1/quizzes/public/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/quizzes/shared/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/quizzes/shared/**").permitAll()
                        .requestMatchers("/api/v1/quizzes/**").authenticated()
                        // Tag and category endpoints
                        .requestMatchers(HttpMethod.GET, "/api/v1/tags/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/categories/**").permitAll()
                        // Question endpoints - require authentication for general access
                        .requestMatchers("/api/v1/questions/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/swagger-ui/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/docs/**").permitAll()
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
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration cfg
    ) throws Exception {
        return cfg.getAuthenticationManager();
    }

}
