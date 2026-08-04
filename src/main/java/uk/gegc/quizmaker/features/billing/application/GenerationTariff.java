package uk.gegc.quizmaker.features.billing.application;

/**
 * Customer-facing pricing rules for one version of quiz generation.
 *
 * <p>Implementations must calculate quotes and settlement from the inputs that
 * their version explicitly supports. Provider usage is deliberately not part of
 * this contract: it is operational telemetry, not a customer price input.</p>
 */
public interface GenerationTariff {

    String version();

    long tokensPerValidQuestion();

    long quoteForRequestedQuestions(int requestedQuestionCount);

    long settlementForAcceptedQuestions(int acceptedQuestionCount, long maximumQuotedTokens);
}
