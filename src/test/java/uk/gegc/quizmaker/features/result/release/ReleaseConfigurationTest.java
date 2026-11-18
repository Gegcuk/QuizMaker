package uk.gegc.quizmaker.features.result.release;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.BuiltInPracticePreset;
import uk.gegc.quizmaker.features.result.domain.model.disclosure.ResultDisclosure;
import uk.gegc.quizmaker.features.result.domain.model.release.*;
import uk.gegc.quizmaker.features.result.domain.model.release.ReleaseConfiguration.*;
import uk.gegc.quizmaker.features.result.infra.release.ReleaseConfigurationFormat;
import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.result.domain.model.release.ReleaseConfigurationException.Reason.*;

@org.junit.jupiter.api.parallel.Isolated
class ReleaseConfigurationTest {
    static final String GOLDEN = "{\"version\":1,\"family\":\"FORMAL_ASSESSMENT\",\"triggers\":[\"MANUAL\"],\"categories\":[\"TOTAL_SCORE\",\"PASS_FAIL\"],\"schedule\":null,\"manualAuthority\":\"DEDICATED_ASSIGNMENT_AND_ORGANIZATION_PERMISSION\",\"notificationBoundary\":\"DURABLE_POST_COMMIT_OUTCOME_EVENTS\"}";
    static ReleaseConfiguration manual() { return ReleaseConfiguration.formal(1, Set.of(Trigger.MANUAL), null, null); }
    static ReleaseConfiguration scheduled(String local, String zone, String offset) {
        return ReleaseConfiguration.formal(1, Set.of(Trigger.SCHEDULED), null,
                new ScheduleInput(LocalDateTime.parse(local), zone, offset == null ? null : ZoneOffset.of(offset)));
    }
    @Test void canonicalGoldenAndFreshCodecRoundTripDoNotGrantExtraCategories() {
        var format = new ReleaseConfigurationFormat();
        assertThat(format.encode(manual())).isEqualTo(GOLDEN);
        assertThat(ReleaseConfigurationFormat.hash(GOLDEN)).isEqualTo("28cf953334257de18c927b6db3dbaa52ee3131aacdae6f58fbaf34361b51bf32");
        assertThat(new ReleaseConfigurationFormat().decode(GOLDEN, ReleaseConfigurationFormat.hash(GOLDEN))).isEqualTo(manual());
        assertThat(manual().categories()).containsExactly(ResultDisclosure.Category.TOTAL_SCORE, ResultDisclosure.Category.PASS_FAIL);
        assertThatThrownBy(() -> manual().triggers().add(Trigger.SCHEDULED)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(ReleaseConfiguration.formal(1, Set.of(Trigger.MANUAL), Set.of(), null).categories()).isEmpty();
    }
    @Test void categoryAndTriggerIterationOrderCannotChangeCanonicalMeaning() {
        var a = ReleaseConfiguration.formal(1, new LinkedHashSet<>(List.of(Trigger.SCHEDULED, Trigger.MANUAL)),
                new LinkedHashSet<>(List.of(ResultDisclosure.Category.PASS_FAIL, ResultDisclosure.Category.TOTAL_SCORE)),
                new ScheduleInput(LocalDateTime.parse("2026-12-04T17:00:00"), "Europe/London", null));
        var b = ReleaseConfiguration.formal(1, Set.of(Trigger.MANUAL, Trigger.SCHEDULED), null,
                new ScheduleInput(LocalDateTime.parse("2026-12-04T17:00:00"), "Europe/London", ZoneOffset.UTC));
        var format = new ReleaseConfigurationFormat();
        assertThat(format.encode(a)).isEqualTo(format.encode(b));
    }
    @Test void gapAndUnresolvedOverlapRejectWhileBothExplicitOccurrencesSucceed() {
        rejected(() -> scheduled("2026-03-29T01:30:00", "Europe/London", null), INVALID_SCHEDULE);
        rejected(() -> scheduled("2026-10-25T01:30:00", "Europe/London", null), INVALID_SCHEDULE);
        rejected(() -> scheduled("2026-10-25T01:30:00", "Europe/London", "+02:00"), INVALID_SCHEDULE);
        assertThat(scheduled("2026-10-25T01:30:00", "Europe/London", "+01:00").schedule().instant()).isEqualTo(Instant.parse("2026-10-25T00:30:00Z"));
        assertThat(scheduled("2026-10-25T01:30:00", "Europe/London", "Z").schedule().instant()).isEqualTo(Instant.parse("2026-10-25T01:30:00Z"));
    }
    @Test void capturedConversionDoesNotDependOnProcessTimezone() {
        var original = scheduled("2026-12-04T17:00:00.123456", "Europe/London", null);
        String bytes = new ReleaseConfigurationFormat().encode(original);
        var before = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"));
            assertThat(new ReleaseConfigurationFormat().decode(bytes, ReleaseConfigurationFormat.hash(bytes))).isEqualTo(original);
        } finally { TimeZone.setDefault(before); }
        // A read checks saved arithmetic rather than reapplying current zone rules.
        var evidence = new Schedule(LocalDateTime.parse("2026-12-04T17:00:00"), "Europe/London", ZoneOffset.ofHours(1), Instant.parse("2026-12-04T16:00:00Z"));
        assertThat(evidence.instant()).isEqualTo(Instant.parse("2026-12-04T16:00:00Z"));
    }
    @ParameterizedTest @ValueSource(strings = {"", "private-invalid-zone", "+01:00"})
    void invalidZonesHaveContentFreeErrors(String zone) { rejected(() -> scheduled("2026-12-04T17:00:00", zone, null), INVALID_SCHEDULE); }
    @Test void missingContradictoryUnknownAndUnrepresentableInputsFailClosed() {
        rejected(() -> ReleaseConfiguration.formal(2, Set.of(Trigger.MANUAL), null, null), UNSUPPORTED_VERSION);
        rejected(() -> ReleaseConfiguration.formal(1, null, null, null), INVALID_INPUT);
        rejected(() -> ReleaseConfiguration.formal(1, Set.of(), null, null), INVALID_INPUT);
        rejected(() -> ReleaseConfiguration.formal(1, Set.of(Trigger.IMMEDIATE, Trigger.MANUAL), null, null), INVALID_INPUT);
        rejected(() -> ReleaseConfiguration.formal(1, Set.of(Trigger.SCHEDULED), null, null), INVALID_SCHEDULE);
        rejected(() -> scheduled("2026-12-04T17:00:00.123456789", "Europe/London", null), INVALID_SCHEDULE);
        rejected(() -> scheduled("0999-12-04T17:00:00", "Europe/London", null), INVALID_SCHEDULE);
        rejected(() -> new Schedule(LocalDateTime.parse("2026-12-04T17:00:00"), "Europe/London", ZoneOffset.UTC, Instant.EPOCH), INVALID_SCHEDULE);
        var wrongHash = ReleaseConfigurationFormat.hash(GOLDEN.replace("MANUAL", "IMMEDIATE"));
        rejected(() -> new ReleaseConfigurationFormat().decode(GOLDEN, wrongHash), CORRUPT);
        for (String malformed : List.of("null", "{}", GOLDEN + "{}", GOLDEN.replace("\"version\":1", "\"version\":1,\"version\":1"),
                GOLDEN.replace("\"MANUAL\"", "\"private-unknown-trigger\""), GOLDEN.replace("\"version\":1", "\"version\":null")))
            rejected(() -> new ReleaseConfigurationFormat().decode(malformed, ReleaseConfigurationFormat.hash(malformed)), CORRUPT);
        var unsupported = GOLDEN.replace("\"version\":1", "\"version\":2");
        rejected(() -> new ReleaseConfigurationFormat().decode(unsupported, ReleaseConfigurationFormat.hash(unsupported)), UNSUPPORTED_VERSION);
    }
    @Test void practiceMappingIsExactlyTheExistingDisclosureContractWithoutChangingPolicy() {
        UUID revision = UUID.randomUUID();
        var binding = new ResultDisclosure.Binding(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), revision, "a".repeat(64));
        var policy = BuiltInPracticePreset.V1.bindContent(new ContentPolicy(revision, 1, FormMode.PRECOMPUTED, SectionMode.IMPLICIT_FLAT, CaseMode.NOT_CONFIGURED));
        var before = policy;
        var mapped = ReleaseConfiguration.practice(binding, policy);
        assertThat(mapped.categories()).isEqualTo(ResultDisclosure.Policy.practice(binding, policy).permitted()).doesNotContain(ResultDisclosure.Category.PASS_FAIL);
        assertThat(mapped.triggers()).containsExactly(Trigger.IMMEDIATE);
        assertThat(policy).isEqualTo(before);
    }
    static void rejected(Runnable action, ReleaseConfigurationException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ReleaseConfigurationException.class, ex -> {
            assertThat(ex.reason()).isEqualTo(reason);
            assertThat(ex).hasNoCause().hasMessage("Release configuration rejected: " + reason);
            assertThat(ex.getSuppressed()).isEmpty();
        });
    }
}
