package uk.gegc.quizmaker.features.attempt.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.application.ParticipantViewDisclosureReader.ContentVisibility;
import uk.gegc.quizmaker.features.attempt.application.ParticipantViewMediaReader.Availability;
import uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "ParticipantAttemptView", description = "Versioned participant projection. No keys, responses, scores, explanations, subject IDs or private provenance. Missing item/completion facts grant no write actions. UTC times; no pagination, filtering or sorting.")
public record ParticipantAttemptView(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Representation representation,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "1") int schemaVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID attemptId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID quizId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID quizRevisionId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID formId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1") long stateVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "1") int policyVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AttemptLifecycle.Execution execution,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AttemptLifecycle.AnswerCollection answerCollection,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant startedAt,
        @Schema(description = "Absent until collection is submitted. Does not establish grading or release.") Instant submittedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "This implemented baseline is untimed; it cannot supply a timer/deadline for a future timed capability.") Timing timing,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "WITHHELD means no content disclosure proof is available; it is not a grading/result state. Items are absent when withheld.") ContentVisibility contentVisibility,
        @Schema(description = "Saved item/option order. Absent when content is withheld.") List<Item> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Advisory server decisions; every command must reauthorize. The current read-only baseline reports VIEW_ATTEMPT.") List<AttemptCommands.Type> allowedActions
) implements AttemptDetailsResponse {
    public enum Representation { POLICY_DRIVEN }
    public enum Timing { UNTIMED }
    public ParticipantAttemptView {
        items = items == null ? null : List.copyOf(items); allowedActions = List.copyOf(allowedActions);
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "ParticipantAttemptItem", description = "Original saved form-item identity and zero-based position. No source question/option keys.")
    public record Item(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                       @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0") int position,
                       @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"MCQ_SINGLE", "MCQ_MULTI", "TRUE_FALSE", "FILL_GAP", "ORDERING"}) QuestionType type,
                       @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String questionText,
                       @Schema(requiredMode = Schema.RequiredMode.REQUIRED, oneOf = {AttemptFormResponse.Choices.class,
                               AttemptFormResponse.TrueFalse.class, AttemptFormResponse.FillGap.class, AttemptFormResponse.Ordering.class})
                       AttemptFormResponse.Content content,
                       @Schema(description = "Absent for a text-only question.") Image image) {
        @Override public String toString() { return "ParticipantAttemptItem[type=" + type + "]"; }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "ParticipantAttemptImage", description = "Current display availability of the original bound image. UNAVAILABLE/RESTRICTED have no URL. Never changes answers, grades, identity or clocks.")
    public record Image(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Availability availability,
                        @Schema(description = "Only present for AVAILABLE; never an authoring/external fallback.") String url) {
        @Override public String toString() { return "ParticipantAttemptImage[availability=" + availability + "]"; }
    }
    @Override public String toString() { return "ParticipantAttemptView"; }
}
