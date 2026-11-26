package uk.gegc.quizmaker.features.quiz.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.quiz.application.QuizScenarioRevisionService;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.RevisionIntegrityException;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.ScenarioGraphValidationException;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRevisionRepository;

import java.util.Locale;
import java.util.UUID;

import static uk.gegc.quizmaker.features.quiz.domain.model.revision.RevisionIntegrityException.Reason.*;

@Service
public class QuizScenarioRevisionServiceImpl implements QuizScenarioRevisionService {
    private final QuizRevisionRepository revisions;
    private final MeterRegistry metrics;

    public QuizScenarioRevisionServiceImpl(QuizRevisionRepository revisions, MeterRegistry metrics) {
        this.revisions = revisions;
        this.metrics = metrics;
    }

    @Override
    @Transactional
    public QuizRevision publish(UUID trustedOwnerId, QuizRevision revision) {
        String outcome = "persistence_unavailable";
        try {
            if (trustedOwnerId == null || revision == null || !trustedOwnerId.equals(revision.ownerId()))
                throw new RevisionIntegrityException(INVALID_CONTENT);
            if (revision.scenarioGraph() == null) throw new RevisionIntegrityException(UNSUPPORTED_VERSION);
            var publication = revisions.publishScenario(revision);
            outcome = publication.replayed() ? "replayed" : "inserted";
            return publication.revision();
        } catch (ScenarioGraphValidationException failure) {
            outcome = "graph_" + failure.reason().name().toLowerCase(Locale.ROOT);
            throw failure;
        } catch (RevisionIntegrityException failure) {
            outcome = failure.reason().name().toLowerCase(Locale.ROOT);
            throw failure;
        } catch (DataAccessException | TransactionException failure) {
            throw new RevisionIntegrityException(PERSISTENCE_UNAVAILABLE);
        } finally {
            // This describes the operation inside the transaction. The enclosing caller may still roll back.
            try { metrics.counter("quiz.revision.scenario.publish", "version", "1", "outcome", outcome).increment(); }
            catch (RuntimeException ignored) { /* Telemetry cannot change publication. */ }
        }
    }
}
