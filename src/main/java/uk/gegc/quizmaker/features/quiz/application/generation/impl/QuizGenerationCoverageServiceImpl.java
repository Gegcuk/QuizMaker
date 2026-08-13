package uk.gegc.quizmaker.features.quiz.application.generation.impl;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.quiz.application.generation.GenerationCoverageSnapshot;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationCoverageException;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationCoverageService;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationCoverageOutcome;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationCoverage;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationFinalizationState;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationTypeCoverage;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationCoverageRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@Slf4j
public class QuizGenerationCoverageServiceImpl implements QuizGenerationCoverageService {

    private final QuizGenerationCoverageRepository coverageRepository;
    private final QuizGenerationJobRepository jobRepository;
    private final Clock clock;
    private final Counter savedCounter;
    private final Counter duplicateCounter;
    private final Counter conflictCounter;
    private final Counter rejectedCounter;
    private final Counter persistenceFailedCounter;
    private final Counter invalidReadCounter;
    private final Map<GenerationCoverageOutcome, Counter> decisionCounters;
    private final DistributionSummary requestedSummary;
    private final DistributionSummary acceptedSummary;
    private final DistributionSummary missingSummary;
    private final DistributionSummary discardedSummary;

    public QuizGenerationCoverageServiceImpl(
            QuizGenerationCoverageRepository coverageRepository,
            QuizGenerationJobRepository jobRepository,
            @Qualifier("systemClock") Clock clock,
            MeterRegistry meterRegistry
    ) {
        this.coverageRepository = coverageRepository;
        this.jobRepository = jobRepository;
        this.clock = clock;
        this.savedCounter = operationCounter(meterRegistry, "saved");
        this.duplicateCounter = operationCounter(meterRegistry, "duplicate");
        this.conflictCounter = operationCounter(meterRegistry, "conflict");
        this.rejectedCounter = operationCounter(meterRegistry, "rejected");
        this.persistenceFailedCounter = operationCounter(meterRegistry, "persistence_failed");
        this.invalidReadCounter = operationCounter(meterRegistry, "invalid_read");
        this.decisionCounters = Map.of(
                GenerationCoverageOutcome.COMPLETE,
                decisionCounter(meterRegistry, "complete"),
                GenerationCoverageOutcome.PARTIAL,
                decisionCounter(meterRegistry, "partial"),
                GenerationCoverageOutcome.FAILED_THRESHOLD,
                decisionCounter(meterRegistry, "failed_threshold")
        );
        this.requestedSummary = countSummary(meterRegistry, "requested");
        this.acceptedSummary = countSummary(meterRegistry, "accepted");
        this.missingSummary = countSummary(meterRegistry, "missing");
        this.discardedSummary = countSummary(meterRegistry, "discarded");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveDecision(UUID jobId, GenerationCoverageSnapshot snapshot) {
        Objects.requireNonNull(jobId, "jobId must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        QuizGenerationJob job = jobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Generation job not found: " + jobId));
        QuizGenerationCoverage existing = findCoverage(jobId);
        if (existing != null) {
            GenerationCoverageSnapshot persisted = toSnapshot(existing);
            if (persisted.equals(snapshot)) {
                duplicateCounter.increment();
                return;
            }
            conflictCounter.increment();
            log.error("Conflicting immutable generation coverage detected for job {}", jobId);
            throw new QuizGenerationCoverageException("A different generation coverage decision is already persisted");
        }

        if (job.getStatus() != GenerationStatus.PROCESSING
                || job.getFinalizationState() != QuizGenerationFinalizationState.NOT_STARTED) {
            rejectedCounter.increment();
            throw new QuizGenerationCoverageException(
                    "Generation job is no longer eligible for coverage reconciliation"
            );
        }

        try {
            coverageRepository.saveAndFlush(toEntity(jobId, snapshot));
        } catch (RuntimeException exception) {
            persistenceFailedCounter.increment();
            throw exception;
        }

        savedCounter.increment();
        decisionCounters.get(snapshot.outcome()).increment();
        requestedSummary.record(snapshot.requestedTotal());
        acceptedSummary.record(snapshot.acceptedTotal());
        missingSummary.record(snapshot.missingTotal());
        discardedSummary.record(snapshot.discardedTotal());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, GenerationCoverageSnapshot> findByJobIds(Collection<UUID> jobIds) {
        Objects.requireNonNull(jobIds, "jobIds must not be null");
        LinkedHashSet<UUID> distinctJobIds = new LinkedHashSet<>();
        for (UUID jobId : jobIds) {
            distinctJobIds.add(Objects.requireNonNull(jobId, "jobIds must not contain null"));
        }
        if (distinctJobIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, GenerationCoverageSnapshot> snapshots = new LinkedHashMap<>();
        for (QuizGenerationCoverage coverage : coverageRepository.findAllWithTypesByJobIdIn(distinctJobIds)) {
            try {
                snapshots.put(coverage.getJobId(), toSnapshot(coverage));
            } catch (RuntimeException exception) {
                invalidReadCounter.increment();
                log.error("Ignoring malformed generation coverage for job {}", coverage.getJobId());
            }
        }
        return Collections.unmodifiableMap(snapshots);
    }

    private QuizGenerationCoverage findCoverage(UUID jobId) {
        return coverageRepository.findAllWithTypesByJobIdIn(List.of(jobId)).stream()
                .findFirst()
                .orElse(null);
    }

    private QuizGenerationCoverage toEntity(UUID jobId, GenerationCoverageSnapshot snapshot) {
        QuizGenerationCoverage coverage = new QuizGenerationCoverage(
                jobId,
                snapshot.outcome(),
                snapshot.thresholdPercent(),
                snapshot.requestedTotal(),
                snapshot.acceptedTotal(),
                snapshot.missingTotal(),
                snapshot.discardedTotal(),
                LocalDateTime.now(clock)
        );
        snapshot.types().forEach(type -> coverage.addType(
                type.questionType(),
                type.requested(),
                type.accepted(),
                type.missing()
        ));
        return coverage;
    }

    private GenerationCoverageSnapshot toSnapshot(QuizGenerationCoverage coverage) {
        List<GenerationCoverageSnapshot.TypeCoverage> types = coverage.getTypes().stream()
                .map(this::toTypeSnapshot)
                .toList();
        return new GenerationCoverageSnapshot(
                coverage.getOutcome(),
                coverage.getThresholdPercent(),
                coverage.getRequestedCount(),
                coverage.getAcceptedCount(),
                coverage.getMissingCount(),
                coverage.getDiscardedCount(),
                types
        );
    }

    private GenerationCoverageSnapshot.TypeCoverage toTypeSnapshot(QuizGenerationTypeCoverage type) {
        return new GenerationCoverageSnapshot.TypeCoverage(
                type.getQuestionType(),
                type.getRequestedCount(),
                type.getAcceptedCount(),
                type.getMissingCount()
        );
    }

    private Counter operationCounter(MeterRegistry meterRegistry, String outcome) {
        return Counter.builder("quiz.generation.coverage.operations")
                .description("Immutable generation coverage persistence operations")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    private Counter decisionCounter(MeterRegistry meterRegistry, String outcome) {
        return Counter.builder("quiz.generation.coverage.decisions")
                .description("Persisted generation coverage decisions")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    private DistributionSummary countSummary(MeterRegistry meterRegistry, String count) {
        return DistributionSummary.builder("quiz.generation.coverage.count")
                .description("Persisted generation coverage count distributions")
                .tag("count", count)
                .register(meterRegistry);
    }
}
