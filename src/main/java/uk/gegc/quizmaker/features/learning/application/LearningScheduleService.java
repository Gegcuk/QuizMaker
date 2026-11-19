package uk.gegc.quizmaker.features.learning.application;

import java.util.UUID;
import uk.gegc.quizmaker.features.learning.domain.model.FsrsManifest;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSchedule.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSession.Source;

/** Dark self-scoped commands. No endpoint or implicit enrollment. Returns inside an outer RC
 * transaction are provisional. Reuse the same command ID and input after an uncertain commit.
 * #578/#650 own public enrollment/session orchestration; #839 reuses the common cycle authority. */
public interface LearningScheduleService {
    record Result(Snapshot snapshot, boolean replayed) { }
    sealed interface Command permits Enroll, Rate, Restart, Capture, Release {
        int version(); UUID id(); Source source(); UUID questionRevisionId();
    }
    record Enroll(int version, UUID id, Source source, UUID questionRevisionId, FsrsManifest manifest) implements Command {
        @Override public String toString() { return "EnrollLearningItem[redacted]"; }
    }
    record Rate(int version, UUID id, Source source, UUID questionRevisionId, Expected expected, Rating rating) implements Command {
        @Override public String toString() { return "RateLearningItem[redacted]"; }
    }
    /** Explicit new cycle, never a parameter migration or inferred progress transfer. */
    record Restart(int version, UUID id, Source source, UUID questionRevisionId, Expected expected, FsrsManifest manifest) implements Command {
        @Override public String toString() { return "RestartLearningItem[redacted]"; }
    }
    record Capture(int version, UUID id, Source source, UUID questionRevisionId, Expected expected, UUID sessionId) implements Command {
        @Override public String toString() { return "CaptureLearningCycle[redacted]"; }
    }
    /** Requires authoritative completion from the later session owner; never completes a session. */
    record Release(int version, UUID id, Source source, UUID questionRevisionId, Expected expected, UUID sessionId) implements Command {
        @Override public String toString() { return "ReleaseLearningCycle[redacted]"; }
    }
    Result execute(Command command);
    Snapshot read(Source source, UUID questionRevisionId);
}
