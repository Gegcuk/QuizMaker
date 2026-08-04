package uk.gegc.quizmaker.features.billing.application.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.billing.application.BillingProperties;
import uk.gegc.quizmaker.features.billing.application.FixedRatePerValidQuestionTariff;
import uk.gegc.quizmaker.features.billing.application.GenerationTariff;
import uk.gegc.quizmaker.features.billing.application.GenerationTariffService;

@Service
@RequiredArgsConstructor
public class GenerationTariffServiceImpl implements GenerationTariffService {

    private final BillingProperties billingProperties;

    @Override
    public GenerationTariff currentTariff() {
        BillingProperties.GenerationPricing pricing = billingProperties.getGeneration();
        return new FixedRatePerValidQuestionTariff(
                pricing.getTariffVersion(),
                pricing.getTokensPerValidQuestion()
        );
    }
}
