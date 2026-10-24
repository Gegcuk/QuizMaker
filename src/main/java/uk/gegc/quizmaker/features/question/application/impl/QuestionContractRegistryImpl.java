package uk.gegc.quizmaker.features.question.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.question.application.QuestionContractRegistry;
import uk.gegc.quizmaker.features.question.application.contract.ResolvedQuestionContracts;
import uk.gegc.quizmaker.features.question.application.contract.VersionedResponseContract;
import uk.gegc.quizmaker.features.question.application.contract.VersionedScoringContract;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractVersion;
import uk.gegc.quizmaker.features.question.domain.model.contract.QuestionContractBinding;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException.Reason.*;

@Component
public class QuestionContractRegistryImpl implements QuestionContractRegistry {
    private final Map<ContractVersion, VersionedResponseContract> responses;
    private final Map<ContractVersion, VersionedScoringContract> scorers;
    private final MeterRegistry metrics;

    public QuestionContractRegistryImpl(List<VersionedResponseContract> responses,
                                       List<VersionedScoringContract> scorers, MeterRegistry metrics) {
        this.responses = index(responses, VersionedResponseContract::version);
        this.scorers = index(scorers, VersionedScoringContract::version);
        this.metrics = java.util.Objects.requireNonNull(metrics);
    }

    @Override
    public ResolvedQuestionContracts resolve(QuestionContractBinding binding) {
        if (binding == null) throw new ContractResolutionException(INVALID_BINDING);
        var response = responses.get(binding.response());
        var scoring = scorers.get(binding.scoring());
        // Fixed outcomes only: no identifiers, answers or arbitrary version strings in labels.
        metrics.counter("question.contract.resolutions", "outcome",
                response == null || scoring == null ? "unsupported" : "supported").increment();
        if (response == null || scoring == null) throw new ContractResolutionException(UNSUPPORTED_CONTRACT);
        return new ResolvedQuestionContracts(binding, response, scoring);
    }

    private static <T> Map<ContractVersion, T> index(List<T> contracts, Function<T, ContractVersion> version) {
        var indexed = new HashMap<ContractVersion, T>();
        for (T contract : contracts) {
            if (contract == null || version.apply(contract) == null
                    || indexed.putIfAbsent(version.apply(contract), contract) != null) {
                throw new ContractResolutionException(DUPLICATE_CONTRACT);
            }
        }
        return Map.copyOf(indexed);
    }
}
