package uk.gegc.quizmaker.features.question.application.contract.v1;

import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.model.contract.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;

/** Exact delivered family versions; the five-family #701 catalogue remains frozen. */
public final class SupportedQuestionContracts {
    public static final ContractVersion OPEN_RESPONSE = new ContractVersion("question.open.response", 1);
    public static final ContractVersion OPEN_SCORING = new ContractVersion("question.open.binary", 1);

    private SupportedQuestionContracts() { }

    public static ContractVersion response(QuestionType type) {
        return type == QuestionType.OPEN ? OPEN_RESPONSE : BaselineQuestionContracts.response(type);
    }

    public static ContractVersion scoring(QuestionType type) {
        return type == QuestionType.OPEN ? OPEN_SCORING : BaselineQuestionContracts.scoring(type);
    }

    public static QuestionContractBinding binding(QuestionRevision question) {
        if (question == null) throw new ContractResolutionException(ContractResolutionException.Reason.INVALID_BINDING);
        return new QuestionContractBinding(question.id(), response(question.type()), scoring(question.type()));
    }
}
