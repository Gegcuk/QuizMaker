package uk.gegc.quizmaker.features.quiz.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.quiz.application.QuizRevisionCaseService;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.RevisionIntegrityException;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRevisionRepository;

import java.util.Locale;
import java.util.UUID;

import static uk.gegc.quizmaker.features.quiz.domain.model.revision.RevisionIntegrityException.Reason.*;

@Service
public class QuizRevisionCaseServiceImpl implements QuizRevisionCaseService {
    private final QuizRevisionRepository revisions;
    private final MeterRegistry metrics;

    public QuizRevisionCaseServiceImpl(QuizRevisionRepository revisions, MeterRegistry metrics) {
        this.revisions = revisions;
        this.metrics = metrics;
    }

    @Override
    @Transactional
    public void publish(UUID trustedOwnerId, QuizRevision revision) {
        String outcome = "persistence_unavailable";
        try {
            if (trustedOwnerId == null || revision == null || !trustedOwnerId.equals(revision.ownerId()))
                throw new RevisionIntegrityException(INVALID_CONTENT);
            if (revision.schemaVersion() != 3) throw new RevisionIntegrityException(UNSUPPORTED_VERSION);
            var existing = revisions.findByIdForShare(trustedOwnerId, revision.id());
            if (existing.isPresent()) {
                if (!existing.get().equals(revision)) throw new RevisionIntegrityException(IDENTITY_CONFLICT);
                outcome = "replayed";
                return;
            }
            revisions.insert(revision);
            outcome = "inserted";
        } catch (RevisionIntegrityException failure) {
            outcome = failure.reason().name().toLowerCase(Locale.ROOT);
            throw failure;
        } catch (DuplicateKeyException failure) {
            outcome = "identity_conflict";
            throw new RevisionIntegrityException(IDENTITY_CONFLICT);
        } catch (DataAccessException | TransactionException failure) {
            throw new RevisionIntegrityException(PERSISTENCE_UNAVAILABLE);
        } finally {
            // This is a boundary outcome, not evidence of an outer caller's eventual commit.
            try { metrics.counter("quiz.revision.case.publish", "version", "1", "outcome", outcome).increment(); }
            catch (RuntimeException ignored) { /* Telemetry cannot change publication. */ }
        }
    }
}
