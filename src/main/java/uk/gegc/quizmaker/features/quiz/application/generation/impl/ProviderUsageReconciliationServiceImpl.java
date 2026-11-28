package uk.gegc.quizmaker.features.quiz.application.generation.impl;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.quiz.application.generation.ProviderUsageReconciliationService;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationProviderUsageRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationProviderUsageRepository.StartedAttempt;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
public class ProviderUsageReconciliationServiceImpl implements ProviderUsageReconciliationService {
    private static final int BATCH_SIZE = 50;

    private final QuizGenerationProviderUsageRepository usage;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final AtomicReference<Backlog> backlog = new AtomicReference<>(new Backlog(0, 0, 0));

    private record Page(UUID next, int markedUnknown) {}
    private record Backlog(long unknownCount, long oldestUnresolvedSeconds, long oldestUnknownSeconds) {}

    public ProviderUsageReconciliationServiceImpl(QuizGenerationProviderUsageRepository usage,
            PlatformTransactionManager manager, Clock clock, MeterRegistry metrics) {
        this.usage = usage;
        this.clock = clock;
        this.metrics = metrics;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        registerMetrics();
    }

    @Override
    public UUID reconcilePage(UUID after) {
        Page page = Objects.requireNonNull(transaction.execute(status -> reconcile(after)));
        report(page.markedUnknown());
        return page.next();
    }

    private Page reconcile(UUID after) {
        var page = usage.findStartedPage(after == null ? null : after.toString(), LocalDateTime.now(clock), BATCH_SIZE);
        UUID next = page.size() == BATCH_SIZE
                ? UUID.fromString(page.get(page.size() - 1).getAttemptRowId()) : null;
        var interrupted = page.stream().filter(row -> row.getInterrupted() == 1).toList();
        if (interrupted.isEmpty()) return new Page(next, 0);

        var jobIds = interrupted.stream().map(StartedAttempt::getJobId).distinct().map(UUID::fromString).toList();
        var locked = usage.lockJobsForReconciliation(jobIds).stream().map(UUID::fromString).toList();
        if (locked.isEmpty()) return new Page(next, 0);

        var attemptIds = interrupted.stream().map(row -> UUID.fromString(row.getAttemptRowId())).toList();
        // Recheck the evidence and STARTED under the job locks. Busy jobs are revisited
        // on the next sweep, and a late terminal fact can still resolve UNKNOWN.
        return new Page(next, usage.markInterrupted(attemptIds, locked, LocalDateTime.now(clock)));
    }

    private void report(int markedUnknown) {
        try {
            Counter.builder("quiz.generation.provider.usage")
                    .description("Durable provider usage recording outcomes")
                    .tag("outcome", "unknown")
                    .register(metrics)
                    .increment(markedUnknown);
        } catch (RuntimeException ignored) {
            log.warn("Provider usage reconciliation counter unavailable");
        }
        try {
            var unresolved = usage.summarizeUnresolved(BATCH_SIZE);
            LocalDateTime now = LocalDateTime.now(clock);
            backlog.set(new Backlog(unresolved.getUnknownCount(),
                    age(unresolved.getOldestUnresolvedAt(), now), age(unresolved.getOldestUnknownAt(), now)));
        } catch (RuntimeException ignored) {
            log.warn("Provider usage reconciliation backlog unavailable");
        }
    }

    private void registerMetrics() {
        try {
            Gauge.builder("quiz.generation.provider.usage.unknown.backlog", backlog,
                            value -> value.get().unknownCount())
                    .description("Unresolved provider outcomes, capped at " + BATCH_SIZE)
                    .register(metrics);
            Gauge.builder("quiz.generation.provider.usage.oldest", backlog,
                            value -> value.get().oldestUnresolvedSeconds())
                    .tag("state", "unresolved").baseUnit("seconds").register(metrics);
            Gauge.builder("quiz.generation.provider.usage.oldest", backlog,
                            value -> value.get().oldestUnknownSeconds())
                    .tag("state", "unknown").baseUnit("seconds").register(metrics);
        } catch (RuntimeException ignored) {
            log.warn("Provider usage reconciliation gauges unavailable");
        }
    }

    private static long age(LocalDateTime recordedAt, LocalDateTime now) {
        return recordedAt == null ? 0 : Math.max(0, Duration.between(recordedAt, now).toSeconds());
    }
}
