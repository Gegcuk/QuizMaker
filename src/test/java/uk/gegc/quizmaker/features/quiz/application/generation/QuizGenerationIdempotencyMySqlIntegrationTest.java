package uk.gegc.quizmaker.features.quiz.application.generation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.billing.application.ContentLengthPerQuestionTypeTariff;
import uk.gegc.quizmaker.features.billing.application.GenerationTariff;
import uk.gegc.quizmaker.features.billing.application.GenerationTariffService;
import uk.gegc.quizmaker.features.billing.domain.exception.IdempotencyConflictException;
import uk.gegc.quizmaker.features.quiz.application.generation.impl.QuizGenerationIdempotencyServiceImpl;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationOperationType;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationOperationRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("db-serial")
@DataJpaTest
@ActiveProfiles("test-mysql")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false"
})
@Import({QuizGenerationIdempotencyServiceImpl.class, QuizGenerationIdempotencyMySqlIntegrationTest.ClockTestConfiguration.class})
@DisplayName("Quiz generation idempotency MySQL integration")
class QuizGenerationIdempotencyMySqlIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockTestConfiguration {
        @Bean
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-08-03T10:00:00Z"));
        }

        @Bean
        @Primary
        Clock clock(MutableClock mutableClock) {
            return mutableClock;
        }

        @Bean
        MutableGenerationTariffService mutableGenerationTariffService() {
            return new MutableGenerationTariffService();
        }

        @Bean
        @Primary
        GenerationTariffService generationTariffService(MutableGenerationTariffService tariffService) {
            return tariffService;
        }
    }

    static final class MutableGenerationTariffService implements GenerationTariffService {
        private final AtomicReference<GenerationTariff> current = new AtomicReference<>();

        MutableGenerationTariffService() {
            reset();
        }

        void reset() {
            current.set(new ContentLengthPerQuestionTypeTariff(
                    "v1-content-length", 3L, new BigDecimal("0.35")));
        }

        void use(GenerationTariff tariff) {
            current.set(tariff);
        }

        @Override
        public GenerationTariff currentTariff() {
            return current.get();
        }
    }

    static final class MutableClock extends Clock {
        private final Instant initialInstant;
        private Instant instant;

        MutableClock(Instant initialInstant) {
            this.initialInstant = initialInstant;
            this.instant = initialInstant;
        }

        void reset() {
            instant = initialInstant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @Autowired
    private QuizGenerationIdempotencyService idempotencyService;

    @Autowired
    private QuizGenerationOperationRepository operationRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MutableClock clock;

    @Autowired
    private MutableGenerationTariffService tariffService;

    @BeforeEach
    void clearOperationsBeforeEach() {
        clock.reset();
        tariffService.reset();
        clearOperations();
    }

    @AfterEach
    void clearOperationsAfterEach() {
        clearOperations();
    }

    private void clearOperations() {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactionTemplate.executeWithoutResult(status -> operationRepository.deleteAllInBatch());
    }

    @Test
    @DisplayName("Concurrent retries with the same key create one durable operation")
    void concurrentSameKeyCreatesOneOperation() throws Exception {
        UUID userId = UUID.randomUUID();
        GenerationRequestFingerprint fingerprint = new GenerationRequestFingerprint("a".repeat(64), "v1");
        CyclicBarrier barrier = new CyclicBarrier(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<UUID>> operationIds = List.of(
                    executor.submit(() -> claimAfterBarrier(barrier, userId, "same-key", fingerprint)),
                    executor.submit(() -> claimAfterBarrier(barrier, userId, "same-key", fingerprint))
            );

            assertThat(operationIds.get(0).get()).isEqualTo(operationIds.get(1).get());
        } finally {
            executor.shutdownNow();
        }

        assertThat(operationRepository.findByUserIdAndOperationTypeAndIdempotencyKey(
                userId, GenerationOperationType.DOCUMENT, "same-key"))
                .get()
                .satisfies(operation -> {
                    assertThat(operation.getBillingTariffVersion()).isEqualTo("v1-content-length");
                    assertThat(operation.getBillingBaseTokens()).isEqualTo(3L);
                    assertThat(operation.getBillingTokensPerThousandCharacters()).isEqualByComparingTo("0.35");
                });
    }

    @Test
    @DisplayName("Rejects a material hash change without creating another operation")
    void changedRequestHashIsRejected() {
        UUID userId = UUID.randomUUID();
        idempotencyService.claim(userId, GenerationOperationType.DOCUMENT, "same-key",
                new GenerationRequestFingerprint("a".repeat(64), "v1"), false);

        assertThatThrownBy(() -> idempotencyService.claim(userId, GenerationOperationType.DOCUMENT, "same-key",
                new GenerationRequestFingerprint("b".repeat(64), "v1"), false))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(operationRepository.findByUserIdAndOperationTypeAndIdempotencyKey(
                userId, GenerationOperationType.DOCUMENT, "same-key")).isPresent();
    }

    @Test
    @DisplayName("Allows equal commands with different keys to remain distinct operations")
    void differentKeysCreateDistinctOperations() {
        UUID userId = UUID.randomUUID();
        GenerationRequestFingerprint fingerprint = new GenerationRequestFingerprint("a".repeat(64), "v1");

        UUID first = idempotencyService.claim(userId, GenerationOperationType.DOCUMENT, "first-key", fingerprint, false).getId();
        UUID second = idempotencyService.claim(userId, GenerationOperationType.DOCUMENT, "second-key", fingerprint, false).getId();

        assertThat(first).isNotEqualTo(second);
        assertThat(operationRepository.findByUserIdAndOperationTypeAndIdempotencyKey(
                userId, GenerationOperationType.DOCUMENT, "first-key")).isPresent();
        assertThat(operationRepository.findByUserIdAndOperationTypeAndIdempotencyKey(
                userId, GenerationOperationType.DOCUMENT, "second-key")).isPresent();
    }

    @Test
    @DisplayName("Rejects a canonicalization-version change for a reused key")
    void changedCanonicalizationVersionIsRejected() {
        UUID userId = UUID.randomUUID();
        idempotencyService.claim(userId, GenerationOperationType.DOCUMENT, "same-key",
                new GenerationRequestFingerprint("a".repeat(64), "v1"), false);

        assertThatThrownBy(() -> idempotencyService.claim(userId, GenerationOperationType.DOCUMENT, "same-key",
                new GenerationRequestFingerprint("a".repeat(64), "v2"), false))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    @DisplayName("Exact retry retains the claimed tariff while a new key receives the new tariff")
    void exactRetryRetainsClaimedTariffAcrossConfigurationChange() {
        UUID userId = UUID.randomUUID();
        GenerationRequestFingerprint fingerprint = new GenerationRequestFingerprint(
                "a".repeat(64), "v2-source-digest");

        UUID originalId = idempotencyService.claim(
                userId, GenerationOperationType.TEXT, "same-key", fingerprint, false).getId();
        tariffService.use(new ContentLengthPerQuestionTypeTariff(
                "v2-content-length", 7L, new BigDecimal("0.50")));

        var replay = idempotencyService.claim(
                userId, GenerationOperationType.TEXT, "same-key", fingerprint, false);
        var newCommand = idempotencyService.claim(
                userId, GenerationOperationType.TEXT, "new-key", fingerprint, false);

        assertThat(replay.getId()).isEqualTo(originalId);
        assertThat(replay.getBillingTariffVersion()).isEqualTo("v1-content-length");
        assertThat(replay.getBillingBaseTokens()).isEqualTo(3L);
        assertThat(newCommand.getBillingTariffVersion()).isEqualTo("v2-content-length");
        assertThat(newCommand.getBillingBaseTokens()).isEqualTo(7L);
    }

    @Test
    @DisplayName("A started legacy metadata operation remains replayable after source-digest canonicalization")
    void startedLegacyOperationRemainsReplayableAfterCanonicalizationUpgrade() {
        UUID userId = UUID.randomUUID();
        var legacyOperation = idempotencyService.claim(
                userId,
                GenerationOperationType.UPLOAD,
                "legacy-key",
                new GenerationRequestFingerprint("a".repeat(64), "v1"),
                false
        );
        linkStartedOperationInCommittedTransaction(
                legacyOperation.getId(), UUID.randomUUID(), UUID.randomUUID());

        var replay = idempotencyService.claim(
                userId,
                GenerationOperationType.UPLOAD,
                "legacy-key",
                new GenerationRequestFingerprint("b".repeat(64), "v2-source-digest"),
                false
        );

        assertThat(replay.getId()).isEqualTo(legacyOperation.getId());
        assertThat(replay.hasStartedJob()).isTrue();
    }

    @Test
    @DisplayName("Concurrent changed commands with one key produce one operation and one conflict")
    void concurrentChangedCommandsProduceOneOperationAndOneConflict() throws Exception {
        UUID userId = UUID.randomUUID();
        CyclicBarrier barrier = new CyclicBarrier(2);
        GenerationRequestFingerprint first = new GenerationRequestFingerprint(
                "a".repeat(64), "v2-source-digest");
        GenerationRequestFingerprint changed = new GenerationRequestFingerprint(
                "b".repeat(64), "v2-source-digest");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<ClaimOutcome> outcomes;
        try {
            List<Future<ClaimOutcome>> futures = List.of(
                    executor.submit(() -> claimOutcomeAfterBarrier(barrier, userId, first)),
                    executor.submit(() -> claimOutcomeAfterBarrier(barrier, userId, changed))
            );
            outcomes = List.of(futures.get(0).get(), futures.get(1).get());
        } finally {
            executor.shutdownNow();
        }

        assertThat(outcomes).filteredOn(outcome -> outcome.operationId() != null).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> outcome.failure() instanceof IdempotencyConflictException)
                .hasSize(1);
        assertThat(operationRepository.findByUserIdAndOperationTypeAndIdempotencyKey(
                userId, GenerationOperationType.UPLOAD, "same-key"))
                .get()
                .satisfies(operation -> {
                    assertThat(operation.hasStartedJob()).isFalse();
                    assertThat(operation.getReservationId()).isNull();
                });
    }

    @Test
    @DisplayName("Purges an operation only after the documented replay retention window")
    void expiredOperationIsPurgedAfterRetentionWindow() {
        UUID userId = UUID.randomUUID();
        idempotencyService.claim(userId, GenerationOperationType.DOCUMENT, "same-key",
                new GenerationRequestFingerprint("a".repeat(64), "v1"), false);

        assertThat(idempotencyService.purgeExpiredOperations()).isZero();

        clock.advance(Duration.ofDays(31));

        assertThat(idempotencyService.purgeExpiredOperations()).isEqualTo(1);
        assertThat(operationRepository.findByUserIdAndOperationTypeAndIdempotencyKey(
                userId, GenerationOperationType.DOCUMENT, "same-key")).isEmpty();
    }

    private UUID claimAfterBarrier(
            CyclicBarrier barrier,
            UUID userId,
            String key,
            GenerationRequestFingerprint fingerprint
    ) throws Exception {
        barrier.await();
        return idempotencyService.claim(userId, GenerationOperationType.DOCUMENT, key, fingerprint, false).getId();
    }

    private ClaimOutcome claimOutcomeAfterBarrier(
            CyclicBarrier barrier,
            UUID userId,
            GenerationRequestFingerprint fingerprint
    ) throws Exception {
        barrier.await();
        try {
            UUID operationId = idempotencyService.claim(
                    userId, GenerationOperationType.UPLOAD, "same-key", fingerprint, false).getId();
            return new ClaimOutcome(operationId, null);
        } catch (RuntimeException exception) {
            return new ClaimOutcome(null, exception);
        }
    }

    private void linkStartedOperationInCommittedTransaction(
            UUID operationId,
            UUID jobId,
            UUID reservationId
    ) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactionTemplate.executeWithoutResult(status -> {
            var operation = operationRepository.findById(operationId).orElseThrow();
            operation.linkStartedGeneration(jobId, reservationId, 60, java.time.LocalDateTime.now(clock));
            operationRepository.save(operation);
        });
    }

    private record ClaimOutcome(UUID operationId, RuntimeException failure) {
    }
}
