package uk.gegc.quizmaker.features.billing.application;

import java.math.BigDecimal;

/**
 * Customer-facing pricing rules for one version of quiz generation.
 *
 * <p>Implementations must calculate quotes and settlement from the inputs that
 * their version explicitly supports. Provider usage is deliberately not part of
 * this contract: it is operational telemetry, not a customer price input.</p>
 */
public interface GenerationTariff {

    String version();

    long baseTokens();

    BigDecimal tokensPerThousandCharacters();

    long quoteForContent(long sourceCharacterCount, int requestedQuestionTypeCount);

    long settlementForAcceptedQuestionTypes(
            long sourceCharacterCount,
            int acceptedQuestionTypeCount,
            long maximumQuotedTokens
    );
}
