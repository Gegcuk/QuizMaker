package uk.gegc.quizmaker.shared.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PublicApiOpenApiCustomizerTest {

    private final PublicApiOpenApiCustomizer customizer = new PublicApiOpenApiCustomizer();

    @Test
    void customise_marksOnlyRuntimePublicOperationsAnonymousAndNormalizesProtectedBearerRequirements() {
        Operation publicArticle = operationWithLegacyBearer();
        Operation protectedArticle = operationWithLegacyBearer();
        Operation publicSharedQuiz = operationWithLegacyBearer();
        Operation protectedSharedQuizDelete = operationWithLegacyBearer();

        OpenAPI openApi = new OpenAPI().paths(new Paths()
                .addPathItem("/api/v1/articles/public", new PathItem().get(publicArticle))
                .addPathItem("/api/v1/articles", new PathItem().get(protectedArticle))
                .addPathItem("/api/v1/quizzes/shared/{token}", new PathItem()
                        .get(publicSharedQuiz)
                        .delete(protectedSharedQuizDelete)));

        customizer.customise(openApi);

        assertThat(publicArticle.getSecurity()).hasSize(1);
        assertThat(publicArticle.getSecurity().get(0).isEmpty()).isTrue();
        assertThat(publicSharedQuiz.getSecurity()).hasSize(1);
        assertThat(publicSharedQuiz.getSecurity().get(0).isEmpty()).isTrue();
        assertThat(protectedArticle.getSecurity().get(0).keySet())
                .containsOnly(OpenApiConfig.BEARER_AUTH_SCHEME);
        assertThat(protectedSharedQuizDelete.getSecurity().get(0).keySet())
                .containsOnly(OpenApiConfig.BEARER_AUTH_SCHEME);
    }

    @Test
    void customise_preservesAdditionalProtectedSecurityRequirements() {
        SecurityRequirement requirement = new SecurityRequirement()
                .addList("Bearer Authentication")
                .addList("apiKey");
        Operation protectedOperation = new Operation().security(List.of(requirement));
        OpenAPI openApi = new OpenAPI().paths(new Paths()
                .addPathItem("/api/v1/articles", new PathItem().get(protectedOperation)));

        customizer.customise(openApi);

        assertThat(protectedOperation.getSecurity().get(0).keySet())
                .containsOnly(OpenApiConfig.BEARER_AUTH_SCHEME, "apiKey");
    }

    private Operation operationWithLegacyBearer() {
        return new Operation().security(List.of(new SecurityRequirement().addList("Bearer Authentication")));
    }
}
