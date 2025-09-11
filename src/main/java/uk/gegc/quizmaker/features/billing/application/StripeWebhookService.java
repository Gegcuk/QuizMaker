package uk.gegc.quizmaker.features.billing.application;

import com.stripe.exception.StripeException;

public interface StripeWebhookService {

    enum Result { OK, DUPLICATE, IGNORED }

    Result process(String payload, String signatureHeader) throws StripeException;
}
