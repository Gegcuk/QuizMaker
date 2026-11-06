package uk.gegc.quizmaker.features.attempt.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.AttemptAccessService;
import uk.gegc.quizmaker.features.attempt.application.AuthenticatedSubjectResolver;
import uk.gegc.quizmaker.features.attempt.domain.model.access.AttemptAccessException;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.SubjectResolutionException;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptAccessRepository;
import uk.gegc.quizmaker.features.quiz.application.QuizPracticeSourceService;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.PublicationException;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.shared.exception.DatabaseConflictException;

import java.util.Locale;
import java.util.UUID;

import static uk.gegc.quizmaker.features.attempt.domain.model.access.AttemptAccessException.Reason.*;

@Service
public class AttemptAccessServiceImpl implements AttemptAccessService {
    private final AuthenticatedSubjectResolver subjects;
    private final AttemptAccessRepository attempts;
    private final QuizPracticeSourceService quizzes;
    private final MeterRegistry metrics;
    private final TransactionTemplate transaction;

    public AttemptAccessServiceImpl(AuthenticatedSubjectResolver subjects, AttemptAccessRepository attempts,
                                    QuizPracticeSourceService quizzes, PlatformTransactionManager transactions,
                                    MeterRegistry metrics) {
        this.subjects = subjects; this.attempts = attempts; this.quizzes = quizzes; this.metrics = metrics;
        this.transaction = new TransactionTemplate(transactions);
    }

    @Override public Access requireAccess(UUID attemptId, Operation operation) {
        Timer.Sample timer = null;
        try { timer = Timer.start(metrics); } catch (RuntimeException ignored) { /* best effort */ }
        String outcome = "unavailable";
        try {
            if (attemptId == null || operation == null) throw new AttemptAccessException(DENIED);
            var access = transaction.execute(status -> authorize(attemptId, operation));
            outcome = "allowed";
            return access;
        } catch (RuntimeException failure) {
            var safe = translate(failure);
            outcome = safe.reason().name().toLowerCase(Locale.ROOT);
            throw safe;
        } finally {
            try {
                if (timer != null) timer.stop(Timer.builder("quizmaker.attempt.access")
                        .tags("operation", operation == null ? "invalid" : operation.name().toLowerCase(Locale.ROOT),
                                "outcome", outcome).register(metrics));
            } catch (RuntimeException ignored) { /* metrics cannot change access or disclose identity */ }
        }
    }

    private Access authorize(UUID attemptId, Operation operation) {
        var subject = subjects.resolveCurrent(attemptId);
        var evidence = attempts.lockEvidence(attemptId, subject.userId())
                .orElseThrow(() -> new AttemptAccessException(DENIED));
        var required = switch (operation) {
            case VIEW, RESULT -> PermissionName.ATTEMPT_READ;
            case ANSWER -> PermissionName.ATTEMPT_CREATE;
        };
        var owner = quizzes.lockParticipantAccess(subject.userId(), evidence.quizId(), required);
        if (!owner.equals(evidence.contentOwnerId())) throw new AttemptAccessException(DENIED);
        return new Access(attemptId, operation, evidence.quizId(), evidence.revisionId(), evidence.formId(),
                evidence.policySnapshotId(), evidence.lifecycle().rowVersion());
    }

    private static AttemptAccessException translate(RuntimeException failure) {
        if (failure instanceof AttemptAccessException access) return access;
        if (failure instanceof SubjectResolutionException identity) {
            return new AttemptAccessException(switch (identity.reason()) {
                case UNAUTHENTICATED -> UNAUTHENTICATED;
                case UNAVAILABLE -> UNAVAILABLE;
                default -> DENIED;
            });
        }
        if (failure instanceof PublicationException resource) {
            return new AttemptAccessException(resource.reason() == PublicationException.Reason.PERSISTENCE_UNAVAILABLE
                    ? UNAVAILABLE : DENIED);
        }
        if (failure instanceof DataAccessException || failure instanceof TransactionException
                || failure instanceof DatabaseConflictException) {
            var conflict = DatabaseConflictException.from(failure);
            if (conflict.isPresent()) return new AttemptAccessException(
                    conflict.get().kind() == DatabaseConflictException.Kind.DEADLOCK ? RETRYABLE : IN_PROGRESS);
        }
        return new AttemptAccessException(UNAVAILABLE);
    }
}
