package uk.gegc.quizmaker.features.result.notification;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.BuiltInPracticePreset;
import uk.gegc.quizmaker.features.question.application.contract.v1.BaselineQuestionContracts;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.result.domain.model.*;
import uk.gegc.quizmaker.features.result.domain.model.disclosure.ResultDisclosure;
import uk.gegc.quizmaker.features.result.domain.model.disclosure.ResultDisclosure.Category;
import uk.gegc.quizmaker.features.result.domain.model.notification.*;
import static uk.gegc.quizmaker.features.result.domain.model.notification.ResultNotificationEvent.*;
import static uk.gegc.quizmaker.features.result.domain.model.notification.ResultNotificationEvents.*;
import static org.assertj.core.api.Assertions.*;

/** Conditional fixtures prove classification only, never certificate/correction runtime writes. */
class ResultNotificationEventTest {
    static final Instant AT = Instant.parse("2026-11-19T12:00:00.123456789Z");
    static final UUID TARGET = UUID.randomUUID();
    static final Set<Category> SCORE = Set.of(Category.TOTAL_SCORE);
    static final Set<Category> FORMAL = Set.of(Category.TOTAL_SCORE, Category.PASS_FAIL);
    static Source source(SourceKind kind, long version) { return new Source(kind, new UUID(1, 2), version); }
    static ResultFacts visible(long score) { return facts(true, SCORE, score, null, Validity.VALID); }
    static ResultFacts facts(boolean available, Set<Category> categories, long score, Boolean passed, Validity validity) {
        return new ResultFacts(available, categories, new Total(score, 100, (int) score * 100), passed, validity);
    }
    @Test void firstReleaseReusesActualIdentityAndRetainsOnlyPermittedCategoryReferences() {
        var result = result(); var release = new ResultRelease(UUID.randomUUID(), 1, result.id(), AT);
        var policy = ResultDisclosure.Policy.practice(ResultDisclosure.Binding.of(result), BuiltInPracticePreset.V1.bindContent(
                new ContentPolicy(result.quizRevisionId(), 1, FormMode.PRECOMPUTED, SectionMode.IMPLICIT_FLAT, CaseMode.NOT_CONFIGURED)));
        var event = firstRelease(result, release, policy);
        assertThat(event.id()).isEqualTo(release.id()); assertThat(event.source()).isEqualTo(new Source(SourceKind.RESULT_RELEASE, release.id(), 1));
        assertThat(event.kind()).isEqualTo(Kind.RESULT_OPENED); assertThat(event.targetId()).isEqualTo(result.id());
        assertThat(event.categories()).containsExactly(Category.TOTAL_SCORE); assertThat(event.previousTargetId()).isNull();
        assertThat(event.occurredAt()).isEqualTo(release.releasedAt()); assertThat(firstRelease(result, release, policy)).isEqualTo(event);
        assertThatThrownBy(() -> firstRelease(result, new ResultRelease(UUID.randomUUID(), 1, UUID.randomUUID(), AT), policy)).isInstanceOf(ResultEventException.class);
        assertThatThrownBy(() -> firstRelease(result, release, ResultDisclosure.Policy.formalDefault(ResultDisclosure.Binding.of(result)))).isInstanceOf(ResultEventException.class);
        assertThatThrownBy(() -> firstRelease(result, new ResultRelease(UUID.randomUUID(), 1, result.id(), result.createdAt().minusNanos(1000)), policy)).isInstanceOf(ResultEventException.class);
    }
    @Test void visibleSeventyToSeventyFiveToEightyPreservesBothChangesAndReplayIdentity() {
        var first = resultChange(source(SourceKind.SELECTED_RESULT, 1), TARGET, AT, visible(70), visible(75));
        var second = resultChange(source(SourceKind.SELECTED_RESULT, 2), TARGET, AT, visible(75), visible(80));
        assertThat(first).extracting(ResultNotificationEvent::kind).containsExactly(Kind.TOTAL_SCORE_CHANGED);
        assertThat(second).extracting(ResultNotificationEvent::kind).containsExactly(Kind.TOTAL_SCORE_CHANGED);
        assertThat(first.get(0).id()).isNotEqualTo(second.get(0).id());
        assertThat(resultChange(source(SourceKind.SELECTED_RESULT, 1), TARGET, AT, visible(70), visible(75))).isEqualTo(first);
        assertThat(resultChange(source(SourceKind.SELECTED_RESULT, 3), TARGET, AT, visible(80), visible(70)))
                .extracting(ResultNotificationEvent::kind).containsExactly(Kind.TOTAL_SCORE_CHANGED);
    }
    @Test void hiddenUnchangedAndInternalOnlyVersionsDoNotBecomeScoreEvents() {
        var source = source(SourceKind.SELECTED_RESULT, 2);
        assertThat(resultChange(source, TARGET, AT, visible(70), visible(70))).isEmpty();
        var hiddenBefore = facts(false, SCORE, 70, null, Validity.VALID);
        var hiddenAfter = facts(false, SCORE, 80, null, Validity.VALID);
        assertThat(resultChange(source, TARGET, AT, hiddenBefore, hiddenAfter)).isEmpty();
        assertThat(resultChange(source, TARGET, AT, facts(true, Set.of(), 70, null, Validity.VALID), facts(true, Set.of(), 80, null, Validity.VALID))).isEmpty();
        assertThat(resultChange(source, TARGET, AT, facts(true, Set.of(), 70, null, Validity.VALID), visible(80))).isEmpty();
        assertThat(resultChange(source, TARGET, AT, visible(70), hiddenAfter)).isEmpty();
        assertThat(resultChange(source(SourceKind.RESULT_RELEASE, 1), TARGET, AT, hiddenBefore, visible(80)))
                .extracting(ResultNotificationEvent::kind).containsExactly(Kind.RESULT_OPENED);
    }
    @Test void exactVisiblePointsMatterEvenWhenNormalizedRoundingIsUnchanged() {
        var before = new ResultFacts(true, SCORE, new Total(9999, 200000001, 0), null, Validity.VALID);
        var after = new ResultFacts(true, SCORE, new Total(10000, 200000001, 0), null, Validity.VALID);
        assertThat(resultChange(source(SourceKind.SELECTED_RESULT, 1), TARGET, AT, before, after)).hasSize(1);
    }
    @Test void scoreAndVisibleStatusChangesHaveDistinctIdentitiesAndCategoryGrants() {
        var before = facts(true, FORMAL, 70, true, Validity.VALID);
        var after = facts(true, FORMAL, 60, false, Validity.INVALID);
        var events = resultChange(source(SourceKind.SELECTED_RESULT, 1), TARGET, AT, before, after);
        assertThat(events).extracting(ResultNotificationEvent::kind).containsExactly(Kind.TOTAL_SCORE_CHANGED, Kind.RESULT_STATUS_CHANGED);
        assertThat(events).extracting(ResultNotificationEvent::id).doesNotHaveDuplicates();
        assertThat(events.get(0).categories()).containsExactly(Category.TOTAL_SCORE); assertThat(events.get(1).categories()).containsExactly(Category.PASS_FAIL);
        assertThat(resultChange(source(SourceKind.SELECTED_RESULT, 2), TARGET, AT, visible(70), facts(true, SCORE, 70, null, Validity.INVALID)))
                .extracting(ResultNotificationEvent::kind).containsExactly(Kind.RESULT_STATUS_CHANGED);
        assertThat(resultChange(source(SourceKind.SELECTED_RESULT, 3), TARGET, AT, facts(true, Set.of(), 70, true, Validity.VALID),
                facts(true, Set.of(), 70, false, Validity.VALID))).isEmpty();
    }
    @Test void issuanceReplacementRevocationClarificationAndExpiryAreClassifiedIndependently() {
        var id = UUID.randomUUID(); var next = UUID.randomUUID(); var reason = UUID.randomUUID();
        var absent = new CertificateFacts(CertificateStatus.ABSENT, null, null);
        var valid = new CertificateFacts(CertificateStatus.VALID, id, null);
        var replacement = new CertificateFacts(CertificateStatus.VALID, next, null);
        var revoked = new CertificateFacts(CertificateStatus.REVOKED, id, null);
        var clarified = new CertificateFacts(CertificateStatus.REVOKED, id, reason);
        var issuer = certificateChange(source(SourceKind.CERTIFICATE, 1), AT, absent, valid).get(0);
        var replace = certificateChange(source(SourceKind.CERTIFICATE, 2), AT, valid, replacement).get(0);
        var revoke = certificateChange(source(SourceKind.CERTIFICATE, 3), AT, valid, revoked).get(0);
        var clarify = certificateChange(source(SourceKind.CERTIFICATE, 4), AT, revoked, clarified).get(0);
        assertThat(List.of(issuer, replace, revoke, clarify)).extracting(ResultNotificationEvent::kind).containsExactly(
                Kind.CERTIFICATE_ISSUED, Kind.CERTIFICATE_REPLACED, Kind.CERTIFICATE_REVOKED, Kind.CERTIFICATE_REASON_CLARIFIED);
        assertThat(List.of(issuer, replace, revoke, clarify)).extracting(ResultNotificationEvent::id).doesNotHaveDuplicates();
        assertThat(replace.previousTargetId()).isEqualTo(id); assertThat(replace.targetId()).isEqualTo(next);
        assertThat(revoke.categories()).isEmpty(); assertThat(clarify.categories()).isEmpty();
        assertThat(certificateChange(source(SourceKind.CERTIFICATE, 5), AT, clarified, clarified)).isEmpty();
        assertThat(certificateChange(source(SourceKind.CERTIFICATE, 6), AT, valid, new CertificateFacts(CertificateStatus.EXPIRED, id, null))).isEmpty();
        assertThat(certificateChange(source(SourceKind.CERTIFICATE, 7), AT, clarified,
                new CertificateFacts(CertificateStatus.REVOKED, id, UUID.randomUUID())))
                .extracting(ResultNotificationEvent::kind).containsExactly(Kind.CERTIFICATE_REASON_CLARIFIED);
    }
    @Test void aVisibleScoreDoesNotGrantAccessToAWithheldValidityChange() {
        var before = facts(true, SCORE, 70, null, null);
        var after = facts(true, SCORE, 70, null, null);
        assertThat(resultChange(source(SourceKind.SELECTED_RESULT, 1), TARGET, AT, before, after)).isEmpty();
        assertThat(resultChange(source(SourceKind.SELECTED_RESULT, 2), TARGET, AT, visible(70), after)).isEmpty();
        assertThat(resultChange(source(SourceKind.SELECTED_RESULT, 3), TARGET, AT, before,
                facts(true, SCORE, 70, null, Validity.INVALID))).isEmpty();
        assertThat(resultChange(source(SourceKind.SELECTED_RESULT, 4), TARGET, AT, visible(70),
                facts(true, SCORE, 70, null, Validity.INVALID)))
                .extracting(ResultNotificationEvent::kind).containsExactly(Kind.RESULT_STATUS_CHANGED);
    }
    @ParameterizedTest @ValueSource(ints = {-1, 0, 2, 100})
    void unknownEnvelopeVersionsNeverFallBack(int version) {
        assertThatThrownBy(() -> new ResultNotificationEvent(version, Kind.RESULT_OPENED, source(SourceKind.RESULT_RELEASE, 1), TARGET, null, AT, SCORE))
                .isInstanceOfSatisfying(ResultEventException.class, e -> assertThat(e.reason()).isEqualTo(ResultEventException.Reason.UNSUPPORTED));
    }
    @Test void malformedCrossCategoryAndMissingFactsAreRejected() {
        assertThatThrownBy(() -> new Source(null, TARGET, 1)).isInstanceOf(ResultEventException.class);
        assertThatThrownBy(() -> new Source(SourceKind.RESULT_RELEASE, null, 1)).isInstanceOf(ResultEventException.class);
        assertThatThrownBy(() -> source(SourceKind.RESULT_RELEASE, 0)).isInstanceOf(ResultEventException.class);
        assertThatThrownBy(() -> new Total(101, 100, 10000)).isInstanceOf(ResultEventException.class);
        assertThatThrownBy(() -> new Total(70, 100, 7001)).isInstanceOf(ResultEventException.class);
        assertThatThrownBy(() -> new ResultFacts(true, SCORE, null, null, Validity.VALID)).isInstanceOf(ResultEventException.class);
        assertThatThrownBy(() -> new ResultFacts(true, FORMAL, new Total(70, 100, 7000), null, Validity.VALID)).isInstanceOf(ResultEventException.class);
        assertThatThrownBy(() -> new CertificateFacts(CertificateStatus.ABSENT, TARGET, null)).isInstanceOf(ResultEventException.class);
        assertThatThrownBy(() -> new CertificateFacts(CertificateStatus.VALID, TARGET, TARGET)).isInstanceOf(ResultEventException.class);
        assertThatThrownBy(() -> resultChange(null, TARGET, AT, visible(70), visible(80))).isInstanceOf(ResultEventException.class);
        assertThatThrownBy(() -> certificateChange(source(SourceKind.SELECTED_RESULT, 1), AT,
                new CertificateFacts(CertificateStatus.ABSENT, null, null), new CertificateFacts(CertificateStatus.VALID, TARGET, null)))
                .isInstanceOf(ResultEventException.class);
        for (var category : Category.values()) if (!FORMAL.contains(category))
            assertThatThrownBy(() -> new ResultNotificationEvent(1, Kind.RESULT_OPENED, source(SourceKind.RESULT_RELEASE, 1), TARGET, null, AT, Set.of(category)))
                    .isInstanceOf(ResultEventException.class);
        assertThatThrownBy(() -> new ResultNotificationEvent(1, Kind.TOTAL_SCORE_CHANGED, source(SourceKind.RESULT_RELEASE, 1), TARGET, null, AT, SCORE))
                .isInstanceOf(ResultEventException.class);
        assertThatThrownBy(() -> new ResultNotificationEvent(1, Kind.RESULT_STATUS_CHANGED, source(SourceKind.SELECTED_RESULT, 1), TARGET, null, AT, SCORE))
                .isInstanceOf(ResultEventException.class);
    }
    @Test void immutableEnvelopeHasNoRawPayloadOrPrivateDiagnostics() {
        var categories = EnumSet.of(Category.TOTAL_SCORE);
        var event = new ResultNotificationEvent(1, Kind.RESULT_OPENED, source(SourceKind.RESULT_RELEASE, 1), TARGET, null, AT, categories);
        categories.clear(); assertThat(event.categories()).containsExactly(Category.TOTAL_SCORE);
        assertThatThrownBy(() -> event.categories().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(event.occurredAt().getNano()).isEqualTo(123456000);
        assertThat(event.toString() + event.source() + visible(70)).doesNotContain(TARGET.toString(), "7000", "70/");
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().valueToTree(event);
        assertThat(json.has("total")).isFalse(); assertThat(json.has("passed")).isFalse();
        assertThat(json.toString()).doesNotContain("explanation", "answer", "contact", "reasonText", "email");
    }
    private AutomaticResult result() {
        var item = new AutomaticResult.Item(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0,
                BaselineQuestionContracts.response(QuestionType.TRUE_FALSE), BaselineQuestionContracts.scoring(QuestionType.TRUE_FALSE), 1);
        return new AutomaticResult(UUID.randomUUID(), 1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "a".repeat(64), AutomaticResult.Score.calculate(1, 1, 7000), AT.minusSeconds(1), List.of(item));
    }
}
