package uk.gegc.quizmaker.features.attempt.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.*;
import uk.gegc.quizmaker.features.attempt.application.NavigationService;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "NavigateAttemptAcknowledgement", description = "Original committed metadata on identical replay, including after later navigation/completion. Contains no question or answer content.")
public record NavigationAcknowledgement(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID attemptId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID formItemId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1") long stateVersion,
        NavigationPosition navigation,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Command families supported at the original navigation boundary. Each target and current access are revalidated; this is not an answer/edit grant.") List<Action> allowedActions,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean replayed) {
    public enum Action { VIEW_ATTEMPT, NAVIGATE_TO_ITEM }
    public NavigationAcknowledgement { allowedActions = List.copyOf(allowedActions); }
    public static NavigationAcknowledgement from(NavigationService.Result result) {
        return new NavigationAcknowledgement(result.attemptId(), result.formItemId(), result.stateVersion(), NavigationPosition.from(result.cursor()),
                List.of(Action.VIEW_ATTEMPT, Action.NAVIGATE_TO_ITEM), result.replayed());
    }
}
