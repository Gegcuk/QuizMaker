package uk.gegc.quizmaker.features.learning.application;

import java.util.UUID;
import uk.gegc.quizmaker.features.learning.domain.model.LearningPlan.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSchedule.Expected;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSession.Source;

/** Self-scoped, online plan management. No custom review command or implicit enrollment. */
public interface LearningPlanService {
    record Save(UUID commandId, long expectedVersion, Definition definition) {
        @Override public String toString() { return "SaveLearningPlan[redacted]"; }
    }
    record Pause(UUID commandId, long expectedVersion, boolean paused) {
        @Override public String toString() { return "PauseLearningPlan[redacted]"; }
    }
    /** A quiz default follows the plan head at enrollment; a question override pins planVersion.
     * Null planId clears the binding using its optimistic counter, retaining a tombstone. */
    record Bind(UUID commandId, Source source, UUID questionRevisionId, long expectedVersion,
                UUID planId, Long planVersion) {
        @Override public String toString() { return "BindLearningPlan[redacted]"; }
    }
    record Change(UUID commandId, Source source, UUID questionRevisionId, Action action,
                  Strategy strategy, Expected expected, boolean confirmed, UUID targetPlanId, Long targetPlanVersion) {
        @Override public String toString() { return "ChangeLearningCycle[redacted]"; }
    }
    Acknowledgement create(Save command);
    Acknowledgement edit(UUID planId, Save command);
    Acknowledgement pause(UUID planId, Pause command);
    View read(UUID planId);
    PlanPage list(int limit, UUID after);
    VersionPage history(UUID planId, int limit, long after);
    Acknowledgement bind(Bind command);
    Binding binding(Source source, UUID questionRevisionId);
    Acknowledgement change(Change command);
    Cycle cycle(Source source, UUID questionRevisionId);
    CyclePage cycles(Source source, Window window, int limit, Cursor after);
}
