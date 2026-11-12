package uk.gegc.quizmaker.features.quiz.revision;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import uk.gegc.quizmaker.features.quiz.application.impl.QuizRevisionCaseServiceImpl;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.RevisionIntegrityException;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRevisionRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static uk.gegc.quizmaker.features.quiz.revision.RevisionFixtures.*;
import static uk.gegc.quizmaker.features.quiz.revision.QuizRevisionCasesTest.*;

class QuizRevisionCaseServiceTest {
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    @AfterEach void closeMetrics() { metrics.close(); }
    @Test void publishesCompleteCaseThenReplaysIdenticalCommittedEvidenceWithoutASecondWrite() {
        var repository = mock(QuizRevisionRepository.class); var revision = cased("service");
        var service = new QuizRevisionCaseServiceImpl(repository, metrics);
        when(repository.findByIdForShare(OWNER, revision.id())).thenReturn(Optional.empty(), Optional.of(revision));
        service.publish(OWNER, revision); service.publish(OWNER, revision);
        verify(repository, times(1)).insert(revision);
        assertThat(metrics.get("quiz.revision.case.publish").tag("outcome", "inserted").counter().count()).isEqualTo(1);
        assertThat(metrics.get("quiz.revision.case.publish").tag("outcome", "replayed").counter().count()).isEqualTo(1);
    }

    @Test void conflictingIdentityCannotRewritePreviouslyPublishedCase() {
        var repository = mock(QuizRevisionRepository.class); var revision = cased("conflict");
        var altered = withCases(revision, revision.sections(), java.util.List.of(revision.caseBlocks().get(0)));
        when(repository.findByIdForShare(OWNER, revision.id())).thenReturn(Optional.of(revision));
        var service = new QuizRevisionCaseServiceImpl(repository, metrics);
        assertThatThrownBy(() -> service.publish(OWNER, altered)).isInstanceOf(RevisionIntegrityException.class)
                .hasMessageContaining("IDENTITY_CONFLICT").hasNoCause();
        verify(repository, never()).insert(any());
    }

    @Test void missingOrWrongTrustedOwnerAndNonCaseFormatsNeverReachStorage() {
        var repository = mock(QuizRevisionRepository.class); var revision = cased("owner");
        var service = new QuizRevisionCaseServiceImpl(repository, metrics);
        assertThatThrownBy(() -> service.publish(null, revision)).isInstanceOf(RevisionIntegrityException.class);
        assertThatThrownBy(() -> service.publish(id("wrong-owner"), revision)).isInstanceOf(RevisionIntegrityException.class);
        assertThatThrownBy(() -> service.publish(OWNER, null)).isInstanceOf(RevisionIntegrityException.class);
        assertThatThrownBy(() -> service.publish(OWNER, quiz("flat"))).isInstanceOf(RevisionIntegrityException.class)
                .hasMessageContaining("UNSUPPORTED_VERSION");
        verifyNoInteractions(repository);
    }

    @Test void storageFailuresHaveBoundedOutcomesAndNoDriverCauseOrPrivateText() {
        var repository = mock(QuizRevisionRepository.class); var revision = cased("failure");
        when(repository.findByIdForShare(OWNER, revision.id())).thenReturn(Optional.empty());
        var service = new QuizRevisionCaseServiceImpl(repository, metrics);
        doThrow(new DuplicateKeyException("private SQL")).when(repository).insert(revision);
        assertThatThrownBy(() -> service.publish(OWNER, revision)).hasMessageContaining("IDENTITY_CONFLICT")
                .hasMessageNotContaining("private").hasNoCause();
        doThrow(new DataAccessResourceFailureException("private payload")).when(repository).insert(revision);
        assertThatThrownBy(() -> service.publish(OWNER, revision)).hasMessageContaining("PERSISTENCE_UNAVAILABLE")
                .hasMessageNotContaining("private").hasNoCause();
    }
}
