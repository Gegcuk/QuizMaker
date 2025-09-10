package uk.gegc.quizmaker.features.billing.application;

public interface StripeWebhookService {

    enum Result { OK, DUPLICATE, IGNORED }

    Result process(String payload, String signatureHeader);
}
