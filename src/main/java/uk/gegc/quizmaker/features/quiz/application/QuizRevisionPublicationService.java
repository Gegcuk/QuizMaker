package uk.gegc.quizmaker.features.quiz.application;

import org.springframework.data.domain.Page;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizPublication.*;
import java.util.Optional;
import java.util.UUID;

/** Explicit author publication boundary, resolved from the authenticated principal. */
public interface QuizRevisionPublicationService {
    Result publish(Command command);
    Optional<Current> current(UUID quizId);
    Page<Revision> history(UUID quizId, int page, int size);
    PublishedContent read(UUID quizId, UUID revisionId);
}
