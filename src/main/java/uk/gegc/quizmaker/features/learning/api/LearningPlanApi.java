package uk.gegc.quizmaker.features.learning.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;
import uk.gegc.quizmaker.features.learning.domain.model.LearningPlan.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSchedule.Expected;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSession.Source;

public final class LearningPlanApi {
    private LearningPlanApi() { }
    @Schema(name="SaveLearningPlanRequest")
    public record SaveRequest(@NotNull UUID commandId,@NotNull @PositiveOrZero Long expectedVersion,
                              @NotNull @Valid Definition definition) {
        @Override public String toString() { return "SaveLearningPlanRequest[redacted]"; }
    }
    @Schema(name="PauseLearningPlanRequest")
    public record PauseRequest(@NotNull UUID commandId,@NotNull @Positive Long expectedVersion,@NotNull Boolean paused) {
        @Override public String toString() { return "PauseLearningPlanRequest[redacted]"; }
    }
    @Schema(name="BindLearningPlanRequest",description="Without questionRevisionId, bind a quiz default following the plan head; omit planVersion. A question override pins planVersion. Null planId clears the binding.")
    public record BindRequest(@NotNull UUID commandId,@NotNull @Valid LearningSessionApi.SourceRef source,
                              UUID questionRevisionId,@NotNull @PositiveOrZero Long expectedVersion,UUID planId,@Positive Long planVersion) {
        @Override public String toString() { return "BindLearningPlanRequest[redacted]"; }
    }
    @Schema(name="ChangeLearningPlanCycleRequest",description="ENROLL needs null expected and confirmed=false. SWITCH/RESTART require expected cycle/sequence and confirmed=true. Custom RESTART requires completed state from #840; this API does not complete a plan.")
    public record ChangeRequest(@NotNull UUID commandId,@NotNull @Valid LearningSessionApi.SourceRef source,
                                @NotNull UUID questionRevisionId,@NotNull Action action,@NotNull Strategy strategy,
                                @Valid ExpectedRequest expected,@NotNull Boolean confirmed,
                                @Schema(nullable=true,description="Required for CUSTOM SWITCH/RESTART; exact chosen plan, null for enrollment/FSRS.") UUID targetPlanId,
                                @Positive @Schema(nullable=true,description="Required immutable target version for CUSTOM SWITCH/RESTART. A later default/plan edit makes confirmation conflict.") Long targetPlanVersion) {
        @Override public String toString() { return "ChangeLearningPlanCycleRequest[redacted]"; }
    }
    @Schema(name="ExpectedLearningPlanCycle")
    public record ExpectedRequest(@NotNull UUID cycleId,@NotNull @Positive Long cycleVersion,@NotNull @PositiveOrZero Long sequence) {
        Expected domain() { return new Expected(cycleId,cycleVersion,sequence); }
        @Override public String toString() { return "ExpectedLearningPlanCycle[redacted]"; }
    }
    public record SourceQuery(@NotNull UUID quizId,@NotNull UUID quizRevisionId,@Positive int revisionSchemaVersion) {
        Source domain() { return new Source(1,quizId,quizRevisionId,revisionSchemaVersion); }
        @Override public String toString() { return "LearningPlanSourceQuery[redacted]"; }
    }
}
