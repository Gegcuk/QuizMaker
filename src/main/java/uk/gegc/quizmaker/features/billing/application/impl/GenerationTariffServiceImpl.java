package uk.gegc.quizmaker.features.billing.application.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.billing.application.BillingProperties;
import uk.gegc.quizmaker.features.billing.application.ContentLengthPerQuestionTypeTariff;
import uk.gegc.quizmaker.features.billing.application.GenerationTariff;
import uk.gegc.quizmaker.features.billing.application.GenerationTariffService;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class GenerationTariffServiceImpl implements GenerationTariffService {

    private final BillingProperties billingProperties;

    @Override
    public GenerationTariff currentTariff() {
        BillingProperties.GenerationPricing pricing = billingProperties.getGeneration();
        return new ContentLengthPerQuestionTypeTariff(
                pricing.getTariffVersion(),
                pricing.getBaseTokens(),
                pricing.getTokensPerThousandCharacters()
        );
    }

    @Override
    public GenerationTariff fromSnapshot(
            String tariffVersion,
            long baseTokens,
            BigDecimal tokensPerThousandCharacters
    ) {
        return new ContentLengthPerQuestionTypeTariff(
                tariffVersion,
                baseTokens,
                tokensPerThousandCharacters
        );
    }
}
