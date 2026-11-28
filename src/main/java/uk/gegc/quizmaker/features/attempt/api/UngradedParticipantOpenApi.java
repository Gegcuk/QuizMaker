package uk.gegc.quizmaker.features.attempt.api;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import uk.gegc.quizmaker.features.attempt.api.dto.UngradedParticipantResponse.*;

/** Adds the second JSON success shape only on the three enabled operations, in full and grouped specs. */
public final class UngradedParticipantOpenApi implements GlobalOpenApiCustomizer {
    private static final String ATTEMPT = "00000000-0000-0000-0000-000000000001";
    private static final String FORM = "00000000-0000-0000-0000-000000000002";
    private static final String COMPLETED = "2026-11-27T10:00:00Z";

    @Override public void customise(OpenAPI api) {
        if (api.getPaths() == null) return;
        api.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
            if (operation.getOperationId() == null) return;
            switch (operation.getOperationId()) {
                case "readParticipantAttempt" -> extend(api, operation, View.class,
                        Map.of("activityKind", "UNGRADED", "attemptId", ATTEMPT, "formId", FORM, "stateVersion", 3,
                                "state", "COMPLETED", "completedAt", COMPLETED, "allowedActions", List.of("VIEW_ATTEMPT")),
                        "In-progress ungraded views include the saved questions, required participation and per-item progress. "
                                + "Completed views contain completion facts only. Query overrides and graded review/key requests cannot reveal graded data.");
                case "acceptAttemptResponse" -> extend(api, operation, Participation.class,
                        Map.of("activityKind", "UNGRADED", "attemptId", ATTEMPT, "participationId", FORM, "stateVersion", 2, "replayed", false),
                        "The saved activity determines response handling. In retained mode changed content conflicts. In no-retention mode "
                                + "the same key, item and expected version acknowledge the same participation for any valid value. No answer bytes or content digest are retained.");
                case "submitAttempt" -> extend(api, operation, Completion.class,
                        Map.of("activityKind", "UNGRADED", "attemptId", ATTEMPT, "completedAt", COMPLETED, "stateVersion", 3, "replayed", false),
                        "A confirmed submit requires every saved required participation. A 422 SUBMISSION_INCOMPLETE lists the unanswered "
                                + "required form item IDs in saved order in missingFormItemIds. Missing confirmation also returns 422. "
                                + "An exact replay returns the original completion time. Optional items may remain unanswered.");
                default -> { }
            }
        }));
    }

    private static void extend(OpenAPI api, Operation operation, Class<?> type, Map<String, Object> example, String description) {
        var media = operation.getResponses().get("200").getContent().get("application/json");
        var graded = media.getSchema();
        if (graded.get$ref() == null) return; // Already customized.
        var resolved = ModelConverters.getInstance(api.getOpenapi().startsWith("3.1"))
                .resolveAsResolvedSchema(new AnnotatedType(type).resolveAsRef(true));
        resolved.referencedSchemas.forEach(api.getComponents().getSchemas()::putIfAbsent);
        // Legacy acknowledgements allow additional properties. Exclude the ungraded discriminator
        // from this branch so a valid ungraded acknowledgement matches exactly one schema.
        var gradedBranch = new ComposedSchema().addAllOfItem(graded).not(new Schema<>().addRequiredItem("activityKind"));
        media.setSchema(new ComposedSchema().addOneOfItem(gradedBranch).addOneOfItem(resolved.schema));
        if (media.getExample() != null) {
            media.addExamples("graded", new Example().value(media.getExample()));
            media.setExample(null);
            media.setExampleSetFlag(false);
        }
        media.addExamples("ungraded", new Example().value(example));
        operation.setDescription(operation.getDescription() + " Ungraded participant support is gated separately until #695. " + description);
    }
}
