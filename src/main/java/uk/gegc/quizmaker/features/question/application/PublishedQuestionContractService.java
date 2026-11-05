package uk.gegc.quizmaker.features.question.application;

import uk.gegc.quizmaker.features.question.domain.model.contract.QuestionContractBinding;
import java.util.List;
import java.util.UUID;

/**
 * Internal author boundary. Reuses publication authentication, permissions and owner scope.
 * Binds a complete published revision to the five baseline v1 families atomically; identical
 * retry is safe, conflicting or unsupported bindings fail without creating an attempt/result.
 */
public interface PublishedQuestionContractService {
    List<QuestionContractBinding> bind(UUID quizId, UUID revisionId);
}
