package uk.gegc.quizmaker.features.learning.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomizer;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Preserve explicit nulls in the published OpenAPI 3.1 Learning contract. */
public final class LearningContractsOpenApiCustomizer implements OpenApiCustomizer {
    @Override
    public void customise(OpenAPI api) {
        if (api.getComponents() == null || api.getComponents().getSchemas() == null) return;
        Map<String, Schema> schemas = api.getComponents().getSchemas();
        Map.ofEntries(
                Map.entry("LearningPlanAcknowledgement", List.of("plan", "binding", "cycle")),
                Map.entry("LearningPlanDefinition", List.of("timeZone", "localTime")),
                Map.entry("LearningPlanBinding", List.of("planId", "planVersion")),
                Map.entry("LearningPlanCycle", List.of("planId", "planVersion")),
                Map.entry("LearningPlanPage", List.of("next")),
                Map.entry("LearningPlanVersionPage", List.of("next")),
                Map.entry("LearningPlanCyclePage", List.of("next")),
                Map.entry("LearningResponseAcknowledgement", List.of("nextItemId", "nextDueAt")),
                Map.entry("LearningSessionView", List.of("nextItemId")))
                .forEach((name, fields) -> fields.forEach(field -> nullable(schemas.get(name), field)));

        // RFC 7807 uses URI references, including a relative request path.
        Schema<?> problem = schemas.get("ProblemDetail");
        if (problem != null && problem.getProperties() != null) {
            for (String field : List.of("type", "instance")) {
                Schema<?> value = problem.getProperties().get(field);
                if (value != null) value.setFormat("uri-reference");
            }
        }
    }

    private static void nullable(Schema<?> owner, String field) {
        if (owner == null || owner.getProperties() == null) return;
        Schema<?> value = owner.getProperties().get(field);
        if (value == null || value.getAnyOf() != null && value.getAnyOf().stream()
                .anyMatch(option -> option.getTypes() != null && option.getTypes().contains("null"))) return;
        owner.addProperty(field, new ComposedSchema().anyOf(List.of(value, new Schema<>().types(Set.of("null")))));
    }
}
