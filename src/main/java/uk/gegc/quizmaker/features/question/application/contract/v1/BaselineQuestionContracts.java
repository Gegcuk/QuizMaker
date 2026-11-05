package uk.gegc.quizmaker.features.question.application.contract.v1;

import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.model.contract.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;

import java.util.Map;

/** Explicit v1 catalogue. Missing families never acquire a fallback implementation. */
public final class BaselineQuestionContracts {
    private static final Map<QuestionType, String> NAMES = Map.of(
            QuestionType.MCQ_SINGLE, "mcq-single", QuestionType.MCQ_MULTI, "mcq-multi",
            QuestionType.TRUE_FALSE, "true-false", QuestionType.FILL_GAP, "fill-gap",
            QuestionType.ORDERING, "ordering");

    private BaselineQuestionContracts() { }

    public static ContractVersion response(QuestionType type) { return version(type, ".response"); }
    public static ContractVersion scoring(QuestionType type) { return version(type, ".binary"); }

    public static QuestionContractBinding binding(QuestionRevision question) {
        if (question == null) throw new ContractResolutionException(ContractResolutionException.Reason.INVALID_BINDING);
        return new QuestionContractBinding(question.id(), response(question.type()), scoring(question.type()));
    }

    private static ContractVersion version(QuestionType type, String suffix) {
        String name = type == null ? null : NAMES.get(type);
        if (name == null) throw new ContractResolutionException(ContractResolutionException.Reason.UNSUPPORTED_CONTRACT);
        return new ContractVersion("question." + name + suffix, 1);
    }
}
