package uk.gegc.quizmaker.features.question.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gegc.quizmaker.features.question.application.contract.VersionedResponseContract;
import uk.gegc.quizmaker.features.question.application.contract.VersionedScoringContract;
import uk.gegc.quizmaker.features.question.application.contract.v1.HotspotResponseContractV1;
import uk.gegc.quizmaker.features.question.application.contract.v1.HotspotScoringContractV1;

@Configuration(proxyBeanMethods = false)
public class HotspotQuestionContractConfig {
    @Bean VersionedResponseContract hotspotResponseV1() { return new HotspotResponseContractV1(); }
    @Bean VersionedScoringContract hotspotScoringV1() { return new HotspotScoringContractV1(); }
}
