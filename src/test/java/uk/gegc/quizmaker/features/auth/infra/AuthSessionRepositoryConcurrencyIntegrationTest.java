package uk.gegc.quizmaker.features.auth.infra;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.auth.domain.model.AuthSession;
import uk.gegc.quizmaker.features.auth.domain.repository.AuthSessionRepository;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Tag("db-serial")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Authentication Session Repository Concurrency")
class AuthSessionRepositoryConcurrencyIntegrationTest {

    @Autowired
    private AuthSessionRepository authSessionRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;
    private ExecutorService executor;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        executor = Executors.newFixedThreadPool(2);
        sessionId = UUID.randomUUID();

        transactionTemplate.executeWithoutResult(status -> authSessionRepository.saveAndFlush(new AuthSession(
                sessionId,
                UUID.randomUUID(),
                "current-refresh-verifier",
                LocalDateTime.now().minusMinutes(1),
                LocalDateTime.now().plusDays(1)
        )));
    }

    @AfterEach
    void cleanUp() {
        if (executor != null) {
            executor.shutdownNow();
        }
        if (transactionTemplate != null && sessionId != null) {
            transactionTemplate.executeWithoutResult(status -> authSessionRepository.deleteById(sessionId));
        }
    }

    @Test
    @DisplayName("pessimistic lock serializes refresh verification so the second transaction observes the rotated verifier")
    void findByIdForUpdate_serializesConcurrentRefreshVerification() throws Exception {
        CountDownLatch firstLockAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirstTransaction = new CountDownLatch(1);
        CountDownLatch secondTransactionStarted = new CountDownLatch(1);

        Future<Void> firstRefresh = executor.submit(() -> transactionTemplate.execute(status -> {
            AuthSession session = authSessionRepository.findByIdForUpdate(sessionId).orElseThrow();
            firstLockAcquired.countDown();
            await(releaseFirstTransaction);
            session.rotateRefreshToken("rotated-refresh-verifier", LocalDateTime.now());
            authSessionRepository.saveAndFlush(session);
            return null;
        }));

        assertThat(firstLockAcquired.await(5, TimeUnit.SECONDS)).isTrue();

        Future<String> secondRefresh = executor.submit(() -> transactionTemplate.execute(status -> {
            secondTransactionStarted.countDown();
            return authSessionRepository.findByIdForUpdate(sessionId)
                    .orElseThrow()
                    .getRefreshTokenHash();
        }));

        assertThat(secondTransactionStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(secondRefresh.isDone()).isFalse();

        releaseFirstTransaction.countDown();

        firstRefresh.get(5, TimeUnit.SECONDS);
        assertThat(secondRefresh.get(5, TimeUnit.SECONDS)).isEqualTo("rotated-refresh-verifier");
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for the concurrent transaction");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for the concurrent transaction", ex);
        }
    }
}
