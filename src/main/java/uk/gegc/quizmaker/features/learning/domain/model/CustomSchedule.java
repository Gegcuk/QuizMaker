package uk.gegc.quizmaker.features.learning.domain.model;

import java.time.Instant;
import uk.gegc.quizmaker.features.learning.domain.model.LearningPlan.Interval;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.require;

/** Bounded current facts. Generated stage lineage is stored separately, only when reached. */
public final class CustomSchedule {
    private CustomSchedule() { }
    public record State(long version, long sequence, int stage, Instant reviewedAt, Instant dueAt,
                        String timeZone, boolean completed) {
        public State {
            require(version >= sequence && sequence >= 0 && stage >= 0 && dueAt != null
                    && (sequence == 0 ? reviewedAt == null && stage == 0 && !completed : reviewedAt != null), CORRUPT);
            if (timeZone != null) {
                try { java.time.ZoneId.of(timeZone); }
                catch (java.time.DateTimeException failure) { throw new uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException(CORRUPT); }
            }
        }
        @Override public String toString() { return "CustomScheduleState[redacted]"; }
    }
    public record Transition(State state, Interval generated) { }
}
