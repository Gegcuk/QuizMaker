package uk.gegc.quizmaker.features.billing.application.impl;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.billing.application.CheckoutValidationFailureReason;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Billing metrics")
class BillingMetricsServiceImplTest {

    @Test
    @DisplayName("records checkout rejections with enum-bounded reason tags")
    void recordsCheckoutValidationFailuresWithBoundedReasons() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BillingMetricsServiceImpl service = new BillingMetricsServiceImpl(registry);

        service.recordCheckoutValidationFailure(CheckoutValidationFailureReason.PRICE_MISMATCH);
        service.recordCheckoutValidationFailure(CheckoutValidationFailureReason.PRICE_MISMATCH);
        service.recordCheckoutValidationFailure(CheckoutValidationFailureReason.USER_MISMATCH);

        assertThat(registry.find("billing.checkout.validation.failures")
                .tag("reason", "price_mismatch")
                .counter())
                .isNotNull()
                .extracting(counter -> counter.count())
                .isEqualTo(2.0d);
        assertThat(registry.find("billing.checkout.validation.failures")
                .tag("reason", "user_mismatch")
                .counter())
                .isNotNull()
                .extracting(counter -> counter.count())
                .isEqualTo(1.0d);
        assertThat(registry.getMeters())
                .filteredOn(meter -> meter.getId().getName().equals("billing.checkout.validation.failures"))
                .hasSize(2);
    }
}
