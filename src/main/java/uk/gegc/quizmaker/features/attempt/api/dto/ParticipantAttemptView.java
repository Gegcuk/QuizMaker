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
@Schema(name = "ParticipantAttemptView", description = "Versioned participant projection. Protected categories occur only inside result after committed release and current authorization. No subject IDs or private provenance. UTC times; no pagination, filtering or sorting.")
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
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "UNTIMED preserves the original contract. TIMED has saved timingDetails; public timed activation requires separate conformance.") Timing timing,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "WITHHELD means no content disclosure proof is available; it is not a grading/result state. Items are absent when withheld.") ContentVisibility contentVisibility,
        @Schema(description = "Saved item/option order. Absent when content is withheld or review context was not requested.") List<Item> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Advisory server decisions; every command must reauthorize. The current read-only baseline reports VIEW_ATTEMPT.") List<AttemptCommands.Type> allowedActions,
        @Schema(description = "Absent until a matching release commits. Every nested category is independently authorized; include flags cannot grant access.")
        uk.gegc.quizmaker.features.result.api.dto.ParticipantResultView result,
        @Schema(description = "Saved sequential position; absent for FREE. Does not imply an answer or a change to finality.") NavigationPosition navigation,
        @Schema(description = "Absent for UNTIMED; saved overall timing for TIMED. Additive web/iOS field; no client clock authority.") ParticipantAttemptTiming timingDetails
) {
    public enum Representation { POLICY_DRIVEN }
    public enum Timing { UNTIMED, TIMED }
    public ParticipantAttemptView {
        items = items == null ? null : List.copyOf(items); allowedActions = List.copyOf(allowedActions);
    }
    public ParticipantAttemptView(Representation representation, int schemaVersion, UUID attemptId, UUID quizId,
            UUID quizRevisionId, UUID formId, long stateVersion, int policyVersion, AttemptLifecycle.Execution execution,
            AttemptLifecycle.AnswerCollection answerCollection, Instant startedAt, Instant submittedAt, Timing timing,
            ContentVisibility contentVisibility, List<Item> items, List<AttemptCommands.Type> allowedActions,
            uk.gegc.quizmaker.features.result.api.dto.ParticipantResultView result, NavigationPosition navigation) {
        this(representation, schemaVersion, attemptId, quizId, quizRevisionId, formId, stateVersion, policyVersion, execution,
                answerCollection, startedAt, submittedAt, timing, contentVisibility, items, allowedActions, result, navigation, null);
    }
    public ParticipantAttemptView(Representation representation, int schemaVersion, UUID attemptId, UUID quizId,
            UUID quizRevisionId, UUID formId, long stateVersion, int policyVersion, AttemptLifecycle.Execution execution,
            AttemptLifecycle.AnswerCollection answerCollection, Instant startedAt, Instant submittedAt, Timing timing,
            ContentVisibility contentVisibility, List<Item> items, List<AttemptCommands.Type> allowedActions,
            uk.gegc.quizmaker.features.result.api.dto.ParticipantResultView result) {
        this(representation, schemaVersion, attemptId, quizId, quizRevisionId, formId, stateVersion, policyVersion, execution,
                answerCollection, startedAt, submittedAt, timing, contentVisibility, items, allowedActions, result, null);
    }
    public ParticipantAttemptView(Representation representation, int schemaVersion, UUID attemptId, UUID quizId,
            UUID quizRevisionId, UUID formId, long stateVersion, int policyVersion, AttemptLifecycle.Execution execution,
            AttemptLifecycle.AnswerCollection answerCollection, Instant startedAt, Instant submittedAt, Timing timing,
            ContentVisibility contentVisibility, List<Item> items, List<AttemptCommands.Type> allowedActions) {
        this(representation, schemaVersion, attemptId, quizId, quizRevisionId, formId, stateVersion, policyVersion,
                execution, answerCollection, startedAt, submittedAt, timing, contentVisibility, items, allowedActions, null);
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
