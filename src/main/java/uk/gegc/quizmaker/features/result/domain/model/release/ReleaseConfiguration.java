package uk.gegc.quizmaker.features.result.domain.model.release;

import java.time.*;
import java.util.*;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;
import uk.gegc.quizmaker.features.result.domain.model.disclosure.ResultDisclosure;
import static uk.gegc.quizmaker.features.result.domain.model.release.ReleaseConfigurationException.*;
import static uk.gegc.quizmaker.features.result.domain.model.release.ReleaseConfigurationException.Reason.*;

/** Immutable configuration only; no release, grading, recipient or notification authority. */
public record ReleaseConfiguration(int version, ResultDisclosure.Family family, Set<Trigger> triggers,
        Set<ResultDisclosure.Category> categories, Schedule schedule, ManualAuthority manualAuthority,
        NotificationBoundary notificationBoundary) {
    public enum Trigger { IMMEDIATE, MANUAL, SCHEDULED }
    public enum ManualAuthority { DEDICATED_ASSIGNMENT_AND_ORGANIZATION_PERMISSION }
    public enum NotificationBoundary { DURABLE_POST_COMMIT_OUTCOME_EVENTS }

    public ReleaseConfiguration {
        require(version == 1, UNSUPPORTED_VERSION);
        require(family != null && triggers != null && !triggers.isEmpty() && categories != null
                && notificationBoundary == NotificationBoundary.DURABLE_POST_COMMIT_OUTCOME_EVENTS, INVALID_INPUT);
        require(triggers.stream().allMatch(Objects::nonNull) && categories.stream().allMatch(Objects::nonNull), INVALID_INPUT);
        triggers = Collections.unmodifiableSet(EnumSet.copyOf(triggers));
        categories = categories.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(categories));
        require(!triggers.contains(Trigger.IMMEDIATE) || triggers.size() == 1, INVALID_INPUT);
        require(triggers.contains(Trigger.SCHEDULED) == (schedule != null), INVALID_SCHEDULE);
        require(triggers.contains(Trigger.MANUAL) == (manualAuthority != null), INVALID_INPUT);
        require(family != ResultDisclosure.Family.PERSONAL_PRACTICE || triggers.equals(Set.of(Trigger.IMMEDIATE)), INVALID_INPUT);
    }

    public static ReleaseConfiguration formal(int version, Set<Trigger> triggers,
            Set<ResultDisclosure.Category> categories, ScheduleInput input) {
        return new ReleaseConfiguration(version, ResultDisclosure.Family.FORMAL_ASSESSMENT, triggers,
                categories == null ? Set.of(ResultDisclosure.Category.TOTAL_SCORE, ResultDisclosure.Category.PASS_FAIL) : categories,
                input == null ? null : input.resolve(), triggers != null && triggers.contains(Trigger.MANUAL)
                        ? ManualAuthority.DEDICATED_ASSIGNMENT_AND_ORGANIZATION_PERMISSION : null,
                NotificationBoundary.DURABLE_POST_COMMIT_OUTCOME_EVENTS);
    }

    /** Exact existing practice mapping, including its absence of a separate PASS_FAIL grant. */
    public static ReleaseConfiguration practice(ResultDisclosure.Binding binding, ResolvedAttemptPolicy policy) {
        return new ReleaseConfiguration(1, ResultDisclosure.Family.PERSONAL_PRACTICE, Set.of(Trigger.IMMEDIATE),
                ResultDisclosure.Policy.practice(binding, policy).permitted(), null, null,
                NotificationBoundary.DURABLE_POST_COMMIT_OUTCOME_EVENTS);
    }

    public record ScheduleInput(LocalDateTime localTime, String zoneId, ZoneOffset offset) {
        public Schedule resolve() {
            require(localTime != null && zoneId != null && zoneId.length() <= 100
                    && ZoneId.getAvailableZoneIds().contains(zoneId), INVALID_SCHEDULE);
            try {
                var offsets = ZoneId.of(zoneId).getRules().getValidOffsets(localTime);
                require(!offsets.isEmpty() && (offset != null ? offsets.contains(offset) : offsets.size() == 1), INVALID_SCHEDULE);
                var selected = offset == null ? offsets.get(0) : offset;
                return new Schedule(localTime, zoneId, selected, localTime.toInstant(selected));
            } catch (DateTimeException invalid) { throw new ReleaseConfigurationException(INVALID_SCHEDULE); }
        }
        @Override public String toString() { return "ReleaseScheduleInput"; }
    }

    /** Reads validate captured arithmetic, never reinterpret a stored instant with today's tzdb. */
    public record Schedule(LocalDateTime localTime, String zoneId, ZoneOffset offset, Instant instant) {
        public Schedule {
            require(localTime != null && zoneId != null && !zoneId.isBlank() && zoneId.length() <= 100
                    && offset != null && instant != null && localTime.getNano() % 1000 == 0, INVALID_SCHEDULE);
            require(localTime.getYear() >= 1000 && localTime.getYear() <= 9999
                    && LocalDateTime.ofInstant(instant, ZoneOffset.UTC).getYear() >= 1000
                    && LocalDateTime.ofInstant(instant, ZoneOffset.UTC).getYear() <= 9999
                    && localTime.toInstant(offset).equals(instant), INVALID_SCHEDULE);
        }
        @Override public String toString() { return "ReleaseSchedule"; }
    }
    @Override public String toString() { return "ReleaseConfiguration[version=" + version + "]"; }
}
