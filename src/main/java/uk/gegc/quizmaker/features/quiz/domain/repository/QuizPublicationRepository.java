package uk.gegc.quizmaker.features.quiz.domain.repository;

import org.springframework.data.domain.Page;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizPublication.*;
import java.time.Instant;
import java.util.*;

/** Transaction-scoped scalar source reads and publication persistence; no mutable entity cache. */
public interface QuizPublicationRepository {
    Optional<Actor> actor(String principal);
    Optional<Draft> draft(UUID quizId, boolean exclusive);
    List<Question> lockQuestions(UUID quizId);
    Optional<Current> current(UUID quizId);
    Optional<Receipt> receipt(UUID quizId, UUID actorId, UUID commandId);
    Optional<Revision> findContent(UUID quizId, String hash);
    Optional<Revision> revision(UUID quizId, UUID revisionId);
    void insert(Revision revision, List<Image> images);
    void pointTo(UUID quizId, Current publication);
    void record(Command command, UUID actorId, String requestHash, Current publication, Instant recordedAt);
    Page<Revision> history(UUID quizId, int page, int size);
    List<Image> images(UUID revisionId);
}
