package uk.gegc.quizmaker.features.result.infra.release;

import java.util.UUID;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizPublicationRepository;
import uk.gegc.quizmaker.features.result.application.ReleaseConfigurationSource;
import uk.gegc.quizmaker.features.result.domain.model.release.ReleaseConfigurationException;
import static uk.gegc.quizmaker.features.result.domain.model.release.ReleaseConfigurationException.*;
import static uk.gegc.quizmaker.features.result.domain.model.release.ReleaseConfigurationException.Reason.*;

/** Adapts the established author publication checks without reading revision payloads. */
@Component
public final class QuizReleaseConfigurationSource implements ReleaseConfigurationSource {
    private final QuizPublicationRepository source;
    public QuizReleaseConfigurationSource(QuizPublicationRepository source) { this.source = source; }
    @Override public UUID authorize(String principal, UUID quiz, UUID revision, boolean writing) {
        try {
            var actor = source.actor(principal).orElseThrow(() -> new ReleaseConfigurationException(NOT_FOUND));
            require(actor.elevated() || actor.permissions().contains("QUIZ_UPDATE"), NOT_FOUND);
            var draft = source.draft(quiz, writing).orElseThrow(() -> new ReleaseConfigurationException(NOT_FOUND));
            require(actor.elevated() || actor.id().equals(draft.ownerId()), NOT_FOUND);
            require(!writing || !"PUBLIC".equals(draft.visibility()) || actor.elevated(), NOT_FOUND);
            require(source.revision(quiz, revision).isPresent(), NOT_FOUND);
            return actor.id();
        } catch (ReleaseConfigurationException safe) { throw safe; }
        catch (RuntimeException failure) { throw new ReleaseConfigurationException(UNAVAILABLE); }
    }
}
