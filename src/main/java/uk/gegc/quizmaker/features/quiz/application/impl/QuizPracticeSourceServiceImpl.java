package uk.gegc.quizmaker.features.quiz.application.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.quiz.application.QuizPracticeSourceService;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.PublicationException;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizPracticeSourceRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRevisionRepository;

import java.util.UUID;
import static uk.gegc.quizmaker.features.quiz.domain.model.revision.PublicationException.Reason.UNAVAILABLE;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class QuizPracticeSourceServiceImpl implements QuizPracticeSourceService {
    private final QuizPracticeSourceRepository source;
    private final QuizRevisionRepository revisions;
    public QuizPracticeSourceServiceImpl(QuizPracticeSourceRepository source, QuizRevisionRepository revisions) {
        this.source = source; this.revisions = revisions;
    }

    @Override public Access lockAccess(UUID actor, UUID quizId) {
        if (actor == null || quizId == null || !source.lockActiveUser(actor)) throw new PublicationException(UNAVAILABLE);
        var permissions = source.lockPermissions(actor);
        var quiz = source.lockSource(quizId).orElseThrow(() -> new PublicationException(UNAVAILABLE));
        boolean owner = actor.equals(quiz.ownerId());
        // Match QuestionController's UPDATE gate and QuestionService's all-associated-quiz edit
        // authority. These effective permissions cover every question, including correct answers.
        // QUIZ_UPDATE, QUIZ_ADMIN, metadata editing and a role name alone do not meet that contract.
        boolean fullEditor = permissions.contains("QUESTION_UPDATE")
                && (permissions.contains("QUIZ_MODERATE") || permissions.contains("QUESTION_ADMIN"));
        if (!owner && !fullEditor) throw new PublicationException(UNAVAILABLE);
        return new Access(quizId, quiz.ownerId(), owner, quiz.timerEnabled(), quiz.currentRevisionId(), quiz.publicationVersion());
    }

    @Override public QuizRevision readRevision(Access access, UUID revisionId) {
        if (access == null || revisionId == null) throw new PublicationException(UNAVAILABLE);
        var revision = revisions.findById(access.ownerId(), revisionId).orElseThrow(() -> new PublicationException(UNAVAILABLE));
        if (!revision.quizId().equals(access.quizId())) throw new PublicationException(UNAVAILABLE);
        return revision;
    }
}
