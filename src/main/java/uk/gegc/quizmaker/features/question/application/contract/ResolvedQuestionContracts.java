package uk.gegc.quizmaker.features.question.application.contract;

import uk.gegc.quizmaker.features.question.domain.model.contract.QuestionContractBinding;

public record ResolvedQuestionContracts(QuestionContractBinding binding,
                                        VersionedResponseContract response,
                                        VersionedScoringContract scoring) { }
