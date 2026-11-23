package uk.gegc.quizmaker.features.learning.domain.repository;

import java.time.Instant;
import java.util.UUID;
import uk.gegc.quizmaker.features.learning.domain.model.CustomSchedule.State;
import uk.gegc.quizmaker.features.learning.domain.model.LearningPlan.*;
import uk.gegc.quizmaker.features.learning.domain.repository.LearningScheduleRepository.Key;

/** All reads/writes join the authorized RC transaction and the common cycle slot. */
public interface CustomScheduleRepository {
    State state(Key key, Cycle cycle, Definition definition);
    Interval stage(UUID cycleId, int stage);
    void change(Key key, Cycle cycle, State before, State after, UUID commandId, String kind,
                Interval generated, Instant at);
}
