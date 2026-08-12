package uk.gegc.quizmaker.features.document.application.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gegc.quizmaker.features.document.application.DocumentUploadStagingService;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DisplayName("After-commit document source cleanup")
class AfterCommitDocumentSourceFileCleanupTest {

    private DocumentUploadStagingService uploadStagingService;
    private AfterCommitDocumentSourceFileCleanup cleanup;
    private Path publishedPath;

    @BeforeEach
    void setUp() {
        uploadStagingService = mock(DocumentUploadStagingService.class);
        cleanup = new AfterCommitDocumentSourceFileCleanup(uploadStagingService);
        publishedPath = Path.of("/published/document.pdf");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    @DisplayName("Defers source removal until the transaction commit callback")
    void removesSourceOnlyAfterCommit() {
        cleanup.deleteAfterCommit(publishedPath);

        verify(uploadStagingService, never()).discard(publishedPath);
        commitRegisteredCallbacks();

        verify(uploadStagingService).discard(publishedPath);
    }

    @Test
    @DisplayName("Preserves the source when the transaction rolls back")
    void preservesSourceAfterRollback() {
        cleanup.deleteAfterCommit(publishedPath);

        registeredCallbacks().forEach(callback ->
                callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(uploadStagingService, never()).discard(publishedPath);
    }

    @Test
    @DisplayName("Does not turn a committed database deletion into a request failure when cleanup fails")
    void containsPostCommitCleanupFailure() {
        org.mockito.Mockito.doThrow(new IllegalStateException("simulated storage failure"))
                .when(uploadStagingService).discard(publishedPath);
        cleanup.deleteAfterCommit(publishedPath);

        assertThatCode(this::commitRegisteredCallbacks).doesNotThrowAnyException();
        verify(uploadStagingService).discard(publishedPath);
    }

    @Test
    @DisplayName("Rejects cleanup scheduling outside a real synchronized transaction")
    void rejectsSchedulingWithoutActiveTransaction() {
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);

        assertThatThrownBy(() -> cleanup.deleteAfterCommit(publishedPath))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Document source cleanup requires an active transaction");
        verify(uploadStagingService, never()).discard(publishedPath);
    }

    @Test
    @DisplayName("Keeps duplicate committed cleanup requests idempotent at the storage boundary")
    void delegatesDuplicateCommitCallbacksSafely() {
        cleanup.deleteAfterCommit(publishedPath);
        cleanup.deleteAfterCommit(publishedPath);

        commitRegisteredCallbacks();

        verify(uploadStagingService, times(2)).discard(publishedPath);
    }

    private void commitRegisteredCallbacks() {
        registeredCallbacks().forEach(TransactionSynchronization::afterCommit);
    }

    private List<TransactionSynchronization> registeredCallbacks() {
        return List.copyOf(TransactionSynchronizationManager.getSynchronizations());
    }
}
