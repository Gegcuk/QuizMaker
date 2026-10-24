package uk.gegc.quizmaker.features.question.domain.model.contract;

import java.math.BigDecimal;

import static uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException.Reason.INVALID_BINDING;

/** Exact per-contract points; result persistence, normalization and release belong to their consumers. */
public record ContractScore(BigDecimal earned, BigDecimal possible) {
    public ContractScore {
        if (earned == null || possible == null || possible.signum() <= 0
                || earned.signum() < 0 || earned.compareTo(possible) > 0) {
            throw new ContractResolutionException(INVALID_BINDING);
        }
    }
}
