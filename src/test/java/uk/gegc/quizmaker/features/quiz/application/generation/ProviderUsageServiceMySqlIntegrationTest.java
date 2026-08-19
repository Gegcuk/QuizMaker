package uk.gegc.quizmaker.features.quiz.application.generation;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.quiz.application.generation.impl.ProviderUsageServiceImpl;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.ProviderUsageRecordState;
import uk.gegc.quizmaker.features.quiz.domain.model.ProviderUsageState;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationProviderUsageRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("db-serial")
@DataJpaTest
@ActiveProfiles("test-mysql")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=update"
})
@Import({ProviderUsageServiceImpl.class, ProviderUsageServiceMySqlIntegrationTest.Configuration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Provider usage service MySQL integration")
class ProviderUsageServiceMySqlIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-08-10T12:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired private ProviderUsageService providerUsageService;
    @Autowired private QuizGenerationProviderUsageRepository usageRepository;
    @Autowired private QuizGenerationJobRepository jobRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MeterRegistry meterRegistry;

    private UUID jobId;
    private UUID userId;

    @AfterEach
    void cleanUp() {
        usageRepository.deleteAllInBatch();
        if (jobId != null) {
            jobRepository.findById(jobId).ifPresent(jobRepository::delete);
        }
        if (userId != null) {
            userRepository.findById(userId).ifPresent(userRepository::delete);
        }
    }

    @Test
    @DisplayName("Concurrent distinct attempts preserve every reported token")
    void concurrentDistinctAttemptsPreserveEveryReportedToken() throws Exception {
        QuizGenerationJob job = persistJob();
        UUID firstAttempt = UUID.randomUUID();
        UUID secondAttempt = UUID.randomUUID();
        providerUsageService.recordStarted(job.getId(), firstAttempt);
        providerUsageService.recordStarted(job.getId(), secondAttempt);
        CyclicBarrier barrier = new CyclicBarrier(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<ProviderUsageRecordResult>> futures = List.of(
                    executor.submit(() -> recordAfterBarrier(barrier, job.getId(), firstAttempt, 100L)),
                    executor.submit(() -> recordAfterBarrier(barrier, job.getId(), secondAttempt, 250L))
            );

            assertThat(List.of(futures.get(0).get(), futures.get(1).get()))
                    .containsExactly(ProviderUsageRecordResult.RECORDED, ProviderUsageRecordResult.RECORDED);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        QuizGenerationJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getProviderLlmTokens()).isEqualTo(350L);
        assertThat(reloaded.getProviderUsageState()).isEqualTo(ProviderUsageState.COMPLETE);
        assertThat(usageRepository.countByJobId(job.getId())).isEqualTo(2L);
    }

    @Test
    @DisplayName("Concurrent conflicting terminal facts retain one outcome")
    void concurrentConflictingDeliveryRetainsOneOutcome() throws Exception {
        QuizGenerationJob job = persistJob();
        UUID providerAttemptId = UUID.randomUUID();
        providerUsageService.recordStarted(job.getId(), providerAttemptId);
        CyclicBarrier barrier = new CyclicBarrier(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<ProviderUsageRecordResult>> futures = List.of(
                    executor.submit(() -> recordAfterBarrier(barrier, job.getId(), providerAttemptId, 125L)),
                    executor.submit(() -> {
                        barrier.await();
                        return providerUsageService.recordMissing(job.getId(), providerAttemptId);
                    })
            );

            assertThatThrownBy(() -> List.of(futures.get(0).get(), futures.get(1).get()))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        QuizGenerationJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getProviderLlmTokens()).isIn(null, 125L);
        assertThat(usageRepository.countByJobId(job.getId())).isEqualTo(1L);
        assertThat(usageRepository.findByJobIdAndProviderAttemptId(job.getId(), providerAttemptId).orElseThrow()
                .getRecordState()).isIn(ProviderUsageRecordState.REPORTED, ProviderUsageRecordState.MISSING);
    }

    @Test
    @DisplayName("Missing usage is durable and preserves legacy review")
    void missingUsageIsDurableAndConflictingRetryIsRejected() {
        QuizGenerationJob job = persistJob();
        UUID providerAttemptId = UUID.randomUUID();
        job.setProviderUsageState(ProviderUsageState.LEGACY_REVIEW);
        jobRepository.saveAndFlush(job);

        providerUsageService.recordStarted(job.getId(), providerAttemptId);
        assertThat(providerUsageService.recordMissing(job.getId(), providerAttemptId))
                .isEqualTo(ProviderUsageRecordResult.RECORDED);
        assertThat(providerUsageService.recordMissing(job.getId(), providerAttemptId))
                .isEqualTo(ProviderUsageRecordResult.DUPLICATE);
        assertThatThrownBy(() -> providerUsageService.recordReported(job.getId(), providerAttemptId, 99L))
                .isInstanceOf(IllegalStateException.class);

        QuizGenerationJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getProviderLlmTokens()).isNull();
        assertThat(reloaded.getProviderUsageState()).isEqualTo(ProviderUsageState.LEGACY_REVIEW);
        assertThat(usageRepository.findByJobIdAndProviderAttemptId(job.getId(), providerAttemptId))
                .get()
                .satisfies(usage -> {
                    assertThat(usage.getRecordState()).isEqualTo(ProviderUsageRecordState.MISSING);
                    assertThat(usage.getProviderLlmTokens()).isNull();
                });
    }

    @Test
    @DisplayName("Started and failed attempts stay incomplete and terminal-only facts are rejected")
    void unresolvedAttemptsStayIncompleteAndRequireDurableStart() {
        QuizGenerationJob job = persistJob();
        UUID providerAttemptId = UUID.randomUUID();

        assertThat(providerUsageService.recordStarted(job.getId(), providerAttemptId))
                .isEqualTo(ProviderUsageRecordResult.RECORDED);
        assertThat(usageRepository.findByJobIdAndProviderAttemptId(job.getId(), providerAttemptId).orElseThrow()
                .getRecordState()).isEqualTo(ProviderUsageRecordState.STARTED);
        assertThat(providerUsageService.recordFailed(job.getId(), providerAttemptId))
                .isEqualTo(ProviderUsageRecordResult.RECORDED);
        assertThat(providerUsageService.recordStarted(job.getId(), providerAttemptId))
                .isEqualTo(ProviderUsageRecordResult.DUPLICATE);
        assertThatThrownBy(() -> providerUsageService.recordReported(
                job.getId(), UUID.randomUUID(), 10L)).isInstanceOf(IllegalStateException.class);

        assertThat(jobRepository.findById(job.getId()).orElseThrow().getProviderUsageState())
                .isEqualTo(ProviderUsageState.INCOMPLETE);
        assertThat(usageRepository.findByJobIdAndProviderAttemptId(job.getId(), providerAttemptId))
                .get().extracting("recordState", "providerLlmTokens")
                .containsExactly(ProviderUsageRecordState.FAILED, null);
    }

    @Test
    @DisplayName("Recording outcomes expose only bounded metric tags")
    void recordingOutcomesExposeBoundedMetrics() {
        QuizGenerationJob job = persistJob();
        UUID providerAttemptId = UUID.randomUUID();
        UUID missingAttemptId = UUID.randomUUID();

        providerUsageService.recordStarted(job.getId(), providerAttemptId);
        providerUsageService.recordReported(job.getId(), providerAttemptId, 10L);
        providerUsageService.recordReported(job.getId(), providerAttemptId, 10L);
        providerUsageService.recordStarted(job.getId(), missingAttemptId);
        providerUsageService.recordMissing(job.getId(), missingAttemptId);

        assertThat(meterRegistry.find("quiz.generation.provider.usage")
                .tag("outcome", "started").counter().count()).isEqualTo(2.0);
        assertThat(meterRegistry.find("quiz.generation.provider.usage")
                .tag("outcome", "reported").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("quiz.generation.provider.usage")
                .tag("outcome", "duplicate").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("quiz.generation.provider.usage")
                .tag("outcome", "missing").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("quiz.generation.provider.usage").meters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags())
                        .extracting(tag -> tag.getKey())
                        .containsExactly("outcome"));
        assertThat(meterRegistry.get("quiz.generation.provider.tokens").counter().count())
                .isEqualTo(10.0);
        assertThat(meterRegistry.get("quiz.generation.provider.tokens").counter().getId().getTags())
                .isEmpty();
    }

    private ProviderUsageRecordResult recordAfterBarrier(
            CyclicBarrier barrier,
            UUID generationJobId,
            UUID providerAttemptId,
            long tokens
    ) throws Exception {
        barrier.await();
        return providerUsageService.recordReported(generationJobId, providerAttemptId, tokens);
    }

    private QuizGenerationJob persistJob() {
        User user = new User();
        user.setUsername("usage-" + UUID.randomUUID().toString().substring(0, 12));
        user.setEmail(user.getUsername() + "@example.com");
        user.setHashedPassword("not-used-by-this-test");
        user.setActive(true);
        user = userRepository.saveAndFlush(user);
        userId = user.getId();

        QuizGenerationJob job = new QuizGenerationJob();
        job.setUser(user);
        job.setDocumentId(UUID.randomUUID());
        job.setStatus(GenerationStatus.PROCESSING);
        job.setRequestData("{}");
        job = jobRepository.saveAndFlush(job);
        jobId = job.getId();
        return job;
    }
}
