package uk.gegc.quizmaker.features.billing.application.impl;

import com.stripe.StripeClient;
import com.stripe.model.Price;
import com.stripe.model.Product;
import com.stripe.service.PriceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.billing.api.dto.CheckoutSessionStatus;
import uk.gegc.quizmaker.features.billing.api.dto.ConfigResponse;
import uk.gegc.quizmaker.features.billing.api.dto.PackDto;
import uk.gegc.quizmaker.features.billing.application.StripeProperties;
import uk.gegc.quizmaker.features.billing.domain.exception.InvalidCheckoutSessionException;
import uk.gegc.quizmaker.features.billing.domain.model.Payment;
import uk.gegc.quizmaker.features.billing.domain.model.ProductPack;
import uk.gegc.quizmaker.features.billing.infra.mapping.PaymentMapper;
import uk.gegc.quizmaker.features.billing.infra.repository.PaymentRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.ProductPackRepository;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CheckoutReadService Tests")
class CheckoutReadServiceImplTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentMapper paymentMapper;

    @Mock
    private ProductPackRepository productPackRepository;

    @Mock
    private StripeProperties stripeProperties;

    @Mock
    private StripeClient stripeClient;

    @Mock
    private PriceService priceService;

    private CheckoutReadServiceImpl checkoutReadService;

    @BeforeEach
    void setUp() {
        checkoutReadService = new CheckoutReadServiceImpl(
                paymentRepository,
                paymentMapper,
                productPackRepository,
                stripeProperties
        );
    }

    @Nested
    @DisplayName("getCheckoutSessionStatus Tests")
    class GetCheckoutSessionStatusTests {

        @Test
        @DisplayName("should return checkout session status when session exists and user matches")
        void getCheckoutSessionStatus_validSessionAndUser_returnsStatus() {
            // Given
            String sessionId = "cs_test_123";
            UUID userId = UUID.randomUUID();
            Payment payment = new Payment();
            payment.setStripeSessionId(sessionId);
            payment.setUserId(userId);

            CheckoutSessionStatus expectedStatus = new CheckoutSessionStatus(
                    sessionId, "complete", true, 1000L
            );

            when(paymentRepository.findByStripeSessionId(sessionId))
                    .thenReturn(Optional.of(payment));
            when(paymentMapper.toCheckoutSessionStatus(payment))
                    .thenReturn(expectedStatus);

            // When
            CheckoutSessionStatus result = checkoutReadService.getCheckoutSessionStatus(sessionId, userId);

            // Then
            assertThat(result).isEqualTo(expectedStatus);
            verify(paymentRepository).findByStripeSessionId(sessionId);
            verify(paymentMapper).toCheckoutSessionStatus(payment);
        }

        @Test
        @DisplayName("should throw exception when checkout session not found")
        void getCheckoutSessionStatus_sessionNotFound_throwsException() {
            // Given
            String sessionId = "cs_test_nonexistent";
            UUID userId = UUID.randomUUID();

            when(paymentRepository.findByStripeSessionId(sessionId))
                    .thenReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> checkoutReadService.getCheckoutSessionStatus(sessionId, userId))
                    .isInstanceOf(InvalidCheckoutSessionException.class)
                    .hasMessageContaining("Error retrieving checkout session");

            verify(paymentRepository).findByStripeSessionId(sessionId);
            verifyNoInteractions(paymentMapper);
        }

        @Test
        @DisplayName("should throw exception when user ID does not match")
        void getCheckoutSessionStatus_userMismatch_throwsException() {
            // Given
            String sessionId = "cs_test_123";
            UUID ownerId = UUID.randomUUID();
            UUID differentUserId = UUID.randomUUID();

            Payment payment = new Payment();
            payment.setStripeSessionId(sessionId);
            payment.setUserId(ownerId);

            when(paymentRepository.findByStripeSessionId(sessionId))
                    .thenReturn(Optional.of(payment));

            // When & Then
            assertThatThrownBy(() -> checkoutReadService.getCheckoutSessionStatus(sessionId, differentUserId))
                    .isInstanceOf(InvalidCheckoutSessionException.class)
                    .hasMessageContaining("Error retrieving checkout session");

            verify(paymentRepository).findByStripeSessionId(sessionId);
            verifyNoInteractions(paymentMapper);
        }

        @Test
        @DisplayName("should throw exception when repository throws exception")
        void getCheckoutSessionStatus_repositoryException_throwsException() {
            // Given
            String sessionId = "cs_test_123";
            UUID userId = UUID.randomUUID();

            when(paymentRepository.findByStripeSessionId(sessionId))
                    .thenThrow(new RuntimeException("Database error"));

            // When & Then
            assertThatThrownBy(() -> checkoutReadService.getCheckoutSessionStatus(sessionId, userId))
                    .isInstanceOf(InvalidCheckoutSessionException.class)
                    .hasMessageContaining("Error retrieving checkout session");

            verify(paymentRepository).findByStripeSessionId(sessionId);
        }
    }

    @Nested
    @DisplayName("getBillingConfig Tests")
    class GetBillingConfigTests {

        @Test
        @DisplayName("should return billing config with publishable key and packs")
        void getBillingConfig_validConfiguration_returnsConfig() {
            // Given
            String publishableKey = "pk_test_12345";
            ProductPack pack = createProductPack("Starter", 1000L, 999L);
            pack.setDescription("Starter pack description");

            when(stripeProperties.getPublishableKey()).thenReturn(publishableKey);
            when(productPackRepository.findByActiveTrue()).thenReturn(List.of(pack));

            // When
            ConfigResponse result = checkoutReadService.getBillingConfig();

            // Then
            assertThat(result.publishableKey()).isEqualTo(publishableKey);
            assertThat(result.prices()).hasSize(1);
            assertThat(result.prices().get(0).name()).isEqualTo("Starter");
            assertThat(result.prices().get(0).description()).isEqualTo("Starter pack description");
            assertThat(result.prices().get(0).tokens()).isEqualTo(1000L);

            verify(stripeProperties).getPublishableKey();
            verify(productPackRepository).findByActiveTrue();
        }

        @Test
        @DisplayName("should throw exception when publishable key is null")
        void getBillingConfig_nullPublishableKey_throwsException() {
            // Given
            when(stripeProperties.getPublishableKey()).thenReturn(null);

            // When & Then
            assertThatThrownBy(() -> checkoutReadService.getBillingConfig())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Billing configuration not available");

            verify(stripeProperties).getPublishableKey();
            verifyNoInteractions(productPackRepository);
        }

        @Test
        @DisplayName("should throw exception when publishable key is blank")
        void getBillingConfig_blankPublishableKey_throwsException() {
            // Given
            when(stripeProperties.getPublishableKey()).thenReturn("   ");

            // When & Then
            assertThatThrownBy(() -> checkoutReadService.getBillingConfig())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Billing configuration not available");

            verify(stripeProperties).getPublishableKey();
            verifyNoInteractions(productPackRepository);
        }

        @Test
        @DisplayName("should throw exception when publishable key is empty")
        void getBillingConfig_emptyPublishableKey_throwsException() {
            // Given
            when(stripeProperties.getPublishableKey()).thenReturn("");

            // When & Then
            assertThatThrownBy(() -> checkoutReadService.getBillingConfig())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Billing configuration not available");

            verify(stripeProperties).getPublishableKey();
            verifyNoInteractions(productPackRepository);
        }
    }

    @Nested
    @DisplayName("getAvailablePacks Tests")
    class GetAvailablePacksTests {

        @Test
        @DisplayName("should return packs from database when available")
        void getAvailablePacks_packsInDatabase_returnsPacksFromDb() {
            // Given
            ProductPack pack1 = createProductPack("Starter", 1000L, 999L);
            ProductPack pack2 = createProductPack("Pro", 5000L, 4999L);
            pack1.setDescription("Starter description");
            pack2.setDescription("Pro description");

            when(productPackRepository.findByActiveTrue()).thenReturn(List.of(pack1, pack2));

            // When
            List<PackDto> result = checkoutReadService.getAvailablePacks();

            // Then
            assertThat(result).hasSize(2);
            assertThat(result.get(0).name()).isEqualTo("Starter");
            assertThat(result.get(0).description()).isEqualTo("Starter description");
            assertThat(result.get(0).tokens()).isEqualTo(1000L);
            assertThat(result.get(0).priceCents()).isEqualTo(999L);
            assertThat(result.get(1).name()).isEqualTo("Pro");
            assertThat(result.get(1).description()).isEqualTo("Pro description");
            assertThat(result.get(1).tokens()).isEqualTo(5000L);

            verify(productPackRepository).findByActiveTrue();
        }

        @Test
        @DisplayName("should return fallback packs when database is empty")
        void getAvailablePacks_emptyDatabase_returnsFallbackPacks() {
            // Given
            when(productPackRepository.findByActiveTrue()).thenReturn(Collections.emptyList());
            when(stripeProperties.getPriceSmall()).thenReturn("price_small");
            when(stripeProperties.getPriceMedium()).thenReturn("price_medium");
            when(stripeProperties.getPriceLarge()).thenReturn("price_large");

            // When
            List<PackDto> result = checkoutReadService.getAvailablePacks();

            // Then
            assertThat(result).hasSize(3);
            assertThat(result.get(0).name()).isEqualTo("Starter Pack");
            assertThat(result.get(0).tokens()).isEqualTo(1000L);
            assertThat(result.get(1).name()).isEqualTo("Growth Pack");
            assertThat(result.get(1).tokens()).isEqualTo(5000L);
            assertThat(result.get(2).name()).isEqualTo("Pro Pack");
            assertThat(result.get(2).tokens()).isEqualTo(10000L);

            verify(productPackRepository).findByActiveTrue();
        }

        @Test
        @DisplayName("should return empty list when database empty and fallback fails")
        void getAvailablePacks_emptyDatabaseAndFallbackFails_returnsEmptyList() {
            // Given
            when(productPackRepository.findByActiveTrue()).thenReturn(Collections.emptyList());
            when(stripeProperties.getPriceSmall()).thenThrow(new RuntimeException("Config error"));

            // When
            List<PackDto> result = checkoutReadService.getAvailablePacks();

            // Then
            assertThat(result).isEmpty();
            verify(productPackRepository).findByActiveTrue();
        }

        @Test
        @DisplayName("should skip packs with null or blank price IDs in fallback")
        void getAvailablePacks_nullPriceIds_skipsThosePacks() {
            // Given
            when(productPackRepository.findByActiveTrue()).thenReturn(Collections.emptyList());
            when(stripeProperties.getPriceSmall()).thenReturn(null);
            when(stripeProperties.getPriceMedium()).thenReturn("  ");
            when(stripeProperties.getPriceLarge()).thenReturn("price_large");

            // When
            List<PackDto> result = checkoutReadService.getAvailablePacks();

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).name()).isEqualTo("Pro Pack");
            assertThat(result.get(0).stripePriceId()).isEqualTo("price_large");
        }
    }

    @Nested
    @DisplayName("Fallback Pack Building Tests")
    class FallbackPackBuildingTests {

        @BeforeEach
        void setUpStripeClient() {
            // Inject the mocked StripeClient using reflection
            try {
                var field = CheckoutReadServiceImpl.class.getDeclaredField("stripeClient");
                field.setAccessible(true);
                field.set(checkoutReadService, stripeClient);
            } catch (Exception e) {
                throw new RuntimeException("Failed to inject StripeClient", e);
            }
        }

        @Test
        @DisplayName("should retrieve price details from Stripe when available")
        void fallbackPack_withStripePrice_retrievesPriceDetails() throws Exception {
            // Given
            when(productPackRepository.findByActiveTrue()).thenReturn(Collections.emptyList());
            when(stripeProperties.getPriceSmall()).thenReturn("price_123");
            when(stripeProperties.getPriceMedium()).thenReturn(null);
            when(stripeProperties.getPriceLarge()).thenReturn(null);

            Price price = mock(Price.class);
            when(price.getUnitAmount()).thenReturn(1999L);
            when(price.getCurrency()).thenReturn("eur");
            Map<String, String> metadata = new HashMap<>();
            metadata.put("tokens", "2500");
            when(price.getMetadata()).thenReturn(metadata);

            Product product = mock(Product.class);
            when(product.getName()).thenReturn("Custom Starter");
            when(product.getDescription()).thenReturn("Custom Starter description");
            when(price.getProductObject()).thenReturn(product);

            when(stripeClient.prices()).thenReturn(priceService);
            when(priceService.retrieve("price_123")).thenReturn(price);

            // When
            List<PackDto> result = checkoutReadService.getAvailablePacks();

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).name()).isEqualTo("Custom Starter");
            assertThat(result.get(0).description()).isEqualTo("Custom Starter description");
            assertThat(result.get(0).tokens()).isEqualTo(2500L);
            assertThat(result.get(0).priceCents()).isEqualTo(1999L);
            assertThat(result.get(0).currency()).isEqualTo("eur");
            assertThat(result.get(0).stripePriceId()).isEqualTo("price_123");
        }

        @Test
        @DisplayName("should use product metadata tokens if price metadata not available")
        void fallbackPack_productMetadata_usesProductTokens() throws Exception {
            // Given
            when(productPackRepository.findByActiveTrue()).thenReturn(Collections.emptyList());
            when(stripeProperties.getPriceSmall()).thenReturn("price_456");
            when(stripeProperties.getPriceMedium()).thenReturn(null);
            when(stripeProperties.getPriceLarge()).thenReturn(null);

            Price price = mock(Price.class);
            when(price.getUnitAmount()).thenReturn(2999L);
            when(price.getCurrency()).thenReturn("gbp");
            when(price.getMetadata()).thenReturn(new HashMap<>());

            Product product = mock(Product.class);
            when(product.getName()).thenReturn("Premium Pack");
            Map<String, String> productMetadata = new HashMap<>();
            productMetadata.put("tokens", "3500");
            when(product.getMetadata()).thenReturn(productMetadata);
            when(price.getProductObject()).thenReturn(product);

            when(stripeClient.prices()).thenReturn(priceService);
            when(priceService.retrieve("price_456")).thenReturn(price);

            // When
            List<PackDto> result = checkoutReadService.getAvailablePacks();

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).name()).isEqualTo("Premium Pack");
            assertThat(result.get(0).tokens()).isEqualTo(3500L);
            assertThat(result.get(0).currency()).isEqualTo("gbp");
        }

        @Test
        @DisplayName("should use defaults when Stripe API call fails")
        void fallbackPack_stripeApiFails_usesDefaults() throws Exception {
            // Given
            when(productPackRepository.findByActiveTrue()).thenReturn(Collections.emptyList());
            when(stripeProperties.getPriceSmall()).thenReturn("price_error");
            when(stripeProperties.getPriceMedium()).thenReturn(null);
            when(stripeProperties.getPriceLarge()).thenReturn(null);

            when(stripeClient.prices()).thenReturn(priceService);
            when(priceService.retrieve("price_error"))
                    .thenThrow(new RuntimeException("Stripe API error"));

            // When
            List<PackDto> result = checkoutReadService.getAvailablePacks();

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).name()).isEqualTo("Starter Pack");
            assertThat(result.get(0).tokens()).isEqualTo(1000L);
            assertThat(result.get(0).priceCents()).isEqualTo(0L);
            assertThat(result.get(0).currency()).isEqualTo("usd");
            assertThat(result.get(0).stripePriceId()).isEqualTo("price_error");
        }

        @Test
        @DisplayName("should handle invalid token metadata gracefully")
        void fallbackPack_invalidTokenMetadata_usesDefaults() throws Exception {
            // Given
            when(productPackRepository.findByActiveTrue()).thenReturn(Collections.emptyList());
            when(stripeProperties.getPriceSmall()).thenReturn("price_789");
            when(stripeProperties.getPriceMedium()).thenReturn(null);
            when(stripeProperties.getPriceLarge()).thenReturn(null);

            Price price = mock(Price.class);
            when(price.getUnitAmount()).thenReturn(999L);
            when(price.getCurrency()).thenReturn("usd");
            Map<String, String> metadata = new HashMap<>();
            metadata.put("tokens", "not-a-number");
            when(price.getMetadata()).thenReturn(metadata);
            when(price.getProductObject()).thenReturn(null);

            when(stripeClient.prices()).thenReturn(priceService);
            when(priceService.retrieve("price_789")).thenReturn(price);

            // When
            List<PackDto> result = checkoutReadService.getAvailablePacks();

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).tokens()).isEqualTo(1000L); // Default value
        }

        @Test
        @DisplayName("should handle null price response gracefully")
        void fallbackPack_nullPriceResponse_usesDefaults() throws Exception {
            // Given
            when(productPackRepository.findByActiveTrue()).thenReturn(Collections.emptyList());
            when(stripeProperties.getPriceSmall()).thenReturn("price_null");
            when(stripeProperties.getPriceMedium()).thenReturn(null);
            when(stripeProperties.getPriceLarge()).thenReturn(null);

            when(stripeClient.prices()).thenReturn(priceService);
            when(priceService.retrieve("price_null")).thenReturn(null);

            // When
            List<PackDto> result = checkoutReadService.getAvailablePacks();

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).name()).isEqualTo("Starter Pack");
            assertThat(result.get(0).tokens()).isEqualTo(1000L);
            assertThat(result.get(0).priceCents()).isEqualTo(0L);
        }
    }

    // Helper method to create ProductPack
    private ProductPack createProductPack(String name, long tokens, long priceCents) {
        ProductPack pack = new ProductPack();
        pack.setId(UUID.randomUUID());
        pack.setName(name);
        pack.setTokens(tokens);
        pack.setPriceCents(priceCents);
        pack.setCurrency("usd");
        pack.setStripePriceId("price_" + name.toLowerCase());
        return pack;
    }
}
