package uk.gegc.quizmaker.features.quiz.application.generation.impl;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.billing.application.InternalBillingService;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.features.quiz.application.generation.*;
import uk.gegc.quizmaker.features.quiz.config.QuizJobProperties;
import uk.gegc.quizmaker.features.quiz.domain.model.*;
import uk.gegc.quizmaker.features.quiz.domain.repository.*;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class GenerationWorkServiceImpl implements GenerationWorkService {
    private final QuizGenerationWorkRepository workRepository;
    private final QuizGenerationJobRepository jobRepository;
    private final QuizGenerationOutputCheckpointRepository checkpointRepository;
    private final QuizGenerationCoverageRepository coverageRepository;
    private final DocumentRepository documentRepository;
    private final InternalBillingService billingService;
    private final QuizJobProperties properties;
    private final Clock clock;
    private final Clock systemClock;
    private final MeterRegistry metrics;
    private final TransactionTemplate transactions;
    private final AtomicLong outstanding = new AtomicLong();
    private final AtomicLong oldestSeconds = new AtomicLong();

    public GenerationWorkServiceImpl(
            QuizGenerationWorkRepository workRepository,
            QuizGenerationJobRepository jobRepository,
            QuizGenerationOutputCheckpointRepository checkpointRepository,
            QuizGenerationCoverageRepository coverageRepository,
            DocumentRepository documentRepository,
            InternalBillingService billingService,
            QuizJobProperties properties,
            Clock clock,
            @Qualifier("systemClock") Clock systemClock,
            MeterRegistry metrics,
            TransactionTemplate transactions
    ) {
        this.workRepository = workRepository;
        this.jobRepository = jobRepository;
        this.checkpointRepository = checkpointRepository;
        this.coverageRepository = coverageRepository;
        this.documentRepository = documentRepository;
        this.billingService = billingService;
        this.properties = properties;
        this.clock = clock;
        this.systemClock = systemClock;
        this.metrics = metrics;
        this.transactions = transactions;
    }

    @PostConstruct
    void registerMetrics() {
        try {
            Gauge.builder("quiz.generation.execution.outstanding", outstanding, AtomicLong::get).register(metrics);
            Gauge.builder("quiz.generation.execution.oldest.seconds", oldestSeconds, AtomicLong::get).register(metrics);
        } catch (RuntimeException ignored) {
            log.warn("Generation execution gauges could not be registered");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enroll(QuizGenerationJob job) {
        Document source = documentRepository.findByIdForGeneration(job.getDocumentId())
                .orElseThrow(() -> new IllegalStateException("Generation source is unavailable"));
        if (!source.getUploadedBy().getId().equals(job.getUser().getId())
                || source.getStatus() != Document.DocumentStatus.PROCESSED) {
            throw new IllegalStateException("Generation source is unavailable");
        }
        workRepository.saveAndFlush(new QuizGenerationWork(job.getId(), source.getContentRevision(), now()));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isManaged(UUID jobId) {
        return workRepository.existsById(jobId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findDue() {
        List<UUID> due = workRepository.findDue(now(), PageRequest.of(0, properties.getFinalization().getRecoveryBatchSize()));
        try {
            outstanding.set(workRepository.countOutstanding());
            LocalDateTime oldest = workRepository.findOldestOutstanding();
            oldestSeconds.set(oldest == null ? 0 : Math.max(0, Duration.between(oldest, now()).getSeconds()));
        } catch (RuntimeException ignored) {
            log.warn("Generation backlog measurements could not be refreshed");
        }
        return due;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Optional<GenerationWorkClaim> claim(UUID jobId) {
        ClaimResult result = Objects.requireNonNull(transactions.execute(tx -> claimInTransaction(jobId)));
        if (result.failed()) releaseFailedReservation(jobId);
        return Optional.ofNullable(result.claim());
    }

    private record ClaimResult(GenerationWorkClaim claim, boolean failed) { }

    private ClaimResult claimInTransaction(UUID jobId) {
        QuizGenerationJob job = jobRepository.findByIdForUpdate(jobId).orElse(null);
        QuizGenerationWork work = workRepository.findById(jobId).orElse(null);
        if (job == null || work == null || !work.isDue(now())) return new ClaimResult(null, false);
        if (job.isTerminal() || checkpointRepository.existsById(jobId)) {
            return new ClaimResult(null, finishWork(job, work));
        }
        String failure = work.getSchemaVersion() != 1 ? "Unsupported generation work schema"
                : work.getExecutions() >= 2 ? "Generation recovery attempts exhausted"
                : reservationExpired(job) ? "Generation reservation expired before recovery"
                : !sourceMatches(job, work) ? "Generation source changed or is unavailable" : null;
        if (failure != null) {
            failJob(job, work, failure);
            return new ClaimResult(null, true);
        }
        if (work.getExecutions() > 0) {
            // An interrupted pre-checkpoint decision is not a published result.
            // Bulk deletion cascades to its bounded type rows without relationship loading.
            coverageRepository.deleteUncheckpointedDecision(jobId);
            job.setCompletedTasks(0);
            job.setProcessedChunks(0);
            job.setCurrentChunk("Recovering interrupted generation");
            count("recovered");
        }
        long fence = work.claim(now(), properties.getExecution().getLeaseSeconds());
        job.setStatus(GenerationStatus.PROCESSING);
        count("claimed");
        return new ClaimResult(new GenerationWorkClaim(jobId, fence, job.getDocumentId(), job.getRequestData()), false);
    }

    @Override
    @Transactional
    public void heartbeat(GenerationWorkClaim claim) {
        QuizGenerationJob job = jobRepository.findByIdForUpdate(claim.jobId()).orElse(null);
        QuizGenerationWork work = workRepository.findById(claim.jobId()).orElse(null);
        if (job == null || work == null || !work.owns(claim.fence(), now())) return;
        if (job.isTerminal() || checkpointRepository.existsById(claim.jobId())) {
            finishWork(job, work);
        } else {
            work.heartbeat(now(), properties.getExecution().getLeaseSeconds());
            count("heartbeat");
        }
    }

    @Override
    @Transactional
    public <T> T protect(GenerationWorkClaim claim, Supplier<T> action) {
        QuizGenerationJob job = jobRepository.findByIdForUpdate(claim.jobId()).orElse(null);
        QuizGenerationWork work = workRepository.findById(claim.jobId()).orElse(null);
        if (job == null || work == null || job.isTerminal() || !work.owns(claim.fence(), now())
                || job.getFinalizationState() != QuizGenerationFinalizationState.NOT_STARTED
                || checkpointRepository.existsById(claim.jobId()) || !sourceMatches(job, work)) {
            count("fenced");
            throw new GenerationLeaseLostException();
        }
        return action.get();
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void finished(GenerationWorkClaim claim) {
        finish(claim, "Generation stopped before durable output was saved");
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void failed(GenerationWorkClaim claim) {
        finish(claim, "Generation execution failed");
    }

    private void finish(GenerationWorkClaim claim, String failure) {
        boolean failed = Boolean.TRUE.equals(transactions.execute(tx -> finishInTransaction(claim, failure)));
        if (failed) releaseFailedReservation(claim.jobId());
    }

    private boolean finishInTransaction(GenerationWorkClaim claim, String failure) {
        QuizGenerationJob job = jobRepository.findByIdForUpdate(claim.jobId()).orElse(null);
        QuizGenerationWork work = workRepository.findById(claim.jobId()).orElse(null);
        if (job == null || work == null || !work.owns(claim.fence(), now())) return false;
        if (job.isTerminal() || checkpointRepository.existsById(claim.jobId())) return finishWork(job, work);
        failJob(job, work, failure);
        return true;
    }

    private boolean sourceMatches(QuizGenerationJob job, QuizGenerationWork work) {
        return documentRepository.findByIdForGeneration(job.getDocumentId())
                .filter(d -> d.getStatus() == Document.DocumentStatus.PROCESSED)
                .filter(d -> d.getContentRevision() == work.getSourceRevision())
                .filter(d -> d.getUploadedBy().getId().equals(job.getUser().getId()))
                .isPresent();
    }

    private boolean reservationExpired(QuizGenerationJob job) {
        return job.getBillingReservationId() != null
                && (job.getBillingState() != BillingState.RESERVED || job.getReservationExpiresAt() == null
                || !job.getReservationExpiresAt().isAfter(LocalDateTime.now(systemClock)));
    }

    private void failJob(QuizGenerationJob job, QuizGenerationWork work, String reason) {
        job.markFailed(reason);
        job.setCompletedAt(LocalDateTime.now(systemClock));
        finishWork(job, work);
        count("failed");
    }

    private boolean finishWork(QuizGenerationJob job, QuizGenerationWork work) {
        boolean failed = job.getStatus() == GenerationStatus.FAILED;
        if (failed && job.getFinalizationState() == QuizGenerationFinalizationState.NOT_STARTED) {
            // Persist the terminal decision independently of billing availability.
            // The existing finalization recovery scan retries pending releases.
            job.markFinalizationFailed("Generation failed before durable output was saved", LocalDateTime.now(systemClock));
            if (job.getBillingReservationId() != null && job.getBillingState() == BillingState.RESERVED) {
                job.setLastBillingError("{\"reason\":\"Generation reservation release pending\"}");
            }
        }
        work.finish();
        return failed;
    }

    private void releaseFailedReservation(UUID jobId) {
        try {
            transactions.executeWithoutResult(tx -> {
                QuizGenerationJob job = jobRepository.findByIdForUpdate(jobId).orElse(null);
                if (job == null || job.getStatus() != GenerationStatus.FAILED
                        || job.getFinalizationState() != QuizGenerationFinalizationState.FAILED) return;
                releaseReservation(job);
            });
        } catch (RuntimeException failure) {
            log.warn("Reservation release is pending for failed generation job {}", jobId);
        }
    }

    private void releaseReservation(QuizGenerationJob job) {
        if (job.getBillingReservationId() != null && job.getBillingState() == BillingState.RESERVED) {
            String key = "quiz:" + job.getId() + ":release";
            billingService.release(job.getBillingReservationId(), "generation-failed", job.getId().toString(), key);
            job.setBillingState(BillingState.RELEASED);
            job.addBillingIdempotencyKey("release", key);
            job.setLastBillingError(null);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private void count(String outcome) {
        try {
            metrics.counter("quiz.generation.execution.operations", "outcome", outcome).increment();
        } catch (RuntimeException ignored) {
            log.warn("Generation execution metric could not be recorded");
        }
    }
}
