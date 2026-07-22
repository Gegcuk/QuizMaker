package uk.gegc.quizmaker.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    public static final String BEARER_AUTH_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI quizMakerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("QuizMaker API")
                        .version("v1"))
                .components(new Components()
                        .addSecuritySchemes(BEARER_AUTH_SCHEME,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH_SCHEME));
    }

    @Bean
    public GlobalOpenApiCustomizer publicApiOpenApiCustomizer() {
        return new PublicApiOpenApiCustomizer();
    }
}
