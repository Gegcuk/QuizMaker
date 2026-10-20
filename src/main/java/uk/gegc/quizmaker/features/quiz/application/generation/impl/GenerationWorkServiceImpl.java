package uk.gegc.quizmaker.features.quiz.application.generation.impl;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
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
    private final MeterRegistry metrics;
    private final AtomicLong outstanding = new AtomicLong();
    private final AtomicLong oldestSeconds = new AtomicLong();

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
    @Transactional
    public Optional<GenerationWorkClaim> claim(UUID jobId) {
        QuizGenerationJob job = jobRepository.findByIdForUpdate(jobId).orElse(null);
        QuizGenerationWork work = workRepository.findById(jobId).orElse(null);
        if (job == null || work == null || !work.isDue(now())) return Optional.empty();
        if (job.isTerminal() || checkpointRepository.existsById(jobId)) {
            work.finish();
            return Optional.empty();
        }
        String failure = work.getSchemaVersion() != 1 ? "Unsupported generation work schema"
                : work.getExecutions() >= 2 ? "Generation recovery attempts exhausted"
                : reservationExpired(job) ? "Generation reservation expired before recovery"
                : !sourceMatches(job, work) ? "Generation source changed or is unavailable" : null;
        if (failure != null) {
            failJob(job, work, failure);
            return Optional.empty();
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
        return Optional.of(new GenerationWorkClaim(jobId, fence, job.getDocumentId(), job.getRequestData()));
    }

    @Override
    @Transactional
    public void heartbeat(GenerationWorkClaim claim) {
        QuizGenerationJob job = jobRepository.findByIdForUpdate(claim.jobId()).orElse(null);
        QuizGenerationWork work = workRepository.findById(claim.jobId()).orElse(null);
        if (job == null || work == null || !work.owns(claim.fence(), now())) return;
        if (job.isTerminal() || checkpointRepository.existsById(claim.jobId())) {
            work.finish();
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
    @Transactional
    public void finished(GenerationWorkClaim claim) {
        finish(claim, "Generation stopped before durable output was saved");
    }

    @Override
    @Transactional
    public void failed(GenerationWorkClaim claim) {
        finish(claim, "Generation execution failed");
    }

    private void finish(GenerationWorkClaim claim, String failure) {
        QuizGenerationJob job = jobRepository.findByIdForUpdate(claim.jobId()).orElse(null);
        QuizGenerationWork work = workRepository.findById(claim.jobId()).orElse(null);
        if (job == null || work == null || !work.owns(claim.fence(), now())) return;
        if (job.isTerminal() || checkpointRepository.existsById(claim.jobId())) work.finish();
        else failJob(job, work, failure);
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
                || !job.getReservationExpiresAt().isAfter(now()));
    }

    private void failJob(QuizGenerationJob job, QuizGenerationWork work, String reason) {
        job.markFailed(reason);
        job.setCompletedAt(now());
        if (job.getBillingReservationId() != null && job.getBillingState() == BillingState.RESERVED) {
            String key = "quiz:" + job.getId() + ":release";
            billingService.release(job.getBillingReservationId(), reason, job.getId().toString(), key);
            job.setBillingState(BillingState.RELEASED);
            job.addBillingIdempotencyKey("release", key);
        }
        work.finish();
        count("failed");
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
