package uk.gegc.quizmaker.features.billing.application.impl;

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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Autowired
    private CheckoutSessionSettlementService settlementService;

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
        user.setUsername("checkout-settlement-" + suffix);
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
        pack.setStripePriceId("price_settlement_" + suffix);
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
    @DisplayName("distinct paid Checkout Session events concurrently create one settlement, purchase ledger entry, and balance credit")
    void concurrentPaidEventsCreditExactlyOnce() throws Exception {
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
                return settlementService.settle(paidCommand(completedEventId));
            });
            Future<CheckoutSessionSettlementService.SettlementResult> asyncSucceeded = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return settlementService.settle(paidCommand(asyncSucceededEventId));
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
}
