package uk.gegc.quizmaker.features.quiz.application.generation;

import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface QuizAssemblyService {

    Category getOrCreateAICategory();

    Set<Tag> resolveTags(GenerateQuizFromDocumentRequest request);

    Quiz createChunkQuiz(User user,
                         List<Question> questions,
                         int chunkIndex,
                         GenerateQuizFromDocumentRequest request,
                         Category category,
                         Set<Tag> tags,
                         UUID documentId);

    Quiz createConsolidatedQuiz(User user,
                                List<Question> allQuestions,
                                GenerateQuizFromDocumentRequest request,
                                Category category,
                                Set<Tag> tags,
                                UUID documentId,
                                int chunkCount);

    String generateChunkTitle(int chunkIndex, List<Question> questions);

    String ensureUniqueTitle(User user, String requestedTitle);
}
