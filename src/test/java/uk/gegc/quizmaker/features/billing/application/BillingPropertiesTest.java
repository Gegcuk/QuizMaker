package uk.gegc.quizmaker.features.billing.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for BillingProperties configuration binding.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
    "billing.reservation-sweeper-ms=30000",
    "billing.token-to-llm-ratio=2000",
    "billing.safety-factor=1.5",
    "billing.reservation-ttl-minutes=180",
    "billing.currency=eur",
    "billing.strict-amount-validation=false",
    "billing.allow-negative-balance=false",
    "billing.allow-email-fallback-for-customer-ownership=true"
})
class BillingPropertiesTest {

    @Autowired
    private BillingProperties billingProperties;

    @Test
    @DisplayName("Should bind reservation sweeper interval from properties")
    void shouldBindReservationSweeperIntervalFromProperties() {
        // When & Then
        assertThat(billingProperties.getReservationSweeperMs()).isEqualTo(30000L);
    }

    @Test
    @DisplayName("Should bind token to LLM ratio from properties")
    void shouldBindTokenToLlmRatioFromProperties() {
        // When & Then
        assertThat(billingProperties.getTokenToLlmRatio()).isEqualTo(2000L);
    }

    @Test
    @DisplayName("Should bind safety factor from properties")
    void shouldBindSafetyFactorFromProperties() {
        // When & Then
        assertThat(billingProperties.getSafetyFactor()).isEqualTo(1.5);
    }

    @Test
    @DisplayName("Should bind reservation TTL minutes from properties")
    void shouldBindReservationTtlMinutesFromProperties() {
        // When & Then
        assertThat(billingProperties.getReservationTtlMinutes()).isEqualTo(180);
    }

    @Test
    @DisplayName("Should bind currency from properties")
    void shouldBindCurrencyFromProperties() {
        // When & Then
        assertThat(billingProperties.getCurrency()).isEqualTo("eur");
    }

    @Test
    @DisplayName("Should bind strict amount validation from properties")
    void shouldBindStrictAmountValidationFromProperties() {
        // When & Then
        assertThat(billingProperties.isStrictAmountValidation()).isFalse();
    }

    @Test
    @DisplayName("Should bind allow negative balance from properties")
    void shouldBindAllowNegativeBalanceFromProperties() {
        // When & Then
        assertThat(billingProperties.isAllowNegativeBalance()).isFalse();
    }

    @Test
    @DisplayName("Should bind allow email fallback for customer ownership from properties")
    void shouldBindAllowEmailFallbackForCustomerOwnershipFromProperties() {
        // When & Then
        assertThat(billingProperties.isAllowEmailFallbackForCustomerOwnership()).isTrue();
    }
}
