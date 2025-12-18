package uk.gegc.quizmaker.features.billing.application.impl;

import com.stripe.StripeClient;
import com.stripe.model.Price;
import com.stripe.model.PriceCollection;
import com.stripe.model.Product;
import com.stripe.param.PriceListParams;
import com.stripe.service.PriceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.billing.application.BillingProperties;
import uk.gegc.quizmaker.features.billing.application.StripeProperties;
import uk.gegc.quizmaker.features.billing.domain.model.ProductPack;
import uk.gegc.quizmaker.features.billing.infra.repository.ProductPackRepository;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StripePackSyncServiceImplTest {

    @Mock
    private ProductPackRepository productPackRepository;

    @Mock
    private StripeProperties stripeProperties;

    @Mock
    private BillingProperties billingProperties;

    @Mock
    private StripeClient stripeClient;

    @Mock
    private PriceService priceService;

    private StripePackSyncServiceImpl syncService;

    @BeforeEach
    void setUp() throws Exception {
        syncService = new StripePackSyncServiceImpl(productPackRepository, stripeProperties, billingProperties);
        when(stripeProperties.getSecretKey()).thenReturn("sk_test_123");

        var field = StripePackSyncServiceImpl.class.getDeclaredField("stripeClient");
        field.setAccessible(true);
        field.set(syncService, stripeClient);
    }

    @Test
    @DisplayName("syncActivePacks: creates packs for multiple currencies and deactivates missing ones")
    void syncActivePacks_createsMultiCurrencyAndDeactivatesMissing() throws Exception {
        ProductPack existing = new ProductPack();
        existing.setId(UUID.randomUUID());
        existing.setStripePriceId("price_old");
        existing.setActive(true);
        when(productPackRepository.findAll()).thenReturn(List.of(existing));
        when(productPackRepository.save(any(ProductPack.class))).thenAnswer(inv -> inv.getArgument(0));

        Price usdPrice = mock(Price.class);
        when(usdPrice.getId()).thenReturn("price_usd");
        when(usdPrice.getCurrency()).thenReturn("usd");
        when(usdPrice.getUnitAmount()).thenReturn(1000L);
        when(usdPrice.getMetadata()).thenReturn(Map.of("tokens", "1000"));
        Product usdProduct = mock(Product.class);
        when(usdProduct.getName()).thenReturn("USD Pack");
        when(usdProduct.getDescription()).thenReturn("USD description");
        when(usdPrice.getProductObject()).thenReturn(usdProduct);

        Price gbpPrice = mock(Price.class);
        when(gbpPrice.getId()).thenReturn("price_gbp");
        when(gbpPrice.getCurrency()).thenReturn("gbp");
        when(gbpPrice.getUnitAmount()).thenReturn(2500L);
        when(gbpPrice.getMetadata()).thenReturn(Map.of("tokens", "5000"));
        Product gbpProduct = mock(Product.class);
        when(gbpProduct.getName()).thenReturn("GBP Pack");
        when(gbpProduct.getDescription()).thenReturn("GBP description");
        when(gbpPrice.getProductObject()).thenReturn(gbpProduct);

        PriceCollection collection = mock(PriceCollection.class);
        when(collection.getData()).thenReturn(List.of(usdPrice, gbpPrice));

        when(stripeClient.prices()).thenReturn(priceService);
        when(priceService.list(any(PriceListParams.class))).thenReturn(collection);

        syncService.syncActivePacks();

        verify(stripeClient.prices(), times(1)).list(any(PriceListParams.class));
        ArgumentCaptor<ProductPack> captor = ArgumentCaptor.forClass(ProductPack.class);
        verify(productPackRepository, atLeast(3)).save(captor.capture());

        var savedByPriceId = captor.getAllValues().stream()
                .collect(java.util.stream.Collectors.toMap(ProductPack::getStripePriceId, p -> p, (a, b) -> a));

        assertThat(savedByPriceId.get("price_usd"))
                .extracting(ProductPack::isActive, ProductPack::getCurrency, ProductPack::getTokens,
                        ProductPack::getPriceCents, ProductPack::getName, ProductPack::getDescription)
                .containsExactly(true, "usd", 1000L, 1000L, "USD Pack", "USD description");

        assertThat(savedByPriceId.get("price_gbp"))
                .extracting(ProductPack::isActive, ProductPack::getCurrency, ProductPack::getTokens,
                        ProductPack::getPriceCents, ProductPack::getName, ProductPack::getDescription)
                .containsExactly(true, "gbp", 5000L, 2500L, "GBP Pack", "GBP description");

        assertThat(savedByPriceId.get("price_old"))
                .extracting(ProductPack::isActive)
                .isEqualTo(false);
    }
}
