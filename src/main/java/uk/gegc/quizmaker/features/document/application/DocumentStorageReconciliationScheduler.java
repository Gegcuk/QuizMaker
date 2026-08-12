package uk.gegc.quizmaker.features.document.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
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

    @Scheduled(fixedDelayString = "${quizmaker.document.processing.reconciliation-interval:PT1H}")
    public void reconcile() {
        try {
            List<Path> candidates = new ArrayList<>(RECONCILIATION_BATCH_SIZE);
            uploadStagingService.visitExpiredPublishedFiles(path -> {
                candidates.add(path);
                if (candidates.size() == RECONCILIATION_BATCH_SIZE) {
                    reconcileBatch(candidates);
                    candidates.clear();
                }
            });
            reconcileBatch(candidates);
        } catch (RuntimeException e) {
            // Storage maintenance is best effort and must never affect uploads.
            log.warn("Document storage reconciliation did not complete", e);
        }
    }

    private void reconcileBatch(List<Path> candidates) {
        if (candidates.isEmpty()) {
            return;
        }

        List<String> candidatePaths = candidates.stream()
                .map(path -> path.toAbsolutePath().normalize().toString())
                .toList();
        Set<String> referencedPaths = new HashSet<>(fileReferenceLookup.findReferencedPaths(candidatePaths));

        for (int index = 0; index < candidates.size(); index++) {
            String candidatePath = candidatePaths.get(index);
            if (referencedPaths.contains(candidatePath) || fileReferenceLookup.isReferenced(candidatePath)) {
                continue;
            }
            uploadStagingService.discard(candidates.get(index));
        }
    }
}
