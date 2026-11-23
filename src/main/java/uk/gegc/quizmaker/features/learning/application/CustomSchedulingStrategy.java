package uk.gegc.quizmaker.features.learning.application;

import java.time.Instant;
import java.util.function.IntFunction;
import uk.gegc.quizmaker.features.learning.domain.model.CustomSchedule.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningPlan.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSchedule.Rating;

/** Deterministic custom/v1 calculation; history supplies only the requested retained stage. */
public interface CustomSchedulingStrategy {
    Transition review(Definition definition, State current, Rating rating, Instant at, IntFunction<Interval> history);
    Instant due(Interval interval, Instant reviewedAt, String zone, String localTime);
}
