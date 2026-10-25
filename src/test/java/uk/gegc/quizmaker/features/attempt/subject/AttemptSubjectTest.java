package uk.gegc.quizmaker.features.attempt.subject;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import uk.gegc.quizmaker.features.attempt.application.AttemptSubjectService;
import uk.gegc.quizmaker.features.attempt.application.impl.AttemptSubjectServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubject;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubjectException;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubjectLookup;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptSubjectRepository;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubjectException.Reason.*;

class AttemptSubjectTest {
    private final UUID attempt = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    @Test
    void authenticatedValueHasStableTypedIdentityAndValueEquality() {
        var subject = AttemptSubject.authenticated(attempt, user);
        assertThat(subject).isEqualTo(new AttemptSubject(attempt, 1, AttemptSubject.Type.AUTHENTICATED_USER, user));
        assertThat(subject.attemptId()).isEqualTo(attempt);
        assertThat(subject.userId()).isEqualTo(user);
        assertThat(subject.schemaVersion()).isEqualTo(1);
        assertThat(subject.type()).isEqualTo(AttemptSubject.Type.AUTHENTICATED_USER);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 2, Integer.MAX_VALUE})
    void unsupportedVersionsAreNeverCoercedToV1(int version) {
        rejected(() -> new AttemptSubject(attempt, version, AttemptSubject.Type.AUTHENTICATED_USER, user), UNSUPPORTED_VERSION);
    }

    @Test
    void incompleteIdentityAndTypeAreRejected() {
        rejected(() -> AttemptSubject.authenticated(null, user), INVALID_SUBJECT);
        rejected(() -> AttemptSubject.authenticated(attempt, null), INVALID_SUBJECT);
        rejected(() -> new AttemptSubject(attempt, 1, null, user), UNSUPPORTED_TYPE);
        assertThatThrownBy(() -> new AttemptSubjectLookup.Bound(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void missingAndContradictoryTrustedIdentityNeverReachStorage() {
        var repository = mock(AttemptSubjectRepository.class);
        var metrics = new SimpleMeterRegistry();
        AttemptSubjectService service = new AttemptSubjectServiceImpl(repository, transactions(), metrics);
        var subject = AttemptSubject.authenticated(attempt, user);
        rejected(() -> service.bindOrReplay(null, subject), SUBJECT_UNAVAILABLE);
        rejected(() -> service.bindOrReplay(UUID.randomUUID(), subject), SUBJECT_UNAVAILABLE);
        rejected(() -> service.bindOrReplay(user, null), INVALID_SUBJECT);
        rejected(() -> service.read(null), INVALID_SUBJECT);
        verifyNoInteractions(repository);
        metrics.close();
    }

    @Test
    void storageOutageDoesNotExposeCollaboratorDiagnostics() {
        var repository = mock(AttemptSubjectRepository.class);
        var subject = AttemptSubject.authenticated(attempt, user);
        when(repository.bindOrReplay(user, subject)).thenThrow(new DataAccessResourceFailureException("sensitive diagnostic"));
        when(repository.read(attempt)).thenThrow(new DataAccessResourceFailureException("sensitive diagnostic"));
        var metrics = new SimpleMeterRegistry();
        try {
            AttemptSubjectService service = new AttemptSubjectServiceImpl(repository, transactions(), metrics);
            rejected(() -> service.bindOrReplay(user, subject), PERSISTENCE_UNAVAILABLE);
            rejected(() -> service.read(attempt), PERSISTENCE_UNAVAILABLE);
        } finally { metrics.close(); }
    }

    private static void rejected(Runnable action, AttemptSubjectException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(AttemptSubjectException.class, failure -> {
            assertThat(failure.reason()).isEqualTo(reason);
            assertThat(failure.getMessage()).isEqualTo("Attempt subject rejected: " + reason);
            assertThat(failure.getCause()).isNull();
        });
    }

    private static PlatformTransactionManager transactions() {
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return transactions;
    }
}
