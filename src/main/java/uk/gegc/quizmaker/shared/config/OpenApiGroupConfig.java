package uk.gegc.quizmaker.shared.config;

import io.swagger.v3.oas.models.OpenAPI;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for API documentation groups.
 * 
 * This provides a cleaner, more maintainable alternative to defining groups
 * in application.properties. Each group can be defined as a Spring Bean with
 * clear structure and IDE support.
 */
@Configuration
public class OpenApiGroupConfig {

    @Bean
    public GroupedOpenApi authGroup() {
        return GroupedOpenApi.builder()
                .group("auth")
                .displayName("Authentication & Users")
                .pathsToMatch("/api/v1/auth/**", "/api/v1/users/**")
                .build();
    }

    @Bean
    public GroupedOpenApi quizzesGroup() {
        return GroupedOpenApi.builder()
                .group("quizzes")
                .displayName("Quizzes & Questions")
                .pathsToMatch(
                    "/api/v1/quizzes/**",
                    "/api/v1/questions/**",
                    "/api/v1/tags/**",
                    "/api/v1/categories/**",
                    "/api/v1/share-links/**"
                )
                .build();
    }

    @Bean
    public GroupedOpenApi attemptsGroup() {
        return GroupedOpenApi.builder()
                .group("attempts")
                .displayName("Quiz Attempts & Scoring")
                .pathsToMatch("/api/v1/attempts/**")
                .build();
    }

    @Bean
    public GroupedOpenApi documentsGroup() {
        return GroupedOpenApi.builder()
                .group("documents")
                .displayName("Document Processing")
                .pathsToMatch("/api/v1/documentProcess/**", "/api/documents/**")
                .build();
    }

    @Bean
    public GroupedOpenApi billingGroup() {
        return GroupedOpenApi.builder()
                .group("billing")
                .displayName("Billing & Payments")
                .pathsToMatch("/api/v1/billing/**")
                .build();
    }

    @Bean
    public GroupedOpenApi aiGroup() {
        return GroupedOpenApi.builder()
                .group("ai")
                .displayName("AI Features")
                .pathsToMatch("/api/ai/**", "/api/v1/ai-analysis/**")
                .build();
    }

    @Bean
    public GroupedOpenApi adminGroup() {
        return GroupedOpenApi.builder()
                .group("admin")
                .displayName("Administration")
                .pathsToMatch("/api/v1/admin/**")
                .build();
    }
}

