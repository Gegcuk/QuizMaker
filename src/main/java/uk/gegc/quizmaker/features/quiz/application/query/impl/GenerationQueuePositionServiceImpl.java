package uk.gegc.quizmaker.features.quiz.application.query.impl;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.quiz.application.query.GenerationQueuePosition;
import uk.gegc.quizmaker.features.quiz.application.query.GenerationQueuePositionService;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationWorkRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class GenerationQueuePositionServiceImpl implements GenerationQueuePositionService {
    private final QuizGenerationWorkRepository repository;
    private final Clock clock;
    private final Clock systemClock;
    private final MeterRegistry metrics;
    private final TransactionTemplate readTransaction;

    public GenerationQueuePositionServiceImpl(QuizGenerationWorkRepository repository,
            Clock clock, @Qualifier("systemClock") Clock systemClock, MeterRegistry metrics,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.clock = clock;
        this.systemClock = systemClock;
        this.metrics = metrics;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public Map<UUID, GenerationQueuePosition> findPositions(Collection<UUID> jobIds, String username) {
        Objects.requireNonNull(jobIds, "jobIds must not be null");
        var ids = new LinkedHashSet<>(jobIds);
        if (ids.contains(null)) throw new IllegalArgumentException("jobIds must not contain null");
        // No caller identity can ever authorize a global queue read.
        if (ids.isEmpty() || username == null || username.isBlank()) return Map.of();
        long started = System.nanoTime();
        try {
            // Catch outside the transaction, including commit/rollback failures. A failed optional
            // read must not mark the caller's status transaction rollback-only.
            return Objects.requireNonNull(readTransaction.execute(tx -> {
                var asOf = systemClock.instant();
                var now = LocalDateTime.ofInstant(asOf, clock.getZone());
                var rows = repository.findQueuePositions(ids, username, now,
                        LocalDateTime.ofInstant(asOf, systemClock.getZone()), asOf);
                Map<UUID, GenerationQueuePosition> positions = new LinkedHashMap<>();
                for (var row : rows) {
                    Long position = row.getQueuePosition();
                    if (position != null && position > 0) {
                        positions.put(UUID.fromString(row.getJobId()), new GenerationQueuePosition(position, asOf));
                        observe("available");
                    } else {
                        observe(Integer.valueOf(1).equals(row.getInconsistent()) ? "inconsistent" : "unavailable");
                    }
                }
                return Map.copyOf(positions);
            }));
        } catch (DataAccessException | TransactionException exception) {
            observe("query_failed");
            log.warn("Generation queue position is temporarily unavailable");
            return Map.of();
        } finally {
            try {
                metrics.timer("quiz.generation.queue.position.duration")
                        .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            } catch (RuntimeException ignored) {
                log.warn("Generation queue position timing could not be recorded");
            }
        }
    }

    private void observe(String outcome) {
        try {
            metrics.counter("quiz.generation.queue.position.reads", "outcome", outcome).increment();
        } catch (RuntimeException ignored) {
            log.warn("Generation queue position outcome could not be recorded");
        }
    }
}
