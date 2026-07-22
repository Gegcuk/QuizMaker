package uk.gegc.quizmaker.shared.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.http.HttpMethod;

import java.util.List;

/**
 * Makes generated OpenAPI operation security match the runtime public-route policy.
 */
public final class PublicApiOpenApiCustomizer implements GlobalOpenApiCustomizer {

    private static final String LEGACY_BEARER_AUTH_SCHEME = "Bearer Authentication";

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return;
        }

        openApi.getPaths().forEach((path, pathItem) -> pathItem.readOperationsMap()
                .forEach((method, operation) -> customizeOperation(path, method, operation)));
    }

    private void customizeOperation(String path, PathItem.HttpMethod method, Operation operation) {
        HttpMethod httpMethod = HttpMethod.valueOf(method.name());
        if (PublicApiRoutes.isPublic(httpMethod, path)) {
            operation.setSecurity(List.of(new SecurityRequirement()));
            return;
        }

        normalizeLegacyBearerRequirement(operation);
    }

    private void normalizeLegacyBearerRequirement(Operation operation) {
        if (operation.getSecurity() == null) {
            return;
        }

        operation.getSecurity().forEach(requirement -> {
            List<String> scopes = requirement.remove(LEGACY_BEARER_AUTH_SCHEME);
            if (scopes != null && !requirement.containsKey(OpenApiConfig.BEARER_AUTH_SCHEME)) {
                requirement.put(OpenApiConfig.BEARER_AUTH_SCHEME, scopes);
            }
        });
    }
}
