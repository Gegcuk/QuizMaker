package uk.gegc.quizmaker.features.billing.application;

import java.math.BigDecimal;

/**
 * Provides the tariff used when a new quiz-generation quote is created.
 */
public interface GenerationTariffService {

    GenerationTariff currentTariff();

    /**
     * Reconstructs the immutable tariff captured on a job. It must not read
     * today's pricing configuration for a job that has already been quoted.
     */
    default GenerationTariff fromSnapshot(
            String tariffVersion,
            long baseTokens,
            BigDecimal tokensPerThousandCharacters
    ) {
        throw new UnsupportedOperationException("Tariff snapshot reconstruction is not configured");
    }
}
