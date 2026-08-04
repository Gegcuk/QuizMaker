package uk.gegc.quizmaker.features.billing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Tariff shared with the frontend estimator:
 * {@code ceil(0.35 * characters / 1000) * activeQuestionTypes + 3}.
 */
public record ContentLengthPerQuestionTypeTariff(
        String version,
        long baseTokens,
        BigDecimal tokensPerThousandCharacters
) implements GenerationTariff {

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1_000L);

    public ContentLengthPerQuestionTypeTariff {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("tariff version must not be blank");
        }
        if (baseTokens < 0L) {
            throw new IllegalArgumentException("baseTokens must not be negative");
        }
        if (tokensPerThousandCharacters == null || tokensPerThousandCharacters.signum() <= 0) {
            throw new IllegalArgumentException("tokensPerThousandCharacters must be greater than zero");
        }
    }

    @Override
    public long quoteForContent(long sourceCharacterCount, int requestedQuestionTypeCount) {
        validateUsageInputs(sourceCharacterCount, requestedQuestionTypeCount);
        long contentTokensPerType = tokensPerThousandCharacters
                .multiply(BigDecimal.valueOf(sourceCharacterCount))
                .divide(THOUSAND)
                .setScale(0, RoundingMode.CEILING)
                .longValueExact();
        return Math.addExact(baseTokens, Math.multiplyExact(contentTokensPerType, requestedQuestionTypeCount));
    }

    @Override
    public long settlementForAcceptedQuestionTypes(
            long sourceCharacterCount,
            int acceptedQuestionTypeCount,
            long maximumQuotedTokens
    ) {
        validateUsageInputs(sourceCharacterCount, acceptedQuestionTypeCount);
        if (maximumQuotedTokens < 0L) {
            throw new IllegalArgumentException("maximumQuotedTokens must not be negative");
        }
        if (acceptedQuestionTypeCount == 0) {
            return 0L;
        }
        return Math.min(quoteForContent(sourceCharacterCount, acceptedQuestionTypeCount), maximumQuotedTokens);
    }

    private void validateUsageInputs(long sourceCharacterCount, int questionTypeCount) {
        if (sourceCharacterCount < 0L) {
            throw new IllegalArgumentException("sourceCharacterCount must not be negative");
        }
        if (questionTypeCount < 0) {
            throw new IllegalArgumentException("questionTypeCount must not be negative");
        }
    }
}
