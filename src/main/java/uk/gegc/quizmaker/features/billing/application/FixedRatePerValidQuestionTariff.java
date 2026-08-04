package uk.gegc.quizmaker.features.billing.application;

/**
 * V1 tariff: a fixed number of billing tokens for each valid accepted question.
 */
public record FixedRatePerValidQuestionTariff(String version, long tokensPerValidQuestion) implements GenerationTariff {

    public FixedRatePerValidQuestionTariff {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("tariff version must not be blank");
        }
        if (tokensPerValidQuestion <= 0) {
            throw new IllegalArgumentException("tokensPerValidQuestion must be greater than zero");
        }
    }

    @Override
    public long quoteForRequestedQuestions(int requestedQuestionCount) {
        if (requestedQuestionCount < 0) {
            throw new IllegalArgumentException("requestedQuestionCount must not be negative");
        }
        return Math.multiplyExact(requestedQuestionCount, tokensPerValidQuestion);
    }

    @Override
    public long settlementForAcceptedQuestions(int acceptedQuestionCount, long maximumQuotedTokens) {
        if (acceptedQuestionCount < 0) {
            throw new IllegalArgumentException("acceptedQuestionCount must not be negative");
        }
        if (maximumQuotedTokens < 0) {
            throw new IllegalArgumentException("maximumQuotedTokens must not be negative");
        }
        return Math.min(quoteForRequestedQuestions(acceptedQuestionCount), maximumQuotedTokens);
    }
}
