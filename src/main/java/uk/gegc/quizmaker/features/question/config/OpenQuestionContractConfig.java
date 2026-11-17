package uk.gegc.quizmaker.features.question.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gegc.quizmaker.features.question.application.contract.*;
import uk.gegc.quizmaker.features.question.application.contract.v1.*;

/** Additive internal capability; registration does not enable public target execution. */
@Configuration(proxyBeanMethods = false)
public class OpenQuestionContractConfig {
    @Bean VersionedResponseContract openResponseV1() { return new OpenResponseContractV1(); }
    @Bean VersionedScoringContract openScoringV1() { return new OpenScoringContractV1(); }
}
