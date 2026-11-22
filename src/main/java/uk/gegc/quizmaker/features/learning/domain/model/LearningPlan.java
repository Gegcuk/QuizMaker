package uk.gegc.quizmaker.features.learning.domain.model;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.require;

/** Private configuration and captured cycle facts. Review progression belongs to #840. */
public final class LearningPlan {
    private LearningPlan() { }
    public enum Unit { MINUTES, HOURS, DAYS }
    public enum Mode { FINITE, INDEFINITE }
    public enum Strategy { FSRS, CUSTOM }
    public enum Action { ENROLL, SWITCH, RESTART }
    public enum Window { ALL, DUE, UPCOMING }
    @io.swagger.v3.oas.annotations.media.Schema(name="LearningPlanInterval",description="Preserved scheduling unit. Whole-minute duration precision; DAYS is a positive integer calendar interval.")
    public record Interval(BigDecimal value, Unit unit) {
        public Interval {
            require(value != null && unit != null && value.signum() > 0, INVALID_INPUT);
            BigDecimal minutes = value.multiply(BigDecimal.valueOf(switch (unit) {
                case MINUTES -> 1; case HOURS -> 60; case DAYS -> 1440;
            }));
            require(minutes.compareTo(BigDecimal.ONE) >= 0
                    && minutes.compareTo(BigDecimal.valueOf(3650L * 1440)) <= 0, INVALID_INPUT);
            require(minutes.stripTrailingZeros().scale() <= 0
                    && (unit != Unit.DAYS || value.stripTrailingZeros().scale() <= 0), INVALID_INPUT);
            value = value.stripTrailingZeros();
        }
        BigDecimal nominalMinutes() {
            return value.multiply(BigDecimal.valueOf(unit == Unit.DAYS ? 1440 : unit == Unit.HOURS ? 60 : 1));
        }
    }
    @io.swagger.v3.oas.annotations.media.Schema(name="LearningPlanDefinition")
    public record Definition(int version,
                             @io.swagger.v3.oas.annotations.media.Schema(minLength=1,maxLength=200) String name,
                             @io.swagger.v3.oas.annotations.media.ArraySchema(minItems=1,maxItems=100) List<Interval> intervals,
                             Mode mode,
                             @io.swagger.v3.oas.annotations.media.Schema(minimum="1",defaultValue="2") BigDecimal coefficient,
                             @io.swagger.v3.oas.annotations.media.Schema(nullable=true,description="Explicit IANA zone required for calendar days.") String timeZone,
                             @io.swagger.v3.oas.annotations.media.Schema(nullable=true,description="Explicit ISO local time required for calendar days; no implicit 09:00 default.") String localTime) {
        public Definition {
            require(version == 1, UNSUPPORTED_VERSION);
            require(name != null && !name.isBlank() && name.length() <= 200
                    && name.codePoints().noneMatch(Character::isISOControl), INVALID_INPUT);
            require(intervals != null && !intervals.isEmpty() && intervals.size() <= 100
                    && intervals.stream().noneMatch(Objects::isNull) && mode != null, INVALID_INPUT);
            intervals = List.copyOf(intervals);
            for (int i = 1; i < intervals.size(); i++) require(
                    intervals.get(i - 1).nominalMinutes().compareTo(intervals.get(i).nominalMinutes()) <= 0, INVALID_INPUT);
            coefficient = coefficient == null ? BigDecimal.valueOf(2) : coefficient.stripTrailingZeros();
            require(coefficient.compareTo(BigDecimal.ONE) >= 0, INVALID_INPUT);
            boolean calendar = intervals.stream().anyMatch(i -> i.unit() == Unit.DAYS);
            require(!calendar || timeZone != null && localTime != null, INVALID_INPUT);
            require((timeZone == null) == (localTime == null), INVALID_INPUT);
            if (timeZone != null) {
                try { timeZone = ZoneId.of(timeZone).getId(); localTime = LocalTime.parse(localTime).toString(); }
                catch (DateTimeException failure) { throw new LearningSessionException(INVALID_INPUT); }
            }
        }
        @Override public String toString() { return "LearningPlanDefinition[redacted]"; }
    }
    /** version is the mutable head counter; planVersion identifies immutable configuration. */
    @io.swagger.v3.oas.annotations.media.Schema(name="LearningPlanView")
    public record View(UUID planId, long version, long planVersion, Definition definition, boolean paused,
                       Instant createdAt, Instant updatedAt) {
        public View { require(planId!=null && version>0 && planVersion>0 && planVersion<=version
                && definition!=null && createdAt!=null && updatedAt!=null,CORRUPT); }
        @Override public String toString() { return "LearningPlanView[redacted]"; }
    }
    @io.swagger.v3.oas.annotations.media.Schema(name="LearningPlanVersion")
    public record Version(long version, Definition definition, Instant createdAt) {
        public Version { require(version>0 && definition!=null && createdAt!=null,CORRUPT); }
        @Override public String toString() { return "LearningPlanVersion[redacted]"; }
    }
    @io.swagger.v3.oas.annotations.media.Schema(name="LearningPlanBinding")
    public record Binding(long version, @io.swagger.v3.oas.annotations.media.Schema(nullable=true) UUID planId,
                          @io.swagger.v3.oas.annotations.media.Schema(nullable=true) Long planVersion) {
        public Binding { require(version>=0 && (version!=0 || planId==null) && (planId!=null || planVersion==null)
                && (planVersion==null || planVersion>0),CORRUPT); }
        @Override public String toString() { return "LearningPlanBinding[redacted]"; }
    }
    @io.swagger.v3.oas.annotations.media.Schema(name="LearningPlanCycle")
    public record Cycle(UUID questionRevisionId, UUID cycleId, long cycleVersion, Strategy strategy,
                        UUID planId, Long planVersion, long sequence, int stage, Instant dueAt,
                        boolean paused, boolean completed) {
        public Cycle {
            require(questionRevisionId!=null && cycleId!=null && cycleVersion>0 && strategy!=null
                    && sequence>=0 && stage>=0 && dueAt!=null,CORRUPT);
            require(strategy==Strategy.CUSTOM ? planId!=null && planVersion!=null && planVersion>0
                    : planId==null && planVersion==null && !paused && !completed,CORRUPT);
        }
        public LearningSchedule.Expected expected() { return new LearningSchedule.Expected(cycleId, cycleVersion, sequence); }
        @Override public String toString() { return "LearningPlanCycle[redacted]"; }
    }
    @io.swagger.v3.oas.annotations.media.Schema(name="LearningPlanCursor")
    public record Cursor(Instant dueAt, UUID questionRevisionId) {
        @Override public String toString() { return "LearningPlanCursor[redacted]"; }
    }
    @io.swagger.v3.oas.annotations.media.Schema(name="LearningPlanPage")
    public record PlanPage(List<View> items, @io.swagger.v3.oas.annotations.media.Schema(nullable=true) UUID next) {
        public PlanPage { items = List.copyOf(items); }
        @Override public String toString() { return "LearningPlanPage[size="+items.size()+"]"; }
    }
    @io.swagger.v3.oas.annotations.media.Schema(name="LearningPlanVersionPage")
    public record VersionPage(List<Version> items, @io.swagger.v3.oas.annotations.media.Schema(nullable=true) Long next) {
        public VersionPage { items = List.copyOf(items); }
        @Override public String toString() { return "LearningPlanVersionPage[size="+items.size()+"]"; }
    }
    @io.swagger.v3.oas.annotations.media.Schema(name="LearningPlanCyclePage")
    public record CyclePage(List<Cycle> items, @io.swagger.v3.oas.annotations.media.Schema(nullable=true) Cursor next) {
        public CyclePage { items = List.copyOf(items); }
        @Override public String toString() { return "LearningPlanCyclePage[size="+items.size()+"]"; }
    }
    @io.swagger.v3.oas.annotations.media.Schema(name="LearningPlanAcknowledgement",description="Exactly one of plan, binding or cycle is non-null, according to the command. Replay retains the original result.")
    public record Acknowledgement(UUID commandId,
            @io.swagger.v3.oas.annotations.media.Schema(nullable=true) View plan,
            @io.swagger.v3.oas.annotations.media.Schema(nullable=true) Binding binding,
            @io.swagger.v3.oas.annotations.media.Schema(nullable=true) Cycle cycle) {
        public Acknowledgement { require(commandId!=null && (plan==null?0:1)+(binding==null?0:1)+(cycle==null?0:1)==1,CORRUPT); }
        @Override public String toString() { return "LearningPlanAcknowledgement[redacted]"; }
    }
}
