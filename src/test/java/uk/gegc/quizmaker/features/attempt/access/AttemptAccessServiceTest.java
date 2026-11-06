package uk.gegc.quizmaker.features.attempt.access;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import uk.gegc.quizmaker.features.attempt.application.AttemptAccessService;
import uk.gegc.quizmaker.features.attempt.application.AuthenticatedSubjectResolver;
import uk.gegc.quizmaker.features.attempt.application.impl.AttemptAccessServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.access.AttemptAccessException;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.LifecycleState;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubject;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.SubjectResolutionException;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptAccessRepository;
import uk.gegc.quizmaker.features.quiz.application.QuizPracticeSourceService;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.PublicationException;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static uk.gegc.quizmaker.features.attempt.application.AttemptAccessService.Operation.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.access.AttemptAccessException.Reason.*;

/** Collaborator contract failures only; MySQL tests separately prove current reads and transactions. */
class AttemptAccessServiceTest {
    final AuthenticatedSubjectResolver identity = mock(AuthenticatedSubjectResolver.class);
    final AttemptAccessRepository attempts = mock(AttemptAccessRepository.class);
    final QuizPracticeSourceService quizzes = mock(QuizPracticeSourceService.class);
    final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final UUID attempt = UUID.randomUUID(), actor = UUID.randomUUID(), quiz = UUID.randomUUID();
    final AttemptAccessService service = service(metrics);

    AttemptAccessService service(MeterRegistry registry) {
        when(transactions.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        return new AttemptAccessServiceImpl(identity, attempts, quizzes, transactions, registry);
    }

    @AfterEach void close() { metrics.close(); }

    @Test void malformedScopeCannotReachProtectedData() {
        rejected(() -> service.requireAccess(null, VIEW), DENIED);
        rejected(() -> service.requireAccess(attempt, null), DENIED);
        verifyNoInteractions(identity, attempts, quizzes, transactions);
    }

    @Test void absenceAndResourceDenialHaveTheSameNonEnumeratingFailure() {
        when(identity.resolveCurrent(attempt)).thenReturn(AttemptSubject.authenticated(attempt, actor));
        when(attempts.lockEvidence(attempt, actor)).thenReturn(Optional.empty());
        rejected(() -> service.requireAccess(attempt, VIEW), DENIED);
        successfulFixture();
        when(quizzes.lockParticipantAccess(actor, quiz, PermissionName.ATTEMPT_READ))
                .thenThrow(new PublicationException(PublicationException.Reason.UNAVAILABLE));
        rejected(() -> service.requireAccess(attempt, VIEW), DENIED);
    }

    @Test void identityErrorsRetainOnlySafeAuthenticationOrAvailabilityCategory() {
        for (var reason : SubjectResolutionException.Reason.values()) {
            doThrow(new SubjectResolutionException(reason)).when(identity).resolveCurrent(attempt);
            var expected = switch (reason) {
                case UNAUTHENTICATED -> UNAUTHENTICATED;
                case UNAVAILABLE -> UNAVAILABLE;
                default -> DENIED;
            };
            rejected(() -> service.requireAccess(attempt, VIEW), expected);
        }
        verifyNoInteractions(attempts, quizzes);
    }

    @ParameterizedTest @CsvSource({"1213,RETRYABLE", "1205,IN_PROGRESS", "2006,UNAVAILABLE"})
    void databaseDiagnosticsAreClassifiedBeforeBeingDiscarded(int code, AttemptAccessException.Reason expected) {
        when(identity.resolveCurrent(attempt)).thenThrow(new DataAccessResourceFailureException(
                "synthetic diagnostic", new SQLException("synthetic private SQL", "40001", code)));
        rejected(() -> service.requireAccess(attempt, VIEW), expected);
        verifyNoInteractions(attempts, quizzes);
    }

    @Test void transactionCompletionFailureCannotBeReportedAsAllowed() {
        successfulFixture();
        doThrow(new TransactionSystemException("synthetic commit failure")).when(transactions).commit(any());
        rejected(() -> service.requireAccess(attempt, VIEW), UNAVAILABLE);
        assertThat(metrics.find("quizmaker.attempt.access").tag("outcome", "allowed").timer()).isNull();
    }

    @Test void unavailableTelemetryCannotChangeAnAllowOrDenial() {
        successfulFixture();
        var broken = mock(MeterRegistry.class);
        when(broken.config()).thenThrow(new IllegalStateException("synthetic telemetry failure"));
        var checked = service(broken);
        assertThat(checked.requireAccess(attempt, VIEW).operation()).isEqualTo(VIEW);
        when(attempts.lockEvidence(attempt, actor)).thenReturn(Optional.empty());
        rejected(() -> checked.requireAccess(attempt, VIEW), DENIED);
    }

    private void successfulFixture() {
        var at = Instant.parse("2026-11-06T10:00:00Z");
        var lifecycle = LifecycleState.initial(attempt, at).advance(
                new AttemptLifecycle.State(1, AttemptLifecycle.Execution.IN_PROGRESS, AttemptLifecycle.AnswerCollection.NOT_STARTED), at);
        when(identity.resolveCurrent(attempt)).thenReturn(AttemptSubject.authenticated(attempt, actor));
        when(attempts.lockEvidence(attempt, actor)).thenReturn(Optional.of(new AttemptAccessRepository.Evidence(
                quiz, actor, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), lifecycle)));
        when(quizzes.lockParticipantAccess(actor, quiz, PermissionName.ATTEMPT_READ)).thenReturn(actor);
    }

    private static void rejected(Runnable action, AttemptAccessException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(AttemptAccessException.class, failure -> {
            assertThat(failure.reason()).isEqualTo(reason);
            assertThat(failure.getMessage()).isEqualTo("Attempt access rejected: " + reason);
            assertThat(failure.getCause()).isNull(); assertThat(failure.getSuppressed()).isEmpty();
        });
    }
}
