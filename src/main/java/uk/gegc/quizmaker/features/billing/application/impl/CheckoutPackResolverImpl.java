package uk.gegc.quizmaker.features.billing.application.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import uk.gegc.quizmaker.features.billing.application.CheckoutPackResolver;
import uk.gegc.quizmaker.features.billing.domain.exception.CheckoutPackMismatchException;
import uk.gegc.quizmaker.features.billing.domain.exception.PackNotFoundException;
import uk.gegc.quizmaker.features.billing.domain.model.ProductPack;
import uk.gegc.quizmaker.features.billing.infra.repository.ProductPackRepository;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CheckoutPackResolverImpl implements CheckoutPackResolver {

    private final ProductPackRepository productPackRepository;

    @Override
    public ProductPack resolve(UUID packId, String legacyPriceId) {
        ProductPack pack = packId != null
                ? productPackRepository.findById(packId)
                        .orElseThrow(() -> new PackNotFoundException("Requested token pack was not found"))
                : resolveLegacyPrice(legacyPriceId);

        if (!pack.isActive()) {
            throw new PackNotFoundException("Requested token pack is not available");
        }
        if (StringUtils.hasText(legacyPriceId) && !legacyPriceId.trim().equals(pack.getStripePriceId())) {
            throw new CheckoutPackMismatchException("packId and priceId identify different token packs");
        }
        return pack;
    }

    private ProductPack resolveLegacyPrice(String legacyPriceId) {
        if (!StringUtils.hasText(legacyPriceId)) {
            throw new PackNotFoundException("A token pack must be selected");
        }
        return productPackRepository.findByStripePriceId(legacyPriceId.trim())
                .orElseThrow(() -> new PackNotFoundException("Requested token pack was not found"));
    }
}
