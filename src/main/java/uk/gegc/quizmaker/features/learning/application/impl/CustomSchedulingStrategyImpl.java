package uk.gegc.quizmaker.features.learning.application.impl;

import org.springframework.stereotype.Component;
import java.math.*;
import java.time.*;
import java.util.function.IntFunction;
import uk.gegc.quizmaker.features.learning.application.CustomSchedulingStrategy;
import uk.gegc.quizmaker.features.learning.domain.model.CustomSchedule.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningPlan.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSchedule.Rating;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.require;

@Component
public class CustomSchedulingStrategyImpl implements CustomSchedulingStrategy {
    @Override public Transition review(Definition d, State s, Rating rating, Instant at, IntFunction<Interval> history) {
        require(d != null && s != null && rating != null && at != null && history != null, INVALID_INPUT);
        require(!s.completed() && s.version() < Long.MAX_VALUE && s.sequence() < Long.MAX_VALUE
                && (s.reviewedAt() == null || !at.isBefore(s.reviewedAt())), CONFLICT);
        int stage = s.sequence() == 0 ? 0 : rating == Rating.AGAIN ? Math.max(0, s.stage() - 2) : s.stage() + 1;
        boolean completed = d.mode() == Mode.FINITE && stage == d.intervals().size();
        Interval generated = null;
        Instant due = s.dueAt();
        if (completed) stage = s.stage();
        else {
            Interval interval;
            if (stage < d.intervals().size()) interval = d.intervals().get(stage);
            else if (stage <= s.stage()) interval = history.apply(stage);
            else {
                var previous = s.stage() < d.intervals().size() ? d.intervals().get(s.stage()) : history.apply(s.stage());
                boolean days = previous.unit() == Unit.DAYS;
                BigDecimal base = previous.value().multiply(BigDecimal.valueOf(previous.unit() == Unit.HOURS ? 60 : 1));
                BigDecimal cap = BigDecimal.valueOf(days ? 3650 : 3650L * 1440);
                BigDecimal value = d.coefficient().compareTo(cap) > 0 ? cap
                        : base.multiply(d.coefficient()).min(cap).setScale(0, RoundingMode.CEILING);
                interval = new Interval(value, days ? Unit.DAYS : Unit.MINUTES);
                if (value.compareTo(base) == 0) stage = s.stage();
                else generated = interval;
            }
            due = due(interval, at, s.timeZone(), d.localTime());
        }
        return new Transition(new State(s.version() + 1, s.sequence() + 1, stage, at, due,
                s.timeZone(), completed), generated);
    }

    @Override public Instant due(Interval interval, Instant reviewedAt, String zone, String localTime) {
        try {
            Instant result;
            if (interval.unit() != Unit.DAYS) {
                long minutes = interval.value().multiply(BigDecimal.valueOf(interval.unit() == Unit.HOURS ? 60 : 1)).longValueExact();
                result = reviewedAt.plusSeconds(Math.multiplyExact(minutes, 60));
            } else {
                var id = ZoneId.of(zone);
                var local = LocalDateTime.of(reviewedAt.atZone(id).toLocalDate().plusDays(interval.value().longValueExact()), LocalTime.parse(localTime));
                var offsets = id.getRules().getValidOffsets(local);
                // atZone's gap adjustment preserves minutes; Q109 instead selects the first valid time.
                result = offsets.isEmpty() ? id.getRules().getTransition(local).getInstant() : local.toInstant(offsets.get(0));
            }
            require(!result.isBefore(Instant.parse("1000-01-01T00:00:00Z"))
                    && result.isBefore(Instant.parse("+10000-01-01T00:00:00Z")), INVALID_INPUT);
            return result;
        } catch (DateTimeException | ArithmeticException | NullPointerException failure) {
            throw new LearningSessionException(INVALID_INPUT);
        }
    }
}
