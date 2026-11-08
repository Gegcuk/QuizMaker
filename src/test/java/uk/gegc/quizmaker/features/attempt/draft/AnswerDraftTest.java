package uk.gegc.quizmaker.features.attempt.draft;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.attempt.domain.model.draft.*;
import uk.gegc.quizmaker.features.attempt.domain.model.draft.AnswerDraft.Reference;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractVersion;

import static org.assertj.core.api.Assertions.*;

class AnswerDraftTest {
    private static final Instant NOW = Instant.parse("2026-11-08T12:00:00.123456789Z");

    private Reference reference() {
        return new Reference(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new ContractVersion("true-false-response", 1));
    }

    @Test void valuesAndTombstonesRetainIdentityAndMicrosecondTimestampsWithoutLeakingContent() {
        var ref = reference();
        var draft = new AnswerDraft(ref, 3, AnswerDraft.Status.PRESENT, "private response", NOW, NOW);
        assertThat(draft.createdAt()).isEqualTo(Instant.parse("2026-11-08T12:00:00.123456Z"));
        assertThat(draft.updatedAt()).isEqualTo(draft.createdAt());
        assertThat(draft.toString()).doesNotContain("private", ref.attemptId().toString(), ref.formItemId().toString());
        assertThat(ref.toString()).doesNotContain(ref.attemptId().toString(), ref.responseContract().id());
        var deleted = new AnswerDraft(ref, 4, AnswerDraft.Status.DELETED, null, NOW, NOW);
        assertThat(deleted.normalizedResponse()).isNull(); assertThat(deleted.reference()).isEqualTo(ref);
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3, 4})
    void everyReferenceDimensionIsRequired(int missing) {
        var r = reference();
        assertThatThrownBy(() -> new Reference(missing == 0 ? null : r.attemptId(), missing == 1 ? null : r.formId(),
                missing == 2 ? null : r.formItemId(), missing == 3 ? null : r.questionRevisionId(),
                missing == 4 ? null : r.responseContract())).isInstanceOf(AnswerDraftException.class);
    }

    @ParameterizedTest @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void persistedVersionsMustBePositive(long version) {
        assertThatThrownBy(() -> new AnswerDraft(reference(), version, AnswerDraft.Status.PRESENT, "{}", NOW, NOW))
                .isInstanceOf(AnswerDraftException.class);
    }

    @Test void contradictoryValueStatusAndTimeMetadataAreRejected() {
        assertThatThrownBy(() -> new AnswerDraft(reference(), 1, AnswerDraft.Status.PRESENT, null, NOW, NOW)).isInstanceOf(AnswerDraftException.class);
        assertThatThrownBy(() -> new AnswerDraft(reference(), 1, AnswerDraft.Status.DELETED, "{}", NOW, NOW)).isInstanceOf(AnswerDraftException.class);
        assertThatThrownBy(() -> new AnswerDraft(reference(), 1, null, "{}", NOW, NOW)).isInstanceOf(AnswerDraftException.class);
        assertThatThrownBy(() -> new AnswerDraft(null, 1, AnswerDraft.Status.PRESENT, "{}", NOW, NOW)).isInstanceOf(AnswerDraftException.class);
        assertThatThrownBy(() -> new AnswerDraft(reference(), 1, AnswerDraft.Status.PRESENT, "{}", NOW, NOW.minusSeconds(1))).isInstanceOf(AnswerDraftException.class);
        assertThatThrownBy(() -> AnswerDraft.time(null)).isInstanceOf(AnswerDraftException.class);
        assertThatThrownBy(() -> AnswerDraft.time(Instant.MIN)).isInstanceOf(AnswerDraftException.class);
        assertThatThrownBy(() -> AnswerDraft.time(Instant.MAX)).isInstanceOf(AnswerDraftException.class);
    }

    @Test void failuresCannotAcquireSensitiveCausesOrSuppressedDiagnostics() {
        var e = new AnswerDraftException(AnswerDraftException.Reason.CONFLICT);
        e.addSuppressed(new IllegalStateException("private marker"));
        assertThat(e).hasMessage("DRAFT_CONFLICT").hasNoCause();
        assertThat(e.getSuppressed()).isEmpty();
        assertThatThrownBy(() -> e.initCause(new IllegalArgumentException("private marker"))).isInstanceOf(IllegalStateException.class);
    }
}
