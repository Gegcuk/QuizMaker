package uk.gegc.quizmaker.features.document.application.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gegc.quizmaker.features.document.application.DocumentSourceFileCleanup;
import uk.gegc.quizmaker.features.document.application.DocumentUploadStagingService;

import java.nio.file.Path;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class AfterCommitDocumentSourceFileCleanup implements DocumentSourceFileCleanup {

    private final DocumentUploadStagingService uploadStagingService;

    @Override
    public void deleteAfterCommit(Path publishedPath) {
        Objects.requireNonNull(publishedPath, "Published document path is required");
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Document source cleanup requires an active transaction");
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    if (!uploadStagingService.discard(publishedPath)) {
                        log.warn("Document source cleanup deferred after committed deletion (reason=cleanup)");
                    }
                } catch (RuntimeException cleanupFailure) {
                    // The database deletion is already committed; reconciliation owns the retry.
                    log.warn("Document source cleanup deferred after committed deletion (reason=cleanup)");
                }
            }
        });
    }
}
