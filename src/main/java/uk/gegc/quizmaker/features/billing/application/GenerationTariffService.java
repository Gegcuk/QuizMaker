package uk.gegc.quizmaker.features.billing.application;

/**
 * Provides the tariff used when a new quiz-generation quote is created.
 */
public interface GenerationTariffService {

    GenerationTariff currentTariff();
}
