package uk.gegc.quizmaker.features.learning.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import uk.gegc.quizmaker.features.attempt.api.dto.AcceptResponseRequest.*;
import uk.gegc.quizmaker.features.attempt.api.dto.AcceptResponseRequest.Response;
import uk.gegc.quizmaker.features.attempt.api.dto.AttemptFormResponse;
import uk.gegc.quizmaker.features.learning.application.*;
import uk.gegc.quizmaker.features.learning.domain.model.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Wire types for the online, single-publication Learning API. */
public final class LearningSessionApi {
    private LearningSessionApi() { }
    public enum SourceKind { QUIZ_REVISION }
    public enum Action { READ_SESSION, RESPOND }

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
                               @Positive @Schema(description = "Maximum due items to select, within the configured server bound. Use 1 with selection.") int limit,
                               @Valid @Schema(description = "Omit for the existing due selection.") Selection selection) {
        LearningStudyService.Start domain() {
            if (selection == null) return new LearningStudyService.Start(commandId, source.domain(), limit);
            return new LearningStudyService.Start(commandId, source.domain(), limit, selection.mode().domain(),
                    selection.questionRevisionId());
        }
    }

    @Schema(name = "LearningExplicitSelection", description = "One enrolled question from this publication. EARLY requires an item not yet due; PRACTICE keeps the schedule unchanged.")
    public record Selection(@NotNull SelectionMode mode, @NotNull UUID questionRevisionId) { }

    public enum SelectionMode {
        EARLY, PRACTICE;
        LearningReview.Mode domain() { return LearningReview.Mode.valueOf(name()); }
    }

    @Schema(name = "RespondToLearningItemRequest", description = "Online answer and self-rating for the next saved item. Repeat the same command ID and input after response loss.")
    public record RespondRequest(@NotNull UUID commandId, @NotNull @Positive Long expectedSessionVersion,
                                 @NotNull UUID sourceItemId, @NotNull LearningSchedule.Rating rating,
                                 @NotNull @Valid @Schema(oneOf = {Single.class, Multiple.class, Truth.class, Ordering.class,
                                         Gaps.class, Open.class, Matching.class, Compliance.class}) Response response) {
        @Override public String toString() { return "RespondToLearningItemRequest[redacted]"; }
    }

    @Schema(name = "LearningResponseAcknowledgement")
    public record Acknowledgement(UUID sessionId, UUID commandId, UUID sourceItemId, long sessionVersion,
                                  int reviewedItems, boolean completed,
                                  @Schema(nullable = true, description = "Next item in saved presentation order, or null at completion.") UUID nextItemId,
                                  @Schema(nullable = true, description = "Next scheduled review of the answered question in UTC. Null for extra practice.") Instant nextDueAt) {
        static Acknowledgement from(LearningReview.Acknowledgement ack) {
            var progress = ack.progress();
            return new Acknowledgement(ack.sessionId(), ack.commandId(), ack.sourceItemId(), progress.version(),
                    progress.reviewedItems(), progress.completed(), progress.nextItemId(), ack.nextDueAt());
        }
    }

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
                       int reviewedItems, List<Action> allowedActions, List<Item> items, LearningReview.Mode mode,
                       long sessionVersion, boolean completed, @Schema(nullable = true) UUID nextItemId) {
        static View from(LearningFormService.View view) {
            var form = view.form();
            var progress = view.progress();
            return new View(form.sessionId(), form.id(), SourceRef.from(form.source()), form.outcome(),
                    progress.reviewedItems(), progress.completed() ? List.of(Action.READ_SESSION)
                            : List.of(Action.READ_SESSION, Action.RESPOND), view.items().stream().map(row -> {
                        var item = row.item();
                        return new Item(item.sourceItemId(), item.questionRevisionId(), item.response().id(),
                                item.response().version(), row.questionText(), AttemptFormResponse.textContent(row.content()));
                    }).toList(), view.mode(), progress.version(), progress.completed(), progress.nextItemId());
        }
    }
}
