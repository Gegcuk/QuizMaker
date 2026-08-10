package uk.gegc.quizmaker.features.billing.application.impl;

import com.stripe.model.LineItem;
import com.stripe.model.LineItemCollection;
import com.stripe.model.Price;
import com.stripe.model.checkout.Session;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import uk.gegc.quizmaker.features.billing.application.CheckoutSessionSettlementCommand;
import uk.gegc.quizmaker.features.billing.application.CheckoutSessionSettlementService;
import uk.gegc.quizmaker.features.billing.application.CheckoutValidationService;
import uk.gegc.quizmaker.features.billing.domain.exception.InvalidCheckoutSessionException;
import uk.gegc.quizmaker.features.billing.domain.model.Balance;
import uk.gegc.quizmaker.features.billing.domain.model.Payment;
import uk.gegc.quizmaker.features.billing.domain.model.PaymentStatus;
import uk.gegc.quizmaker.features.billing.domain.model.ProductPack;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransaction;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransactionType;
import uk.gegc.quizmaker.features.billing.infra.repository.BalanceRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.CheckoutSessionSettlementRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.PaymentRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.ProcessedStripeEventRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.ProductPackRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.TokenTransactionRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("db-serial")
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "quizmaker.features.billing=true"
})
@DisplayName("Checkout session settlement integration")
class CheckoutSessionSettlementServiceIntegrationTest {

    private static final long TOKENS = 500L;
    private static final long AMOUNT_CENTS = 2_500L;
    private static final String ORIGINAL_PRICE_ID = "price_checkout_snapshot";

    @Autowired
    private CheckoutSessionSettlementService settlementService;

    @Autowired
    private CheckoutValidationService checkoutValidationService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductPackRepository productPackRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private CheckoutSessionSettlementRepository settlementRepository;

    @Autowired
    private ProcessedStripeEventRepository processedStripeEventRepository;

    @Autowired
    private TokenTransactionRepository tokenTransactionRepository;

    @Autowired
    private BalanceRepository balanceRepository;

    private UUID userId;
    private UUID packId;
    private String sessionId;
    private final List<String> processedEventIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        User user = new User();
        user.setUsername("checkout-" + suffix.substring(0, 8));
        user.setEmail("checkout-settlement-" + suffix + "@example.test");
        user.setHashedPassword("not-used-by-this-test");
        user.setActive(true);
        user.setDeleted(false);
        user.setEmailVerified(true);
        userId = userRepository.saveAndFlush(user).getId();

        ProductPack pack = new ProductPack();
        pack.setName("Settlement Test Pack " + suffix);
        pack.setDescription("Used only by checkout settlement integration tests");
        pack.setTokens(TOKENS);
        pack.setPriceCents(AMOUNT_CENTS);
        pack.setCurrency("usd");
        pack.setStripePriceId(ORIGINAL_PRICE_ID + "_" + suffix);
        pack.setActive(true);
        packId = productPackRepository.saveAndFlush(pack).getId();

