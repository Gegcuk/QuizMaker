package uk.gegc.quizmaker.features.billing.application.impl;

import com.stripe.model.LineItem;
import com.stripe.model.LineItemCollection;
import com.stripe.model.Price;
import com.stripe.model.checkout.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.billing.application.BillingProperties;
import uk.gegc.quizmaker.features.billing.application.CheckoutValidationService;
import uk.gegc.quizmaker.features.billing.domain.exception.InvalidCheckoutSessionException;
import uk.gegc.quizmaker.features.billing.domain.model.ProductPack;
import uk.gegc.quizmaker.features.billing.infra.repository.ProductPackRepository;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CheckoutValidationService Tests")
class CheckoutValidationServiceImplTest {

    @Mock
    private ProductPackRepository productPackRepository;

    @Mock
    private BillingProperties billingProperties;

    private CheckoutValidationServiceImpl checkoutValidationService;

    @BeforeEach
    void setUp() {
        checkoutValidationService = new CheckoutValidationServiceImpl(
            productPackRepository,
            billingProperties
        );
    }

    @Nested
    @DisplayName("validateAndResolvePack - Basic Tests")
    class BasicValidationTests {

        @Test
        @DisplayName("should resolve pack from metadata and validate successfully")
        void validateAndResolvePack_withMetadata_resolvesSuccessfully() {
            // Given
            UUID packId = UUID.randomUUID();
            ProductPack pack = createProductPack("USD", 1000L, 100L);
            Session session = createMockSession("USD", 1000L, null);
            
            when(productPackRepository.findById(packId)).thenReturn(Optional.of(pack));

            // When
            CheckoutValidationService.CheckoutValidationResult result = 
                checkoutValidationService.validateAndResolvePack(session, packId);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.primaryPack()).isEqualTo(pack);
            assertThat(result.totalTokens()).isEqualTo(100L);
            assertThat(result.totalAmountCents()).isEqualTo(1000L);
            assertThat(result.currency()).isEqualTo("USD");
            assertThat(result.hasMultipleLineItems()).isFalse();
            assertThat(result.additionalPacks()).isNull();
        }

