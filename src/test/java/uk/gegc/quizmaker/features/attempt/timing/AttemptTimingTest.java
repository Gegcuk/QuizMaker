package uk.gegc.quizmaker.features.attempt.timing;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.attempt.application.impl.*;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.*;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.*;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.*;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionRequest.*;
import uk.gegc.quizmaker.features.attempt.domain.model.timing.*;
import uk.gegc.quizmaker.features.attempt.infra.policy.*;
import static org.assertj.core.api.Assertions.*;

class AttemptTimingTest {
    static final Instant START = Instant.parse("2026-11-05T17:45:00.123456Z");
    static final UUID ATTEMPT = UUID.randomUUID(), SNAPSHOT = UUID.randomUUID();
    @Test void overallDeadlineAndDurationStaySeparateAndEarlierAlwaysWins() {
        for (Duration duration : Arrays.asList(null, Duration.ofMinutes(10), Duration.ofMinutes(30))) {
            for (Instant close : Arrays.asList(null, START.plusSeconds(900), START.plusSeconds(600))) {
                if (duration == null && close == null) continue;
                var policy = new TimingPolicy(TimingMode.TOTAL_ATTEMPT, duration, close);
                var captured = AttemptTiming.capture(ATTEMPT, SNAPSHOT, policy, START);
                Instant durationEnd = duration == null ? null : START.plus(duration);
                Instant expected = durationEnd == null ? close : close == null || durationEnd.isBefore(close) ? durationEnd : close;
                assertThat(captured.durationDeadline()).isEqualTo(durationEnd);
                assertThat(captured.effectiveDeadline()).isEqualTo(expected);
                assertThat(captured.grace()).isEqualTo(Duration.ZERO);
                assertThat(captured.decide(START.minusNanos(1))).isEqualTo(AttemptTiming.Decision.BEFORE_START);
                assertThat(captured.decide(START)).isEqualTo(AttemptTiming.Decision.OPEN);
                assertThat(captured.decide(expected.minusNanos(1))).isEqualTo(AttemptTiming.Decision.OPEN);
                assertThat(captured.decide(expected)).isEqualTo(AttemptTiming.Decision.EXPIRED);
                assertThat(captured.decide(expected.plusNanos(1))).isEqualTo(AttemptTiming.Decision.EXPIRED);
            }
        }
    }
    @ParameterizedTest @ValueSource(longs = {-1,0}) void closedStartCannotCreateTiming(long offset) {
        var policy = new TimingPolicy(TimingMode.TOTAL_ATTEMPT, Duration.ofMinutes(30), START.plusSeconds(offset));
        assertThatThrownBy(() -> AttemptTiming.capture(ATTEMPT,SNAPSHOT,policy,START))
                .isInstanceOfSatisfying(AttemptTimingException.class,e -> assertThat(e.reason()).isEqualTo(AttemptTimingException.Reason.CLOSED));
    }
    @Test void invalidPrecisionOverflowMissingLimitsAndInventedGraceFailClosed() {
        for (Duration invalid : List.of(Duration.ZERO,Duration.ofNanos(1),Duration.ofSeconds(-1)))
            assertThatThrownBy(() -> new TimingPolicy(TimingMode.TOTAL_ATTEMPT,invalid,null)).isInstanceOf(PolicyValidationException.class);
        assertThatThrownBy(() -> new TimingPolicy(TimingMode.TOTAL_ATTEMPT,null,null)).isInstanceOf(PolicyValidationException.class);
        assertThatThrownBy(() -> new TimingPolicy(TimingMode.NONE,Duration.ofSeconds(1),null)).isInstanceOf(PolicyValidationException.class);
        assertThatThrownBy(() -> new TimingPolicy(TimingMode.TOTAL_ATTEMPT,null,START.plusNanos(1))).isInstanceOf(PolicyValidationException.class);
        assertThatThrownBy(() -> AttemptTiming.capture(ATTEMPT,SNAPSHOT,new TimingPolicy(TimingMode.TOTAL_ATTEMPT,Duration.ofSeconds(Long.MAX_VALUE),null),START))
                .isInstanceOf(AttemptTimingException.class).hasNoCause();
        var original = AttemptTiming.capture(ATTEMPT,SNAPSHOT,new TimingPolicy(TimingMode.TOTAL_ATTEMPT,Duration.ofSeconds(1),null),START);
        assertThatThrownBy(() -> new AttemptTiming(2,ATTEMPT,SNAPSHOT,original.policy(),START,original.durationDeadline(),original.effectiveDeadline(),Duration.ZERO))
                .isInstanceOfSatisfying(AttemptTimingException.class,e -> assertThat(e.reason()).isEqualTo(AttemptTimingException.Reason.UNSUPPORTED));
        assertThatThrownBy(() -> new AttemptTiming(1,ATTEMPT,SNAPSHOT,original.policy(),START,original.durationDeadline(),original.effectiveDeadline().plusSeconds(1),Duration.ZERO))
                .isInstanceOf(AttemptTimingException.class);
        assertThatThrownBy(() -> new AttemptTiming(1,ATTEMPT,SNAPSHOT,original.policy(),START,original.durationDeadline(),original.effectiveDeadline(),Duration.ofSeconds(1)))
                .isInstanceOf(AttemptTimingException.class);
        assertThatThrownBy(() -> original.decide(null)).isInstanceOf(AttemptTimingException.class);
    }
    @Test void timingIsAnExplicitAuthorOverrideAndPreservesExactCanonicalEvidence() throws Exception {
        var timing = new TimingPolicy(TimingMode.TOTAL_ATTEMPT,Duration.ofSeconds(1800,123_000),START.plusSeconds(900));
        var resolved=resolve(timing,OverrideSource.AUTHOR,true);
        assertThat(new CanonicalPolicyFormat().verify(resolved)).isEqualTo(resolved);
        assertThat(resolved.canonicalJson()).contains("\"durationNanos\":123000", "\"durationSeconds\":1800", "\"overallDeadlineNanos\":123456000");
        assertThat(resolved.provenance().entrySet().stream().filter(e -> e.getKey().startsWith("timing.")))
                .allSatisfy(e -> assertThat(e.getValue()).isEqualTo(new PolicyResolution.ValueSource(PolicyResolution.Source.AUTHOR,1)));
        assertThat(resolve(new TimingPolicy(TimingMode.TOTAL_ATTEMPT,Duration.ofMinutes(31),timing.overallDeadline()),OverrideSource.AUTHOR,true).hash()).isNotEqualTo(resolved.hash());
        assertThatThrownBy(() -> resolve(timing,OverrideSource.PARTICIPANT,true)).isInstanceOf(PolicyResolutionException.class);
        assertThatThrownBy(() -> resolve(timing,OverrideSource.AUTHOR,false)).isInstanceOf(PolicyResolutionException.class);
        var codec = com.fasterxml.jackson.databind.json.JsonMapper.builder().addModule(TimingPolicyJson.module()).build();
        assertThat(codec.writeValueAsString(new TimingPolicy(TimingMode.NONE))).isEqualTo("{\"mode\":\"NONE\"}");
        for (String bad : List.of("{\"mode\":\"NONE\",\"durationSeconds\":0}",
                "{\"mode\":\"TOTAL_ATTEMPT\",\"durationSeconds\":1,\"durationNanos\":1000000000}",
                "{\"mode\":\"NONE\",\"grace\":1}", "{\"mode\":\"TOTAL_ATTEMPT\"}",
                "{\"mode\":\"TOTAL_ATTEMPT\",\"durationSeconds\":1.0,\"durationNanos\":0}"))
            assertThatThrownBy(() -> codec.readValue(bad,TimingPolicy.class)).isInstanceOf(RuntimeException.class);
    }
    static PolicyResolution resolve(TimingPolicy timing, OverrideSource source, boolean allowed) {
        var content = new ContentPolicy(UUID.fromString("00000000-0000-0000-0000-000000000042"),1,FormMode.PRECOMPUTED,SectionMode.IMPLICIT_FLAT,CaseMode.NOT_CONFIGURED);
        return new AttemptPolicyResolverImpl(new AttemptPresetServiceImpl(),new SimpleMeterRegistry()).resolve(new PolicyResolutionRequest(1,1,"SHA-256",BuiltInPracticePreset.REFERENCE,content,
                List.of(new SourceOverrides(source,1,new AllowedChoices(Set.of(),Set.of(),Set.of(),Set.of(),Set.of(),Set.of(),Set.of(),allowed?Set.of(timing):Set.of()),List.of(new TimingChoice(timing))))));
    }
}
