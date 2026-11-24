package uk.gegc.quizmaker.features.learning.domain.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSession.Source;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.require;

/** Private organization and read provenance only. No set owns a schedule or grants source access. */
public final class LearningSet {
    private LearningSet() { }
    public enum Kind { SET, QUIZ, QUESTION }
    public enum Window { DUE, UPCOMING }

    @Schema(name="LearningSetMember", description="SET requires only childSetId; QUIZ requires only source; QUESTION requires source and questionRevisionId. Sources are exact publications, never a moving quiz head.")
    public record Member(@NotNull Kind kind, UUID childSetId, Source source, UUID questionRevisionId) {
        public Member {
            require(kind != null, INVALID_INPUT);
            require(switch(kind) {
                case SET -> childSetId != null && source == null && questionRevisionId == null;
                case QUIZ -> childSetId == null && source != null && questionRevisionId == null;
                case QUESTION -> childSetId == null && source != null && questionRevisionId != null;
            }, INVALID_INPUT);
        }
        public String key() {
            return kind + "/" + (kind == Kind.SET ? childSetId : source.quizId()+"/"+source.quizRevisionId()+"/"+source.revisionSchemaVersion()+"/"+questionRevisionId);
        }
        @Override public String toString() { return "LearningSetMember[redacted]"; }
    }
    public record Scope(UUID setId, Source source) {
        public Scope { require((setId == null) != (source == null), INVALID_INPUT); }
    }
    @Schema(name="LearningSetView")
    public record View(@NotNull UUID setId, @NotBlank String name, @Positive long version, boolean deleted,
                       @NotNull Instant createdAt, @NotNull Instant updatedAt) {
        public View { require(setId!=null && name!=null && !name.isBlank() && version>0 && createdAt!=null && updatedAt!=null,CORRUPT); }
        @Override public String toString() { return "LearningSetView[redacted]"; }
    }
    @Schema(name="LearningSetAcknowledgement", description="Original durable response, including exact replay after later edits or deletion.")
    public record Acknowledgement(@NotNull UUID commandId, @NotNull View set, @Positive long graphVersion) {
        public Acknowledgement { require(commandId!=null && set!=null && graphVersion>0,CORRUPT); }
    }
    @Schema(name="LearningSetPage")
    public record SetPage(@NotNull List<View> items, UUID next) {
        public SetPage { items=List.copyOf(items); }
    }
    @Schema(name="LearningSetMemberPage")
    public record MemberPage(@NotNull List<Member> items, String next) {
        public MemberPage { items=List.copyOf(items); }
    }
    @Schema(name="LearningSelectionItem", description="One existing quiz-local cycle, shared across all entry points. Metadata does not start or authorize a review. UPCOMING identifies candidates for the separately supported explicit EARLY command.")
    public record Item(@NotNull Source source, @NotNull UUID questionRevisionId,
                       @NotNull LearningSchedule.Expected expected, @NotNull LearningPlan.Strategy strategy,
                       @NotNull Instant dueAt) { }
    @Schema(name="LearningSelectionPage", description="No total count or mastery. Paused/completed custom cycles and currently inaccessible sources are excluded. Reads never enroll, review, spend command credit or emit notifications.")
    public record ItemPage(@NotNull Window window, @NotNull Instant selectedAt,
                           @NotNull List<Item> items, String next) {
        public ItemPage { items=List.copyOf(items); }
    }
    /** Cursor is a position in a changing view, not a snapshot or an access grant. */
    public record Cursor(int version, Scope scope, Window window, long graphVersion,
                         Instant dueAt, UUID quizId, UUID revisionId, UUID questionId, String memberKey) { }
}
