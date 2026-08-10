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
    @DisplayName("Concurrent duplicate delivery contributes exactly once")
    void concurrentDuplicateDeliveryContributesExactlyOnce() throws Exception {
        QuizGenerationJob job = persistJob();
        UUID providerAttemptId = UUID.randomUUID();
        CyclicBarrier barrier = new CyclicBarrier(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<ProviderUsageRecordResult>> futures = List.of(
                    executor.submit(() -> recordAfterBarrier(barrier, job.getId(), providerAttemptId, 125L)),
                    executor.submit(() -> recordAfterBarrier(barrier, job.getId(), providerAttemptId, 125L))
            );

            assertThat(List.of(futures.get(0).get(), futures.get(1).get()))
                    .containsExactlyInAnyOrder(
                            ProviderUsageRecordResult.RECORDED,
                            ProviderUsageRecordResult.DUPLICATE
                    );
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        QuizGenerationJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getProviderLlmTokens()).isEqualTo(125L);
        assertThat(usageRepository.countByJobId(job.getId())).isEqualTo(1L);
    }

    @Test
    @DisplayName("Missing usage is durable and cannot be replaced by a conflicting retry")
    void missingUsageIsDurableAndConflictingRetryIsRejected() {
        QuizGenerationJob job = persistJob();
        UUID providerAttemptId = UUID.randomUUID();

        assertThat(providerUsageService.recordMissing(job.getId(), providerAttemptId))
                .isEqualTo(ProviderUsageRecordResult.RECORDED);
        assertThat(providerUsageService.recordMissing(job.getId(), providerAttemptId))
                .isEqualTo(ProviderUsageRecordResult.DUPLICATE);
        assertThatThrownBy(() -> providerUsageService.recordReported(job.getId(), providerAttemptId, 99L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(providerAttemptId.toString());

        QuizGenerationJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getProviderLlmTokens()).isNull();
        assertThat(reloaded.getProviderUsageState()).isEqualTo(ProviderUsageState.INCOMPLETE);
        assertThat(usageRepository.findByJobIdAndProviderAttemptId(job.getId(), providerAttemptId))
                .get()
                .satisfies(usage -> {
                    assertThat(usage.getRecordState()).isEqualTo(ProviderUsageRecordState.MISSING);
                    assertThat(usage.getProviderLlmTokens()).isNull();
                });
    }

    @Test
    @DisplayName("Recording outcomes expose only bounded metric tags")
    void recordingOutcomesExposeBoundedMetrics() {
        QuizGenerationJob job = persistJob();
        UUID providerAttemptId = UUID.randomUUID();

        providerUsageService.recordReported(job.getId(), providerAttemptId, 10L);
        providerUsageService.recordReported(job.getId(), providerAttemptId, 10L);
        providerUsageService.recordMissing(job.getId(), UUID.randomUUID());

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