        sessionId = "cs_settlement_" + UUID.randomUUID();
        Payment payment = new Payment();
        payment.setUserId(userId);
        payment.setPackId(packId);
        payment.setStatus(PaymentStatus.PENDING);
        payment.setStripeSessionId(sessionId);
        payment.setAmountCents(AMOUNT_CENTS);
        payment.setCurrency("usd");
        payment.setCreditedTokens(TOKENS);
        payment.setStripePriceIdSnapshot(pack.getStripePriceId());
        payment.setRefundedAmountCents(0L);
        paymentRepository.saveAndFlush(payment);
    }

    @AfterEach
    void tearDown() {
        tokenTransactionRepository.findByIdempotencyKey(ledgerKey()).ifPresent(tokenTransactionRepository::delete);
        balanceRepository.findByUserId(userId).ifPresent(balanceRepository::delete);
        processedEventIds.forEach(processedStripeEventRepository::deleteById);
        settlementRepository.deleteById(sessionId);
        paymentRepository.findByStripeSessionId(sessionId).ifPresent(paymentRepository::delete);
        productPackRepository.findById(packId).ifPresent(productPackRepository::delete);
        userRepository.findById(userId).ifPresent(userRepository::delete);
    }

    @Test
    @DisplayName("catalog drift plus concurrent paid events preserves original terms and credits exactly once")
    void concurrentPaidEventsCreditExactlyOnce() throws Exception {
        CheckoutValidationService.CheckoutValidationResult originalPurchase = validateAfterCatalogDrift();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        String completedEventId = "evt_completed_" + UUID.randomUUID();
        String asyncSucceededEventId = "evt_async_succeeded_" + UUID.randomUUID();
        processedEventIds.add(completedEventId);
        processedEventIds.add(asyncSucceededEventId);

        try {
            Future<CheckoutSessionSettlementService.SettlementResult> completed = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return settlementService.settle(paidCommand(completedEventId, originalPurchase));
            });
            Future<CheckoutSessionSettlementService.SettlementResult> asyncSucceeded = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return settlementService.settle(paidCommand(asyncSucceededEventId, originalPurchase));
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<CheckoutSessionSettlementService.SettlementResult> results = List.of(
                    completed.get(10, TimeUnit.SECONDS),
                    asyncSucceeded.get(10, TimeUnit.SECONDS)
            );

            assertThat(results).containsExactlyInAnyOrder(
                    CheckoutSessionSettlementService.SettlementResult.CREDITED,
                    CheckoutSessionSettlementService.SettlementResult.ALREADY_SETTLED
            );
        } finally {
            executor.shutdownNow();
        }

        assertSettledOnce();
        assertThat(processedStripeEventRepository.existsByEventId(completedEventId)).isTrue();
        assertThat(processedStripeEventRepository.existsByEventId(asyncSucceededEventId)).isTrue();
    }

    @Test
    @DisplayName("mismatched Stripe facts leave payment, settlement, ledger, and balance unchanged")
    void mismatchedStripeFactsDoNotMutateBillingState() {
        Session stripeSession = stripeSession();
        stripeSession.getLineItems().getData().get(0).setAmountTotal(AMOUNT_CENTS - 1L);
        Counter existingCounter = meterRegistry.find("billing.checkout.validation.failures")
                .tag("reason", "amount_mismatch")
                .counter();
        double countBefore = existingCounter == null ? 0.0d : existingCounter.count();

        assertThatThrownBy(() -> checkoutValidationService.validateAndResolvePack(stripeSession, packId))
                .isInstanceOf(InvalidCheckoutSessionException.class)
                .hasMessageContaining("amount");

        Payment payment = paymentRepository.findByStripeSessionId(sessionId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(settlementRepository.findById(sessionId)).isEmpty();
        assertThat(tokenTransactionRepository.findByIdempotencyKey(ledgerKey())).isEmpty();
        assertThat(balanceRepository.findByUserId(userId)).isEmpty();
        assertThat(meterRegistry.get("billing.checkout.validation.failures")
                .tag("reason", "amount_mismatch")
                .counter()
                .count()).isEqualTo(countBefore + 1.0d);
    }

    @Test
    @DisplayName("an unpaid completion followed by paid async success credits once and a later failure cannot overwrite success")
    void unpaidCompletionThenAsyncSuccessCreditsOnceAndLaterFailureDoesNotChangeIt() {
        String completionEventId = "evt_completed_" + UUID.randomUUID();
        String asyncSuccessEventId = "evt_async_succeeded_" + UUID.randomUUID();
        String lateFailureEventId = "evt_async_failed_" + UUID.randomUUID();
        processedEventIds.addAll(List.of(completionEventId, asyncSuccessEventId, lateFailureEventId));

        CheckoutSessionSettlementService.SettlementResult completion = settlementService.settle(
                command(completionEventId, false,
                        CheckoutSessionSettlementCommand.UnpaidDisposition.KEEP_PENDING)
        );
        CheckoutSessionSettlementService.SettlementResult asyncSuccess = settlementService.settle(
                paidCommand(asyncSuccessEventId)
        );
        CheckoutSessionSettlementService.SettlementResult lateFailure = settlementService.settle(
                command(lateFailureEventId, false,
                        CheckoutSessionSettlementCommand.UnpaidDisposition.MARK_FAILED)
        );

        assertThat(completion).isEqualTo(CheckoutSessionSettlementService.SettlementResult.PENDING);
        assertThat(asyncSuccess).isEqualTo(CheckoutSessionSettlementService.SettlementResult.CREDITED);
        assertThat(lateFailure).isEqualTo(CheckoutSessionSettlementService.SettlementResult.ALREADY_SETTLED);
        assertSettledOnce();
        assertThat(processedStripeEventRepository.existsByEventId(completionEventId)).isTrue();
        assertThat(processedStripeEventRepository.existsByEventId(asyncSuccessEventId)).isTrue();
        assertThat(processedStripeEventRepository.existsByEventId(lateFailureEventId)).isTrue();
    }

    private void assertSettledOnce() {
        Payment payment = paymentRepository.findByStripeSessionId(sessionId).orElseThrow();
        TokenTransaction purchase = tokenTransactionRepository.findByIdempotencyKey(ledgerKey()).orElseThrow();
        Balance balance = balanceRepository.findByUserId(userId).orElseThrow();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(settlementRepository.findById(sessionId)).isPresent();
        assertThat(purchase.getType()).isEqualTo(TokenTransactionType.PURCHASE);
        assertThat(purchase.getAmountTokens()).isEqualTo(TOKENS);
        assertThat(balance.getAvailableTokens()).isEqualTo(TOKENS);
        assertThat(tokenTransactionRepository.findByUserId(userId))
                .filteredOn(transaction -> ledgerKey().equals(transaction.getIdempotencyKey()))
                .hasSize(1);
    }

    private CheckoutSessionSettlementCommand paidCommand(String eventId) {
        return command(eventId, true, CheckoutSessionSettlementCommand.UnpaidDisposition.KEEP_PENDING);
    }

    private CheckoutSessionSettlementCommand paidCommand(
            String eventId,
            CheckoutValidationService.CheckoutValidationResult purchase
    ) {
        return new CheckoutSessionSettlementCommand(
                eventId,
                sessionId,
                "pi_" + sessionId,
                "cus_" + userId,
                userId,
                purchase.packId(),
                purchase.totalAmountCents(),
                purchase.currency(),
                purchase.totalTokens(),
                "{\"source\":\"catalog-drift-integration-test\"}",
                true,
                CheckoutSessionSettlementCommand.UnpaidDisposition.KEEP_PENDING
        );
    }

    private CheckoutSessionSettlementCommand command(
            String eventId,
            boolean paid,
            CheckoutSessionSettlementCommand.UnpaidDisposition unpaidDisposition
    ) {
        return new CheckoutSessionSettlementCommand(
                eventId,
                sessionId,
                "pi_" + sessionId,
                "cus_" + userId,
                userId,
                packId,
                AMOUNT_CENTS,
                "USD",
                TOKENS,
                "{\"source\":\"integration-test\"}",
                paid,
                unpaidDisposition
        );
    }

    private String ledgerKey() {
        return "checkout-session:" + sessionId;
    }

    private CheckoutValidationService.CheckoutValidationResult validateAfterCatalogDrift() {
        Payment payment = paymentRepository.findByStripeSessionId(sessionId).orElseThrow();
        String originalPriceId = payment.getStripePriceIdSnapshot();

        ProductPack currentPack = productPackRepository.findById(packId).orElseThrow();
        currentPack.setActive(false);
        currentPack.setStripePriceId("price_repriced_" + UUID.randomUUID());
        currentPack.setPriceCents(9_999L);
        currentPack.setCurrency("gbp");
        currentPack.setTokens(42L);
        productPackRepository.saveAndFlush(currentPack);

        CheckoutValidationService.CheckoutValidationResult purchase =
                checkoutValidationService.validateAndResolvePack(stripeSession(), packId);
        assertThat(purchase.stripePriceId()).isEqualTo(originalPriceId);
        assertThat(purchase.totalAmountCents()).isEqualTo(AMOUNT_CENTS);
        assertThat(purchase.currency()).isEqualTo("usd");
        assertThat(purchase.totalTokens()).isEqualTo(TOKENS);
        return purchase;
    }

    private Session stripeSession() {
        Payment payment = paymentRepository.findByStripeSessionId(sessionId).orElseThrow();
        Price price = new Price();
        price.setId(payment.getStripePriceIdSnapshot());

        LineItem lineItem = new LineItem();
        lineItem.setPrice(price);
        lineItem.setQuantity(1L);
        lineItem.setAmountTotal(AMOUNT_CENTS);
        lineItem.setCurrency("usd");

        LineItemCollection lineItems = new LineItemCollection();
        lineItems.setData(List.of(lineItem));

        Session session = new Session();
        session.setId(sessionId);
        session.setClientReferenceId(userId.toString());
        session.setMetadata(Map.of(
                "userId", userId.toString(),
                "packId", packId.toString(),
                "priceId", payment.getStripePriceIdSnapshot()
        ));
        session.setAmountTotal(AMOUNT_CENTS);
        session.setCurrency("usd");
        session.setLineItems(lineItems);
        return session;
    }
}
