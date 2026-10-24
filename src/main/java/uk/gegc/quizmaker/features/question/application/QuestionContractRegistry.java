package uk.gegc.quizmaker.features.question.application;

import uk.gegc.quizmaker.features.question.application.contract.ResolvedQuestionContracts;
import uk.gegc.quizmaker.features.question.domain.model.contract.QuestionContractBinding;

public interface QuestionContractRegistry {
    ResolvedQuestionContracts resolve(QuestionContractBinding binding);
}
