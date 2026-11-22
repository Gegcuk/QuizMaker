package uk.gegc.quizmaker.features.learning.domain.repository;

import java.time.Instant;
import java.util.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningPlan.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSession.Source;
import uk.gegc.quizmaker.features.learning.domain.repository.LearningScheduleRepository.Key;

/** Requires the authorized RC owner. Plan heads and the shared schedule slot serialize writes. */
public interface LearningPlanRepository {
    record Receipt(String input, Acknowledgement result) {
        @Override public String toString() { return "LearningPlanReceipt[redacted]"; }
    }
    Optional<Receipt> receipt(UUID learner, UUID command);
    void record(UUID learner, UUID command, String input, Acknowledgement result, Instant now);
    Optional<View> plan(UUID learner, UUID plan, boolean lock);
    void save(UUID learner, View plan, boolean configurationChanged);
    List<View> plans(UUID learner, int take, UUID after);
    List<Version> versions(UUID learner, UUID plan, int take, long after);
    Optional<Version> version(UUID learner, UUID plan, long version);
    Binding binding(UUID learner, Source source, UUID question);
    void bind(UUID learner, Source source, UUID question, Binding binding);
    Optional<Cycle> custom(Key key);
    void createCustom(Key key, Cycle cycle);
    List<Cycle> cycles(UUID learner, Source source, Window window, Instant now, int take, Cursor after);
}