        @Test
        @DisplayName("should throw exception when pack not found in metadata")
        void validateAndResolvePack_packNotFound_throwsException() {
            // Given
            UUID packId = UUID.randomUUID();
            Session session = createMockSession("USD", 1000L, null);
            
            when(productPackRepository.findById(packId)).thenReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> checkoutValidationService.validateAndResolvePack(session, packId))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("Pack referenced in metadata not found");
        }

        @Test
        @DisplayName("should resolve pack from line items when no metadata packId")
        void validateAndResolvePack_fromLineItems_resolvesSuccessfully() {
            // Given
            ProductPack pack = createProductPack("EUR", 2000L, 200L);
            String priceId = pack.getStripePriceId();
            
            Session session = createMockSessionWithLineItems("EUR", 2000L, List.of(priceId));
            
            when(productPackRepository.findByStripePriceId(priceId)).thenReturn(Optional.of(pack));

            // When
            CheckoutValidationService.CheckoutValidationResult result = 
                checkoutValidationService.validateAndResolvePack(session, null);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.primaryPack()).isEqualTo(pack);
            assertThat(result.totalTokens()).isEqualTo(200L);
        }

        @Test
        @DisplayName("should throw exception when cannot resolve pack")
        void validateAndResolvePack_cannotResolve_throwsException() {
            // Given
            String priceId = "price_unknown";
            Session session = createMockSessionWithLineItems("USD", 1000L, List.of(priceId));
            
            when(productPackRepository.findByStripePriceId(priceId)).thenReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> checkoutValidationService.validateAndResolvePack(session, null))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("Unable to resolve pack from session");
        }
    }

    @Nested
    @DisplayName("Currency Validation Tests")
    class CurrencyValidationTests {

        @Test
        @DisplayName("should throw exception when session currency does not match pack currency")
        void validateCurrency_mismatch_throwsException() {
            // Given
            UUID packId = UUID.randomUUID();
            ProductPack pack = createProductPack("USD", 1000L, 100L);
            Session session = createMockSession("EUR", 1000L, null); // Different currency
            
            when(productPackRepository.findById(packId)).thenReturn(Optional.of(pack));

            // When & Then
            assertThatThrownBy(() -> checkoutValidationService.validateAndResolvePack(session, packId))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("Currency mismatch")
                .hasMessageContaining("EUR")
                .hasMessageContaining("USD");
        }

        @Test
        @DisplayName("should handle case-insensitive currency comparison")
        void validateCurrency_caseInsensitive_passes() {
            // Given
            UUID packId = UUID.randomUUID();
            ProductPack pack = createProductPack("USD", 1000L, 100L);
            Session session = createMockSession("usd", 1000L, null); // Lowercase
            
            when(productPackRepository.findById(packId)).thenReturn(Optional.of(pack));

            // When
            CheckoutValidationService.CheckoutValidationResult result = 
                checkoutValidationService.validateAndResolvePack(session, packId);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.currency()).isEqualTo("USD");
        }

        @Test
        @DisplayName("should warn and continue when session has no currency")
        void validateCurrency_noCurrency_warns() {
            // Given
            UUID packId = UUID.randomUUID();
            ProductPack pack = createProductPack("USD", 1000L, 100L);
            Session session = createMockSession(null, 1000L, null);
            
            when(productPackRepository.findById(packId)).thenReturn(Optional.of(pack));

            // When
            CheckoutValidationService.CheckoutValidationResult result = 
                checkoutValidationService.validateAndResolvePack(session, packId);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.currency()).isEqualTo("USD");
        }

        @Test
        @DisplayName("should warn and continue when session has blank currency")
        void validateCurrency_blankCurrency_warns() {
            // Given
            UUID packId = UUID.randomUUID();
            ProductPack pack = createProductPack("GBP", 1500L, 150L);
            Session session = createMockSession("  ", 1500L, null);
            
            when(productPackRepository.findById(packId)).thenReturn(Optional.of(pack));

            // When
            CheckoutValidationService.CheckoutValidationResult result = 
                checkoutValidationService.validateAndResolvePack(session, packId);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.currency()).isEqualTo("GBP");
        }
    }

    @Nested
    @DisplayName("Multiple Line Items Tests")
    class MultipleLineItemsTests {

        @Test
        @DisplayName("should handle multiple line items and sum tokens/amounts")
        void multipleLineItems_sumsTotals() {
            // Given
            UUID packId = UUID.randomUUID();
            ProductPack pack1 = createProductPack("USD", 1000L, 100L, "price_1");
            ProductPack pack2 = createProductPack("USD", 2000L, 200L, "price_2");
            
            Session session = createMockSessionWithLineItems("USD", 3000L, 
                List.of("price_1", "price_2"));
            
            when(productPackRepository.findById(packId)).thenReturn(Optional.of(pack1));
            when(productPackRepository.findByStripePriceId("price_2")).thenReturn(Optional.of(pack2));

            // When
            CheckoutValidationService.CheckoutValidationResult result = 
                checkoutValidationService.validateAndResolvePack(session, packId);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.hasMultipleLineItems()).isTrue();
            assertThat(result.totalTokens()).isEqualTo(300L); // 100 + 200
            assertThat(result.totalAmountCents()).isEqualTo(3000L); // 1000 + 2000
            assertThat(result.additionalPacks()).hasSize(1);
        }

        @Test
        @DisplayName("should handle exactly one line item (not multiple)")
        void singleLineItem_noAdditionalPacks() {
            // Given - This tests the branch where lineItems.getData().size() == 1 (not > 1)
            UUID packId = UUID.randomUUID();
            ProductPack pack = createProductPack("USD", 1000L, 100L, "price_1");
            
            Session session = createMockSessionWithLineItems("USD", 1000L, 
                List.of("price_1")); // Only ONE item
            
            when(productPackRepository.findById(packId)).thenReturn(Optional.of(pack));

            // When
            CheckoutValidationService.CheckoutValidationResult result = 
                checkoutValidationService.validateAndResolvePack(session, packId);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.hasMultipleLineItems()).isFalse(); // Not multiple!
            assertThat(result.totalTokens()).isEqualTo(100L);
            assertThat(result.additionalPacks()).isNull();
        }

        @Test
        @DisplayName("should handle empty line items list (size == 0)")
        void emptyLineItems_noAdditionalPacks() {
            // Given - Test when lineItems.getData().size() == 0
            UUID packId = UUID.randomUUID();
            ProductPack pack = createProductPack("USD", 1000L, 100L);
            
            LineItemCollection lineItems = mock(LineItemCollection.class);
            lenient().when(lineItems.getData()).thenReturn(Collections.emptyList()); // EMPTY!
            
            Session session = mock(Session.class);
            lenient().when(session.getCurrency()).thenReturn("USD");
            lenient().when(session.getId()).thenReturn("cs_test");
            lenient().when(session.getLineItems()).thenReturn(lineItems);
            lenient().when(session.getAmountTotal()).thenReturn(1000L);
            
            when(productPackRepository.findById(packId)).thenReturn(Optional.of(pack));

            // When
            CheckoutValidationService.CheckoutValidationResult result = 
                checkoutValidationService.validateAndResolvePack(session, packId);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.hasMultipleLineItems()).isFalse();
            assertThat(result.totalTokens()).isEqualTo(100L);
            assertThat(result.additionalPacks()).isNull();
        }

        @Test
        @DisplayName("should handle additional line item with empty/null price ID")
        void multipleLineItems_emptyPriceId_skips() {
            // Given - This tests the branch where StringUtils.hasText(priceId) is false
            UUID packId = UUID.randomUUID();
            ProductPack pack1 = createProductPack("USD", 1000L, 100L, "price_1");
            
            // Create a line item with no price ID (null)
            LineItem lineItem1 = mock(LineItem.class);
            Price price1 = mock(Price.class);
            lenient().when(price1.getId()).thenReturn("price_1");
            lenient().when(lineItem1.getPrice()).thenReturn(price1);
            lenient().when(lineItem1.getCurrency()).thenReturn("USD");
            
            LineItem lineItem2 = mock(LineItem.class);
            Price price2 = mock(Price.class);
            lenient().when(price2.getId()).thenReturn(null); // NULL price ID!
            lenient().when(lineItem2.getPrice()).thenReturn(price2);
            lenient().when(lineItem2.getCurrency()).thenReturn("USD");
            
            LineItemCollection lineItems = mock(LineItemCollection.class);
            lenient().when(lineItems.getData()).thenReturn(List.of(lineItem1, lineItem2));
            
            Session session = mock(Session.class);
            lenient().when(session.getCurrency()).thenReturn("USD");
            lenient().when(session.getId()).thenReturn("cs_test");
            lenient().when(session.getLineItems()).thenReturn(lineItems);
            lenient().when(session.getAmountTotal()).thenReturn(1000L);
            
            when(productPackRepository.findById(packId)).thenReturn(Optional.of(pack1));

            // When
            CheckoutValidationService.CheckoutValidationResult result = 
                checkoutValidationService.validateAndResolvePack(session, packId);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.hasMultipleLineItems()).isTrue();
            assertThat(result.totalTokens()).isEqualTo(100L); // Only primary pack (second skipped)
            assertThat(result.additionalPacks()).isNull(); // No additional packs added
        }

        @Test
        @DisplayName("should handle non-LineItem object in additional packs resolution")
        void multipleLineItems_nonLineItemObject_skips() {
            // Given - Test the instanceof check in extractPriceId within resolveAdditionalPacks
            UUID packId = UUID.randomUUID();
            ProductPack pack1 = createProductPack("USD", 1000L, 100L, "price_1");
            
            LineItem validLineItem = mock(LineItem.class);
            Price price1 = mock(Price.class);
            lenient().when(price1.getId()).thenReturn("price_1");
            lenient().when(validLineItem.getPrice()).thenReturn(price1);
            lenient().when(validLineItem.getCurrency()).thenReturn("USD");
            
            // Second item is not a LineItem (will fail instanceof check)
            Object invalidLineItem = new String("not a line item");
            
            LineItemCollection lineItems = mock(LineItemCollection.class);
            List<Object> mixedList = new ArrayList<>();
            mixedList.add(validLineItem);
            mixedList.add(invalidLineItem);
            when(lineItems.getData()).thenReturn((List) mixedList);
            
            Session session = mock(Session.class);
            lenient().when(session.getCurrency()).thenReturn("USD");
            lenient().when(session.getId()).thenReturn("cs_test");
            lenient().when(session.getLineItems()).thenReturn(lineItems);
            lenient().when(session.getAmountTotal()).thenReturn(1000L);
            
            when(productPackRepository.findById(packId)).thenReturn(Optional.of(pack1));

            // When - The non-LineItem will be skipped (instanceof returns false)
            CheckoutValidationService.CheckoutValidationResult result = 
                checkoutValidationService.validateAndResolvePack(session, packId);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.hasMultipleLineItems()).isTrue();
            assertThat(result.totalTokens()).isEqualTo(100L); // Only primary pack
            assertThat(result.additionalPacks()).isNull(); // Invalid item skipped
        }

        @Test
        @DisplayName("should warn when additional pack has different currency (exception caught)")
        void multipleLineItems_currencyMismatch_warns() {
            // Given
            UUID packId = UUID.randomUUID();
            ProductPack pack1 = createProductPack("USD", 1000L, 100L, "price_1");
            ProductPack pack2 = createProductPack("EUR", 2000L, 200L, "price_2"); // Different currency!
            
            Session session = createMockSessionWithLineItems("USD", 3000L, 
                List.of("price_1", "price_2"));
            
            when(productPackRepository.findById(packId)).thenReturn(Optional.of(pack1));
            when(productPackRepository.findByStripePriceId("price_2")).thenReturn(Optional.of(pack2));
            lenient().when(billingProperties.isStrictAmountValidation()).thenReturn(false);

            // When - The exception is caught and logged as a warning
            CheckoutValidationService.CheckoutValidationResult result = 
                checkoutValidationService.validateAndResolvePack(session, packId);

            // Then - Still succeeds with only primary pack
            assertThat(result).isNotNull();
            assertThat(result.totalTokens()).isEqualTo(100L); // Only primary pack counted
        }

        @Test
        @DisplayName("should warn when additional line item has unknown price ID")
        void multipleLineItems_unknownPriceId_warns() {
            // Given
            UUID packId = UUID.randomUUID();
            ProductPack pack1 = createProductPack("USD", 1000L, 100L, "price_1");
            
            Session session = createMockSessionWithLineItems("USD", 3000L, 
                List.of("price_1", "price_unknown"));
            
            when(productPackRepository.findById(packId)).thenReturn(Optional.of(pack1));
            when(productPackRepository.findByStripePriceId("price_unknown")).thenReturn(Optional.empty());
            lenient().when(billingProperties.isStrictAmountValidation()).thenReturn(false);

            // When
            CheckoutValidationService.CheckoutValidationResult result = 
                checkoutValidationService.validateAndResolvePack(session, packId);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.totalTokens()).isEqualTo(100L); // Only primary pack
            assertThat(result.additionalPacks()).isNull(); // Empty list becomes null
        }
    }

    @Nested
    @DisplayName("Amount Validation Tests")
    class AmountValidationTests {

        @Test
        @DisplayName("should warn in strict mode when amounts don't match (exception is caught)")
        void amountValidation_strictMode_warns() {
            // Given
            UUID packId = UUID.randomUUID();
            ProductPack pack = createProductPack("USD", 1000L, 100L);
            Session session = createMockSession("USD", 2000L, null); // Mismatch: session says 2000, pack is 1000
            
            when(productPackRepository.findById(packId)).thenReturn(Optional.of(pack));
            when(billingProperties.isStrictAmountValidation()).thenReturn(true);

            // When - The exception is caught internally and logged as warning
            CheckoutValidationService.CheckoutValidationResult result = 
                checkoutValidationService.validateAndResolvePack(session, packId);

            // Then - Still returns result, but with calculated amount
            assertThat(result).isNotNull();
            assertThat(result.totalAmountCents()).isEqualTo(1000L);
        }

        @Test
        @DisplayName("should warn in non-strict mode when amounts don't match")
        void amountValidation_nonStrictMode_warns() {
            // Given
            UUID packId = UUID.randomUUID();
            ProductPack pack = createProductPack("USD", 1000L, 100L);
            Session session = createMockSession("USD", 1500L, null); // Mismatch
            
            when(productPackRepository.findById(packId)).thenReturn(Optional.of(pack));
            when(billingProperties.isStrictAmountValidation()).thenReturn(false);

            // When
            CheckoutValidationService.CheckoutValidationResult result = 
                checkoutValidationService.validateAndResolvePack(session, packId);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.totalAmountCents()).isEqualTo(1000L); // Uses calculated amount
        }

        @Test
        @DisplayName("should handle null session amount gracefully")
        void amountValidation_nullAmount_handlesGracefully() {
            // Given
            UUID packId = UUID.randomUUID();
            ProductPack pack = createProductPack("USD", 1000L, 100L);
            Session session = createMockSession("USD", null, null);
            
            when(productPackRepository.findById(packId)).thenReturn(Optional.of(pack));

            // When
            CheckoutValidationService.CheckoutValidationResult result = 
                checkoutValidationService.validateAndResolvePack(session, packId);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.totalAmountCents()).isEqualTo(1000L);
        }
    }

    @Nested
    @DisplayName("Line Item Extraction Tests")
    class LineItemExtractionTests {

        @Test
        @DisplayName("should handle line items without price")
        void extractPriceId_noPriceObject_returnsNull() {
            // Given
            UUID packId = UUID.randomUUID();
            ProductPack pack = createProductPack("USD", 1000L, 100L, "price_123");
            
            LineItem lineItem = mock(LineItem.class);
            lenient().when(lineItem.getPrice()).thenReturn(null);
            
            LineItemCollection lineItems = mock(LineItemCollection.class);
            lenient().when(lineItems.getData()).thenReturn(List.of(lineItem));
            
            Session session = mock(Session.class);
            lenient().when(session.getCurrency()).thenReturn("USD");
            lenient().when(session.getId()).thenReturn("cs_test");
            lenient().when(session.getLineItems()).thenReturn(lineItems);
            lenient().when(session.getAmountTotal()).thenReturn(1000L);
            
            when(productPackRepository.findById(packId)).thenReturn(Optional.of(pack));

            // When
            CheckoutValidationService.CheckoutValidationResult result = 
                checkoutValidationService.validateAndResolvePack(session, packId);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("should handle exception when extracting price ID")
        void extractPriceId_exception_returnsNull() {
            // Given
            UUID packId = UUID.randomUUID();
            ProductPack pack = createProductPack("USD", 1000L, 100L, "price_123");
            
            LineItem lineItem = mock(LineItem.class);
            lenient().when(lineItem.getPrice()).thenThrow(new RuntimeException("Price extraction error"));
            
            LineItemCollection lineItems = mock(LineItemCollection.class);
            lenient().when(lineItems.getData()).thenReturn(List.of(lineItem));
            
            Session session = mock(Session.class);
            lenient().when(session.getCurrency()).thenReturn("USD");
            lenient().when(session.getId()).thenReturn("cs_test");
            lenient().when(session.getLineItems()).thenReturn(lineItems);
            lenient().when(session.getAmountTotal()).thenReturn(1000L);
            
            when(productPackRepository.findById(packId)).thenReturn(Optional.of(pack));

            // When
            CheckoutValidationService.CheckoutValidationResult result = 
                checkoutValidationService.validateAndResolvePack(session, packId);

            // Then
            assertThat(result).isNotNull();
        }

    }

    @Nested
    @DisplayName("Line Item Currency Validation Tests")
    class LineItemCurrencyValidationTests {

        @Test
        @DisplayName("should warn when line item currency doesn't match (exception is caught)")
        void lineItemCurrency_mismatch_warns() {
            // Given
            UUID packId = UUID.randomUUID();
            ProductPack pack = createProductPack("USD", 1000L, 100L, "price_123");
            
            LineItem lineItem = mock(LineItem.class);
            lenient().when(lineItem.getCurrency()).thenReturn("EUR"); // Mismatch!
            
            Price price = mock(Price.class);
            lenient().when(price.getId()).thenReturn("price_123");
            lenient().when(lineItem.getPrice()).thenReturn(price);
            
            LineItemCollection lineItems = mock(LineItemCollection.class);
            lenient().when(lineItems.getData()).thenReturn(List.of(lineItem));
            
            Session session = mock(Session.class);
            lenient().when(session.getCurrency()).thenReturn("USD");
            lenient().when(session.getId()).thenReturn("cs_test");
            lenient().when(session.getLineItems()).thenReturn(lineItems);
            lenient().when(session.getAmountTotal()).thenReturn(1000L);
            
            when(productPackRepository.findById(packId)).thenReturn(Optional.of(pack));

            // When - The currency mismatch is caught and only logged as warning
            CheckoutValidationService.CheckoutValidationResult result = 
                checkoutValidationService.validateAndResolvePack(session, packId);

            // Then - Still succeeds
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("should handle non-LineItem object gracefully")
        void extractLineItemCurrency_nonLineItemObject_handlesGracefully() {
            // Given
            UUID packId = UUID.randomUUID();
            ProductPack pack = createProductPack("USD", 1000L, 100L);
            
            // Create a mock that returns null for currency (simulating extraction failure)
            LineItem lineItem = mock(LineItem.class);
            lenient().when(lineItem.getCurrency()).thenReturn(null);
            
            LineItemCollection lineItems = mock(LineItemCollection.class);
            lenient().when(lineItems.getData()).thenReturn(List.of(lineItem));
            
            Session session = mock(Session.class);
            lenient().when(session.getCurrency()).thenReturn("USD");
            lenient().when(session.getId()).thenReturn("cs_test");
            lenient().when(session.getLineItems()).thenReturn(lineItems);
            lenient().when(session.getAmountTotal()).thenReturn(1000L);
            
            when(productPackRepository.findById(packId)).thenReturn(Optional.of(pack));

            // When
            CheckoutValidationService.CheckoutValidationResult result = 
                checkoutValidationService.validateAndResolvePack(session, packId);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("should handle empty line items list")
        void lineItemCurrency_emptyList_handlesGracefully() {
            // Given
            UUID packId = UUID.randomUUID();
            ProductPack pack = createProductPack("USD", 1000L, 100L);
            
            LineItemCollection lineItems = mock(LineItemCollection.class);
            lenient().when(lineItems.getData()).thenReturn(Collections.emptyList());
            
            Session session = mock(Session.class);
            lenient().when(session.getCurrency()).thenReturn("USD");
            lenient().when(session.getId()).thenReturn("cs_test");
            lenient().when(session.getLineItems()).thenReturn(lineItems);
            lenient().when(session.getAmountTotal()).thenReturn(1000L);
            
            when(productPackRepository.findById(packId)).thenReturn(Optional.of(pack));

            // When
            CheckoutValidationService.CheckoutValidationResult result = 
                checkoutValidationService.validateAndResolvePack(session, packId);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("should handle getCurrency() exception")
        void extractLineItemCurrency_exceptionThrown_returnsNull() {
            // Given
            UUID packId = UUID.randomUUID();
            ProductPack pack = createProductPack("USD", 1000L, 100L);
            
            LineItem lineItem = mock(LineItem.class);
            lenient().when(lineItem.getCurrency()).thenThrow(new RuntimeException("Currency error"));
            
            LineItemCollection lineItems = mock(LineItemCollection.class);
            lenient().when(lineItems.getData()).thenReturn(List.of(lineItem));
            
            Session session = mock(Session.class);
            lenient().when(session.getCurrency()).thenReturn("USD");
            lenient().when(session.getId()).thenReturn("cs_test");
            lenient().when(session.getLineItems()).thenReturn(lineItems);
            lenient().when(session.getAmountTotal()).thenReturn(1000L);
            
            when(productPackRepository.findById(packId)).thenReturn(Optional.of(pack));

            // When
            CheckoutValidationService.CheckoutValidationResult result = 
                checkoutValidationService.validateAndResolvePack(session, packId);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("should handle non-LineItem object in extractLineItemCurrency")
        void extractLineItemCurrency_nonLineItemObject_returnsNull() {
            // Given - Test the instanceof check in extractLineItemCurrency
            UUID packId = UUID.randomUUID();
            ProductPack pack = createProductPack("USD", 1000L, 100L);
            
            // Create a collection with a non-LineItem object
            LineItemCollection lineItems = mock(LineItemCollection.class);
            List<Object> nonLineItemList = new ArrayList<>();
            nonLineItemList.add(new HashMap<>()); // Not a LineItem!
            when(lineItems.getData()).thenReturn((List) nonLineItemList);
            
            Session session = mock(Session.class);
            lenient().when(session.getCurrency()).thenReturn("USD");
            lenient().when(session.getId()).thenReturn("cs_test");
            lenient().when(session.getLineItems()).thenReturn(lineItems);
            lenient().when(session.getAmountTotal()).thenReturn(1000L);
            
            when(productPackRepository.findById(packId)).thenReturn(Optional.of(pack));

            // When - The instanceof check will fail, returning null, which is ignored
            CheckoutValidationService.CheckoutValidationResult result = 
                checkoutValidationService.validateAndResolvePack(session, packId);

            // Then - Should still succeed (currency validation is lenient)
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("Resolve Primary Pack Tests")
    class ResolvePrimaryPackTests {

        @Test
        @DisplayName("should handle empty line items when no metadata")
        void resolvePrimaryPack_emptyLineItems_throwsException() {
            // Given
            Session session = mock(Session.class);
            when(session.getId()).thenReturn("cs_test");
            when(session.getLineItems()).thenReturn(null);

            // When & Then
            assertThatThrownBy(() -> checkoutValidationService.validateAndResolvePack(session, null))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("Unable to resolve pack from session");
        }

        @Test
        @DisplayName("should handle non-LineItem object in line items during pack resolution")
        void resolvePrimaryPack_nonLineItemObject_throwsException() {
            // Given - Test the instanceof check in extractPriceId
            Session session = mock(Session.class);
            when(session.getId()).thenReturn("cs_test");
            
            // Create a collection with a non-LineItem object (e.g., a String)
            LineItemCollection lineItems = mock(LineItemCollection.class);
            List<Object> nonLineItemList = new ArrayList<>();
            nonLineItemList.add("not a line item"); // This will fail the instanceof check
            when(lineItems.getData()).thenReturn((List) nonLineItemList);
            when(session.getLineItems()).thenReturn(lineItems);

            // When & Then - Should fail to resolve because instanceof returns false
            assertThatThrownBy(() -> checkoutValidationService.validateAndResolvePack(session, null))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("Unable to resolve pack from session");
        }

        @Test
        @DisplayName("should handle exception when resolving from line items")
        void resolvePrimaryPack_exceptionInLineItems_throwsException() {
            // Given
            Session session = mock(Session.class);
            when(session.getId()).thenReturn("cs_test");
            when(session.getLineItems()).thenThrow(new RuntimeException("Line items error"));

            // When & Then
            assertThatThrownBy(() -> checkoutValidationService.validateAndResolvePack(session, null))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("Unable to resolve pack from session");
        }

        @Test
        @DisplayName("should handle line item without price ID")
        void resolvePrimaryPack_noPriceId_throwsException() {
            // Given
            LineItem lineItem = mock(LineItem.class);
            when(lineItem.getPrice()).thenReturn(null);
            
            LineItemCollection lineItems = mock(LineItemCollection.class);
            when(lineItems.getData()).thenReturn(List.of(lineItem));
            
            Session session = mock(Session.class);
            when(session.getId()).thenReturn("cs_test");
            when(session.getLineItems()).thenReturn(lineItems);

            // When & Then
            assertThatThrownBy(() -> checkoutValidationService.validateAndResolvePack(session, null))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("Unable to resolve pack from session");
        }
    }

    @Nested
    @DisplayName("Exception Handling Tests")
    class ExceptionHandlingTests {

        @Test
        @DisplayName("should handle exception when processing line items")
        void processLineItems_exception_continuesGracefully() {
            // Given
            UUID packId = UUID.randomUUID();
            ProductPack pack = createProductPack("USD", 1000L, 100L);
            
            Session session = mock(Session.class);
            when(session.getCurrency()).thenReturn("USD");
            when(session.getId()).thenReturn("cs_test");
            when(session.getLineItems()).thenThrow(new RuntimeException("Line items error"));
            when(session.getAmountTotal()).thenReturn(1000L);
            
            when(productPackRepository.findById(packId)).thenReturn(Optional.of(pack));

            // When
            CheckoutValidationService.CheckoutValidationResult result = 
                checkoutValidationService.validateAndResolvePack(session, packId);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.hasMultipleLineItems()).isFalse();
        }

        @Test
        @DisplayName("should handle exception in amount validation")
        void validateAmount_exception_continuesGracefully() {
            // Given
            UUID packId = UUID.randomUUID();
            ProductPack pack = createProductPack("USD", 1000L, 100L);
            
            Session session = mock(Session.class);
            when(session.getCurrency()).thenReturn("USD");
            when(session.getId()).thenReturn("cs_test");
            when(session.getLineItems()).thenReturn(null);
            when(session.getAmountTotal()).thenThrow(new RuntimeException("Amount error"));
            
            when(productPackRepository.findById(packId)).thenReturn(Optional.of(pack));

            // When
            CheckoutValidationService.CheckoutValidationResult result = 
                checkoutValidationService.validateAndResolvePack(session, packId);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("should handle exception in line item currency validation")
        void validateLineItemCurrency_exception_continuesGracefully() {
            // Given
            UUID packId = UUID.randomUUID();
            ProductPack pack = createProductPack("USD", 1000L, 100L);
            
            LineItemCollection lineItems = mock(LineItemCollection.class);
            when(lineItems.getData()).thenThrow(new RuntimeException("Data error"));
            
            Session session = mock(Session.class);
            when(session.getCurrency()).thenReturn("USD");
            when(session.getId()).thenReturn("cs_test");
            when(session.getLineItems()).thenReturn(lineItems);
            when(session.getAmountTotal()).thenReturn(1000L);
            
            when(productPackRepository.findById(packId)).thenReturn(Optional.of(pack));

            // When
            CheckoutValidationService.CheckoutValidationResult result = 
                checkoutValidationService.validateAndResolvePack(session, packId);

            // Then
            assertThat(result).isNotNull();
        }
    }

    // Helper methods
    private ProductPack createProductPack(String currency, Long priceCents, Long tokens) {
        return createProductPack(currency, priceCents, tokens, "price_" + UUID.randomUUID());
    }

    private ProductPack createProductPack(String currency, Long priceCents, Long tokens, String priceId) {
        ProductPack pack = new ProductPack();
        pack.setId(UUID.randomUUID());
        pack.setCurrency(currency);
        pack.setPriceCents(priceCents);
        pack.setTokens(tokens);
        pack.setStripePriceId(priceId);
        pack.setName("Test Pack");
        return pack;
    }

    private Session createMockSession(String currency, Long amountTotal, String sessionId) {
        Session session = mock(Session.class);
        lenient().when(session.getCurrency()).thenReturn(currency);
        lenient().when(session.getAmountTotal()).thenReturn(amountTotal);
        lenient().when(session.getId()).thenReturn(sessionId != null ? sessionId : "cs_test_" + UUID.randomUUID());
        lenient().when(session.getLineItems()).thenReturn(null);
        return session;
    }

    private Session createMockSessionWithLineItems(String currency, Long amountTotal, List<String> priceIds) {
        Session session = mock(Session.class);
        lenient().when(session.getCurrency()).thenReturn(currency);
        lenient().when(session.getAmountTotal()).thenReturn(amountTotal);
        lenient().when(session.getId()).thenReturn("cs_test_" + UUID.randomUUID());
        
        // Create mock line items
        List<LineItem> lineItemsList = new ArrayList<>();
        for (String priceId : priceIds) {
            LineItem lineItem = mock(LineItem.class);
            Price price = mock(Price.class);
            lenient().when(price.getId()).thenReturn(priceId);
            lenient().when(lineItem.getPrice()).thenReturn(price);
            lenient().when(lineItem.getCurrency()).thenReturn(currency);
            lineItemsList.add(lineItem);
        }
        
        LineItemCollection lineItems = mock(LineItemCollection.class);
        lenient().when(lineItems.getData()).thenReturn(lineItemsList);
        lenient().when(session.getLineItems()).thenReturn(lineItems);
        
        return session;
    }
}
