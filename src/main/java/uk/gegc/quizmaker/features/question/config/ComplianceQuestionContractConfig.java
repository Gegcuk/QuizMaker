package uk.gegc.quizmaker.features.question.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gegc.quizmaker.features.question.application.contract.*;
import uk.gegc.quizmaker.features.question.application.contract.v1.*;

/** Internal capability only; public target activation remains separately gated. */
@Configuration(proxyBeanMethods = false)
public class ComplianceQuestionContractConfig {
    @Bean VersionedResponseContract complianceResponseV1() { return new ComplianceResponseContractV1(); }
    @Bean VersionedScoringContract complianceScoringV1() { return new ComplianceScoringContractV1(); }
}
