package uk.gegc.quizmaker.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * CORS (Cross-Origin Resource Sharing) configuration for the QuizMaker application.
 * This configuration controls which origins, methods, and headers are allowed
 * when making cross-origin requests to the API.
 */
@Configuration
public class CorsConfig {

    @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:4200,http://localhost:5173}")
    private String[] allowedOrigins;
    
    @Value("${app.cors.use-origin-patterns:false}")
    private boolean useOriginPatterns;

    @Value("${app.cors.allowed-methods:GET,POST,PUT,PATCH,DELETE,OPTIONS,HEAD}")
    private String[] allowedMethods;

    @Value("${app.cors.allowed-headers:Authorization,Content-Type,Accept,X-Requested-With,Origin,If-None-Match,Accept-Language}")
    private String[] allowedHeaders;

    @Value("${app.cors.exposed-headers:Location,Link,Content-Disposition,X-Total-Count,X-RateLimit-Limit,X-RateLimit-Remaining,X-RateLimit-Reset}")
    private String[] exposedHeaders;

    @Value("${app.cors.allow-credentials:true}")
    private boolean allowCredentials;

    @Value("${app.cors.max-age:3600}")
    private long maxAgeSeconds;

    /**
     * Configures CORS settings for the application.
     * 
     * Security considerations:
     * - Only specific origins are allowed (no wildcards in production)
     * - Only necessary HTTP methods are permitted
     * - Headers are restricted to what's actually needed
     * - Credentials are allowed only when necessary
     * - Preflight cache is set to reduce OPTIONS requests
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        // For development with dynamic ports (e.g., Vite), use origin patterns
        if (useOriginPatterns) {
            // This allows any port on localhost - DEV ONLY!
            configuration.setAllowedOriginPatterns(Arrays.asList(
                "http://localhost:[*]",
                "http://127.0.0.1:[*]"
            ));
        } else {
            // Production: use explicit origins only
            configuration.setAllowedOrigins(Arrays.asList(allowedOrigins));
        }
        
        // Set allowed HTTP methods including HEAD for cache validation
        configuration.setAllowedMethods(Arrays.asList(allowedMethods));
        
        // Set allowed headers - can use "*" in dev if needed
        configuration.setAllowedHeaders(Arrays.asList(allowedHeaders));
        
        // Set exposed headers (headers that the browser can access in responses)
        // Note: Authorization is a request header, not typically exposed as response
        configuration.setExposedHeaders(Arrays.asList(exposedHeaders));
        
        // Allow credentials only if using cookies/session auth
        // For JWT in Authorization header only, this could be false
        configuration.setAllowCredentials(allowCredentials);
        
        // Set max age for preflight cache using Duration (Spring 6+)
        configuration.setMaxAge(Duration.ofSeconds(maxAgeSeconds));
        
        // Apply configuration to all endpoints or just /api/**
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        
        // Register for API endpoints
        source.registerCorsConfiguration("/api/**", configuration);
        
        // Also register for Swagger/OpenAPI endpoints if needed
        source.registerCorsConfiguration("/v3/api-docs/**", configuration);
        source.registerCorsConfiguration("/swagger-ui/**", configuration);
        
        return source;
    }
} 