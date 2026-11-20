package uk.gegc.quizmaker.features.learning.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import uk.gegc.quizmaker.features.attempt.api.dto.AttemptFormResponse;
import uk.gegc.quizmaker.features.learning.application.*;
import uk.gegc.quizmaker.features.learning.domain.model.*;
import java.util.List;
import java.util.UUID;

/** Wire types for the online, single-publication Learning API. */
public final class LearningSessionApi {
    private LearningSessionApi() { }
    public enum SourceKind { QUIZ_REVISION }
    public enum Action { READ_SESSION }

    @Schema(name = "LearningQuizSource")
    public record SourceRef(@NotNull SourceKind kind, @NotNull UUID quizId, @NotNull UUID quizRevisionId,
                            @Positive int revisionSchemaVersion) {
        LearningSession.Source domain() {
            return new LearningSession.Source(1, quizId, quizRevisionId, revisionSchemaVersion);
        }
        static SourceRef from(LearningSession.Source s) {
            return new SourceRef(SourceKind.QUIZ_REVISION, s.quizId(), s.quizRevisionId(), s.revisionSchemaVersion());
        }
    }

    @Schema(name = "LearningConfiguration", description = "Exact supported configuration; unknown versions are rejected.")
    public record Configuration(@NotBlank @Schema(example = "fsrs/v1") String strategy, @Positive int version) {
        void verify() {
            LearningSessionException.require("fsrs/v1".equals(strategy) && version == 1,
                    LearningSessionException.Reason.UNSUPPORTED_VERSION);
        }
    }

    @Schema(name = "EnrollLearningItemsRequest")
    public record EnrollRequest(@NotNull UUID commandId, @NotNull @Valid SourceRef source,
                                @NotNull @Valid Configuration configuration,
                                @NotEmpty List<@NotNull UUID> questionRevisionIds) { }

    @Schema(name = "StartLearningSessionRequest")
    public record StartRequest(@NotNull UUID commandId, @NotNull @Valid SourceRef source,
                               @NotNull @Valid Configuration configuration,
                               @Positive @Schema(description = "Maximum due items to select, within the configured server bound.") int limit) { }

    @Schema(name = "EnrolledLearningItem")
    public record Cycle(UUID questionRevisionId, UUID cycleId, long cycleVersion) { }

    @Schema(name = "EnrollLearningItemsResponse")
    public record Enrolled(SourceRef source, List<Cycle> items) {
        static Enrolled from(LearningStudyService.Enrollment e) {
            return new Enrolled(SourceRef.from(e.source()), e.items().stream()
                    .map(i -> new Cycle(i.questionRevisionId(), i.cycleId(), i.cycleVersion())).toList());
        }
    }

    @Schema(name = "LearningSessionItem")
    public record Item(UUID sourceItemId, UUID questionRevisionId, String responseContract, int responseVersion,
                       String questionText, AttemptFormResponse.Content content) { }

    @Schema(name = "LearningSessionView", description = "Ordered items from the saved online session. EMPTY_COMPLETE is terminal.")
    public record View(UUID sessionId, UUID formId, SourceRef source, LearningForm.Outcome outcome,
                       int reviewedItems, List<Action> allowedActions, List<Item> items) {
        static View from(LearningFormService.View view) {
            var form = view.form();
            return new View(form.sessionId(), form.id(), SourceRef.from(form.source()), form.outcome(),
                    0, List.of(Action.READ_SESSION), view.items().stream().map(row -> {
                        var item = row.item();
                        return new Item(item.sourceItemId(), item.questionRevisionId(), item.response().id(),
                                item.response().version(), row.questionText(), AttemptFormResponse.textContent(row.content()));
                    }).toList());
        }
    }
}
