package uk.gegc.quizmaker.features.quiz.application.generation.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.ai.application.AiQuizGenerationService;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.application.generation.*;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class GenerationWorkExecutorImpl implements GenerationWorkExecutor {
    private final GenerationWorkService workService;
    private final AiQuizGenerationService aiService;
    private final ObjectMapper objectMapper;
    private final Map<UUID, GenerationWorkClaim> active = new ConcurrentHashMap<>();

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void execute(UUID jobId) {
        workService.claim(jobId).ifPresent(claim -> {
            // A claimant can pause after committing its lease and return after a
            // replacement has registered. Never replace a newer heartbeat owner.
            active.merge(jobId, claim, (registered, incoming) ->
                    registered.fence() > incoming.fence() ? registered : incoming);
            try {
                var request = objectMapper.readValue(claim.requestData(), GenerateQuizFromDocumentRequest.class);
                if (request == null || !java.util.Objects.equals(claim.documentId(), request.documentId())) {
                    throw new IllegalStateException("Persisted generation request has a different source");
                }
                GenerationWorkerContext.run(claim, workService,
                        () -> aiService.generateQuizFromDocumentAsync(jobId, request));
                workService.finished(claim);
            } catch (GenerationLeaseLostException ignored) {
                // Current owner or recovery, rather than this former worker, decides the outcome.
            } catch (Exception failure) {
                log.warn("Generation worker stopped for job {}", jobId);
                workService.failed(claim);
            } finally {
                active.remove(jobId, claim);
            }
        });
    }

    @Scheduled(fixedDelayString = "${quiz.jobs.execution.heartbeat-seconds:30}000",
            scheduler = "generationLeaseScheduler")
    public void heartbeat() {
        active.values().forEach(claim -> {
            try {
                workService.heartbeat(claim);
            } catch (RuntimeException ignored) {
                // A database outage never grants a lease extension. Recovery uses persisted time.
                log.warn("Generation heartbeat could not be persisted for job {}", claim.jobId());
            }
        });
    }
}
