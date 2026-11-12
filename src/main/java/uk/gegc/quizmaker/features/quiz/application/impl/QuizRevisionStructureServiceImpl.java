package uk.gegc.quizmaker.features.quiz.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.quiz.application.QuizRevisionStructureService;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.*;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRevisionRepository;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class QuizRevisionStructureServiceImpl implements QuizRevisionStructureService {
    private final QuizRevisionRepository revisions;
    private final MeterRegistry metrics;

    public QuizRevisionStructureServiceImpl(QuizRevisionRepository revisions, MeterRegistry metrics) {
        this.revisions = revisions;
        this.metrics = metrics;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RevisionStructure> read(UUID trustedOwnerId, RevisionReference reference) {
        var sample = Timer.start();
        String outcome = "unavailable";
        try {
            if (trustedOwnerId == null || reference == null)
                throw new RevisionIntegrityException(RevisionIntegrityException.Reason.INVALID_CONTENT);
            if (reference.provenance() == RevisionReference.Provenance.LEGACY) {
                outcome = "legacy";
                return Optional.of(RevisionStructure.legacy());
            }
            var result = revisions.findById(trustedOwnerId, reference.revisionId()).map(RevisionStructure::from);
            outcome = result.map(value -> value.provenance().name().toLowerCase(Locale.ROOT)).orElse("unavailable");
            return result;
        } catch (RevisionIntegrityException failure) {
            outcome = failure.reason().name().toLowerCase(Locale.ROOT);
            throw failure;
        } catch (DataAccessException | TransactionException failure) {
            outcome = "persistence_unavailable";
            throw new RevisionIntegrityException(RevisionIntegrityException.Reason.PERSISTENCE_UNAVAILABLE);
        } finally {
            try { sample.stop(Timer.builder("quiz.revision.structure").tags("version", "1", "outcome", outcome).register(metrics)); }
            catch (RuntimeException ignored) { /* Observability cannot change the read outcome. */ }
        }
    }
}
