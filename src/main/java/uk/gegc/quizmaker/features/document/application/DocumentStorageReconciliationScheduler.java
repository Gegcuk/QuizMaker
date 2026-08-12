package uk.gegc.quizmaker.features.document.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Removes expired staging files and published files not referenced by a document row. */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentStorageReconciliationScheduler {

    static final int RECONCILIATION_BATCH_SIZE = 250;

    private final DocumentFileReferenceLookup fileReferenceLookup;
    private final DocumentUploadStagingService uploadStagingService;
    private final DocumentIngestionMetrics metrics;

    @Scheduled(fixedDelayString = "${quizmaker.document.processing.reconciliation-interval:PT1H}")
    public void reconcile() {
        long startedAtNanos = System.nanoTime();
        ReconciliationSummary summary = new ReconciliationSummary();
        try {
            List<Path> candidates = new ArrayList<>(RECONCILIATION_BATCH_SIZE);
            uploadStagingService.visitExpiredPublishedFiles(path -> {
                candidates.add(path);
                summary.candidates++;
                if (candidates.size() == RECONCILIATION_BATCH_SIZE) {
                    summary.cleanupFailures += reconcileBatch(candidates);
                    candidates.clear();
                }
            });
            summary.cleanupFailures += reconcileBatch(candidates);
            metrics.recordReconciliationCandidates(summary.candidates);
            if (summary.cleanupFailures == 0) {
                recordReconciliationOutcome(
                        DocumentIngestionMetrics.Outcome.SUCCEEDED,
                        DocumentIngestionMetrics.Reason.NONE,
                        startedAtNanos);
            } else {
                recordReconciliationOutcome(
                        DocumentIngestionMetrics.Outcome.FAILED,
                        DocumentIngestionMetrics.Reason.CLEANUP,
                        startedAtNanos);
            }
        } catch (RuntimeException e) {
            // Storage maintenance is best effort and must never affect uploads.
            metrics.recordReconciliationCandidates(summary.candidates);
            DocumentIngestionMetrics.Reason reason = DocumentIngestionMetrics.Reason.from(e);
            recordReconciliationOutcome(
                    DocumentIngestionMetrics.Outcome.FAILED,
                    reason,
                    startedAtNanos);
            log.warn("Document storage reconciliation did not complete (reason={})", reason.tagValue());
        }
    }

    private int reconcileBatch(List<Path> candidates) {
        if (candidates.isEmpty()) {
            return 0;
        }

        List<String> candidatePaths = candidates.stream()
                .map(path -> path.toAbsolutePath().normalize().toString())
                .toList();
        Set<String> referencedPaths = new HashSet<>(fileReferenceLookup.findReferencedPaths(candidatePaths));
        int cleanupFailures = 0;

        for (int index = 0; index < candidates.size(); index++) {
            String candidatePath = candidatePaths.get(index);
            if (referencedPaths.contains(candidatePath) || fileReferenceLookup.isReferenced(candidatePath)) {
                continue;
            }
            if (!uploadStagingService.discard(candidates.get(index))) {
                cleanupFailures++;
            }
        }
        return cleanupFailures;
    }

    private void recordReconciliationOutcome(
            DocumentIngestionMetrics.Outcome outcome,
            DocumentIngestionMetrics.Reason reason,
            long startedAtNanos
    ) {
        metrics.recordEvent(DocumentIngestionMetrics.Stage.RECONCILIATION, outcome, reason);
        metrics.recordDuration(
                DocumentIngestionMetrics.Stage.RECONCILIATION,
                outcome,
                Duration.ofNanos(Math.max(0, System.nanoTime() - startedAtNanos)));
    }

    private static final class ReconciliationSummary {
        private int candidates;
        private int cleanupFailures;
    }
}
