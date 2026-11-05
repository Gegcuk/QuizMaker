package uk.gegc.quizmaker.features.question.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gegc.quizmaker.features.question.application.contract.VersionedResponseContract;
import uk.gegc.quizmaker.features.question.application.contract.VersionedScoringContract;
import uk.gegc.quizmaker.features.question.application.contract.v1.*;

import static uk.gegc.quizmaker.features.question.domain.model.QuestionType.*;

/** Registers exact version pairs only; no public route or existing attempt is switched. */
@Configuration(proxyBeanMethods = false)
public class BaselineQuestionContractConfig {
    @Bean VersionedResponseContract mcqSingleResponseV1() { return new FamilyResponseContractV1(MCQ_SINGLE); }
    @Bean VersionedResponseContract mcqMultiResponseV1() { return new FamilyResponseContractV1(MCQ_MULTI); }
    @Bean VersionedResponseContract trueFalseResponseV1() { return new FamilyResponseContractV1(TRUE_FALSE); }
    @Bean VersionedResponseContract fillGapResponseV1() { return new FamilyResponseContractV1(FILL_GAP); }
    @Bean VersionedResponseContract orderingResponseV1() { return new FamilyResponseContractV1(ORDERING); }
    @Bean VersionedScoringContract mcqSingleScoringV1() { return new FamilyScoringContractV1(MCQ_SINGLE); }
    @Bean VersionedScoringContract mcqMultiScoringV1() { return new FamilyScoringContractV1(MCQ_MULTI); }
    @Bean VersionedScoringContract trueFalseScoringV1() { return new FamilyScoringContractV1(TRUE_FALSE); }
    @Bean VersionedScoringContract fillGapScoringV1() { return new FamilyScoringContractV1(FILL_GAP); }
    @Bean VersionedScoringContract orderingScoringV1() { return new FamilyScoringContractV1(ORDERING); }
}
