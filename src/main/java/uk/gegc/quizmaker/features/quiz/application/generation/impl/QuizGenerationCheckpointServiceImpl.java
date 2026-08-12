package uk.gegc.quizmaker.features.quiz.application.generation.impl;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.quiz.application.generation.GeneratedQuizCheckpoint;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationCheckpointException;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationCheckpointService;
import uk.gegc.quizmaker.features.quiz.config.QuizJobProperties;
import uk.gegc.quizmaker.features.quiz.domain.model.BillingState;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationFinalizationState;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationOutputCheckpoint;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationOutputCheckpointRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@Slf4j
public class QuizGenerationCheckpointServiceImpl implements QuizGenerationCheckpointService {

    private final QuizGenerationOutputCheckpointRepository checkpointRepository;
    private final QuizGenerationJobRepository jobRepository;
    private final QuizGenerationCheckpointCodec codec;
    private final QuizJobProperties properties;
    private final Clock clock;
    private final Counter savedCounter;
    private final Counter duplicateCounter;
    private final Counter loadedCounter;
    private final Counter deletedCounter;
    private final Counter deleteFailedCounter;
    private final Counter rejectedCounter;

    public QuizGenerationCheckpointServiceImpl(
            QuizGenerationOutputCheckpointRepository checkpointRepository,
            QuizGenerationJobRepository jobRepository,
            QuizGenerationCheckpointCodec codec,
            QuizJobProperties properties,
            @Qualifier("systemClock") Clock clock,
            MeterRegistry meterRegistry
    ) {
        this.checkpointRepository = checkpointRepository;
        this.jobRepository = jobRepository;
        this.codec = codec;
        this.properties = properties;
        this.clock = clock;
        this.savedCounter = counter(meterRegistry, "saved");
        this.duplicateCounter = counter(meterRegistry, "duplicate");
        this.loadedCounter = counter(meterRegistry, "loaded");
        this.deletedCounter = counter(meterRegistry, "deleted");
        this.deleteFailedCounter = counter(meterRegistry, "delete_failed");
        this.rejectedCounter = counter(meterRegistry, "rejected");
    }

    @Override
    @Transactional
    public void save(UUID jobId, Map<Integer, List<Question>> chunkQuestions) {
        Objects.requireNonNull(jobId, "jobId must not be null");
        QuizGenerationCheckpointCodec.EncodedCheckpoint encoded = codec.encode(
                chunkQuestions,
                properties.getFinalization().getCheckpointMaxBytes()
        );
        QuizGenerationJob job = jobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Generation job not found: " + jobId));

        if (job.isTerminal() || job.getStatus() != GenerationStatus.PROCESSING) {
            rejectedCounter.increment();
            throw new QuizGenerationCheckpointException("Generation job is no longer eligible for output checkpointing");
        }

        QuizGenerationOutputCheckpoint existing = checkpointRepository.findById(jobId).orElse(null);
        if (existing != null) {
            if (!existing.matches(encoded.schemaVersion(), encoded.payload(), encoded.questionCount())) {
                rejectedCounter.increment();
                throw new QuizGenerationCheckpointException("A different generated output is already checkpointed");
            }
            duplicateCounter.increment();
            return;
        }
        if (job.getFinalizationState() != QuizGenerationFinalizationState.NOT_STARTED) {
            rejectedCounter.increment();
            throw new QuizGenerationCheckpointException("Generation finalization has already started");
        }

        checkpointRepository.saveAndFlush(new QuizGenerationOutputCheckpoint(
                jobId,
                encoded.schemaVersion(),
                encoded.payload(),
                encoded.questionCount(),
                LocalDateTime.now(clock)
        ));
        savedCounter.increment();
        log.info("Checkpointed {} generated questions for job {}", encoded.questionCount(), jobId);
    }

    @Override
    @Transactional(readOnly = true)
    public GeneratedQuizCheckpoint getRequired(UUID jobId) {
        Objects.requireNonNull(jobId, "jobId must not be null");
        QuizGenerationOutputCheckpoint checkpoint = checkpointRepository.findById(jobId)
                .orElseThrow(() -> new QuizGenerationCheckpointException(
                        "No durable generated output exists for job " + jobId));
        try {
            GeneratedQuizCheckpoint decoded = codec.decode(
                    checkpoint.getSchemaVersion(),
                    checkpoint.getPayload(),
                    checkpoint.getQuestionCount(),
                    properties.getFinalization().getCheckpointMaxBytes()
            );
            loadedCounter.increment();
            return decoded;
        } catch (QuizGenerationCheckpointException exception) {
            rejectedCounter.increment();
            throw exception;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(UUID jobId) {
        Objects.requireNonNull(jobId, "jobId must not be null");
        return checkpointRepository.existsById(jobId);
    }

    @Override
    @Transactional
    public int delete(UUID jobId) {
        Objects.requireNonNull(jobId, "jobId must not be null");
        try {
            int deleted = checkpointRepository.deleteByJobId(jobId);
            if (deleted > 0) {
                deletedCounter.increment(deleted);
            }
            return deleted;
        } catch (RuntimeException exception) {
            deleteFailedCounter.increment();
            throw exception;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public RecoveryBatch findRecoveryBatch(int recoveryGraceSeconds, int batchSize) {
        if (recoveryGraceSeconds < 0) {
            throw new IllegalArgumentException("recoveryGraceSeconds must not be negative");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime cutoff = now.minusSeconds(recoveryGraceSeconds);
        PageRequest page = PageRequest.of(0, batchSize);
        return new RecoveryBatch(
                checkpointRepository.findCheckpointedJobIds(
                        GenerationStatus.PROCESSING,
                        QuizGenerationFinalizationState.NOT_STARTED,
                        cutoff,
                        page
                ),
                checkpointRepository.findStaleCheckpointedFinalizationJobIds(
                        GenerationStatus.PROCESSING,
                        QuizGenerationFinalizationState.FINALIZING,
                        cutoff,
                        page
                ),
                checkpointRepository.findStaleUncheckpointedFinalizationJobIds(
                        GenerationStatus.PROCESSING,
                        QuizGenerationFinalizationState.FINALIZING,
                        cutoff,
                        page
                ),
                checkpointRepository.findExpiredUncheckpointedJobIds(
                        GenerationStatus.PROCESSING,
                        QuizGenerationFinalizationState.NOT_STARTED,
                        BillingState.RESERVED,
                        now,
                        page
                )
        );
    }

    private Counter counter(MeterRegistry meterRegistry, String outcome) {
        return Counter.builder("quiz.generation.checkpoint.operations")
                .description("Durable generated-output checkpoint operations")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }
}
