package uk.gegc.quizmaker.features.attempt.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** Participant wire types for a saved completion-only or retained-response self-assessment. */
public final class UngradedParticipantResponse {
    private UngradedParticipantResponse() { }

    public enum Kind { UNGRADED }
    public enum State { IN_PROGRESS, COMPLETED }
    public enum Action { VIEW_ATTEMPT, SUBMIT_ANSWER, SUBMIT_ATTEMPT }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "UngradedParticipantView", additionalProperties = FALSE)
    public record View(@Schema(requiredMode = REQUIRED) Kind activityKind,
                       @Schema(requiredMode = REQUIRED) UUID attemptId,
                       @Schema(requiredMode = REQUIRED) UUID formId,
                       @Schema(requiredMode = REQUIRED) long stateVersion,
                       @Schema(requiredMode = REQUIRED) State state,
                       @Schema(description = "UTC instant of explicit completion; absent during participation.") Instant completedAt,
                       @Schema(description = "Saved participant-safe questions while in progress; absent after completion.") List<Item> items,
                       @Schema(requiredMode = REQUIRED) List<Action> allowedActions) {
        @Override public String toString() { return "UngradedView[redacted]"; }
    }

    @Schema(name = "UngradedParticipantItem", additionalProperties = FALSE)
    public record Item(@Schema(requiredMode = REQUIRED) ParticipantAttemptView.Item question,
                       @Schema(requiredMode = REQUIRED) boolean required,
                       @Schema(requiredMode = REQUIRED) boolean participated) {
        @Override public String toString() { return "UngradedItem[redacted]"; }
    }

    @Schema(name = "UngradedParticipationAcknowledgement", additionalProperties = FALSE)
    public record Participation(@Schema(requiredMode = REQUIRED) Kind activityKind,
                                @Schema(requiredMode = REQUIRED) UUID attemptId,
                                @Schema(requiredMode = REQUIRED) UUID participationId,
                                @Schema(requiredMode = REQUIRED) long stateVersion,
                                @Schema(requiredMode = REQUIRED) boolean replayed) {
        @Override public String toString() { return "UngradedParticipation[redacted]"; }
    }

    @Schema(name = "UngradedCompletionAcknowledgement", additionalProperties = FALSE)
    public record Completion(@Schema(requiredMode = REQUIRED) Kind activityKind,
                             @Schema(requiredMode = REQUIRED) UUID attemptId,
                             @Schema(requiredMode = REQUIRED) Instant completedAt,
                             @Schema(requiredMode = REQUIRED) long stateVersion,
                             @Schema(requiredMode = REQUIRED) boolean replayed) {
        @Override public String toString() { return "UngradedCompletion[redacted]"; }
    }
}
