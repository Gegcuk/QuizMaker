package uk.gegc.quizmaker.features.billing.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.billing.domain.exception.CheckoutPackMismatchException;
import uk.gegc.quizmaker.features.billing.domain.exception.PackNotFoundException;
import uk.gegc.quizmaker.features.billing.domain.model.ProductPack;
import uk.gegc.quizmaker.features.billing.infra.repository.ProductPackRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckoutPackResolverImplTest {

    @Mock
    private ProductPackRepository productPackRepository;

    private CheckoutPackResolverImpl resolver;

    @BeforeEach
    void setUp() {
        resolver = new CheckoutPackResolverImpl(productPackRepository);
    }

    @Test
    void resolvesAnActivePackIdAndUsesItsConfiguredPrice() {
        UUID packId = UUID.randomUUID();
        ProductPack pack = pack(packId, "price_standard", true);
        when(productPackRepository.findById(packId)).thenReturn(Optional.of(pack));

        assertThat(resolver.resolve(packId, null)).isSameAs(pack);
        verify(productPackRepository).findById(packId);
    }

    @Test
    void resolvesTheLegacyPriceOnlyWhenItMapsToAnActivePack() {
        ProductPack pack = pack(UUID.randomUUID(), "price_legacy", true);
        when(productPackRepository.findByStripePriceId("price_legacy")).thenReturn(Optional.of(pack));

        assertThat(resolver.resolve(null, "price_legacy")).isSameAs(pack);
    }

    @Test
    void rejectsConflictingPackAndLegacyPriceBeforeStripeIsCalled() {
        UUID packId = UUID.randomUUID();
        ProductPack pack = pack(packId, "price_standard", true);
        when(productPackRepository.findById(packId)).thenReturn(Optional.of(pack));

        assertThatThrownBy(() -> resolver.resolve(packId, "price_cheaper"))
                .isInstanceOf(CheckoutPackMismatchException.class)
                .hasMessageContaining("different token packs");
    }

    @Test
    void rejectsInactiveOrUnknownPacks() {
        UUID inactiveId = UUID.randomUUID();
        when(productPackRepository.findById(inactiveId)).thenReturn(Optional.of(pack(inactiveId, "price_disabled", false)));

        assertThatThrownBy(() -> resolver.resolve(inactiveId, null)).isInstanceOf(PackNotFoundException.class);
        assertThatThrownBy(() -> resolver.resolve(null, "price_unknown")).isInstanceOf(PackNotFoundException.class);
    }

    private static ProductPack pack(UUID id, String priceId, boolean active) {
        ProductPack pack = new ProductPack();
        pack.setId(id);
        pack.setStripePriceId(priceId);
        pack.setActive(active);
        return pack;
    }
}
