package uk.gegc.quizmaker.features.document.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Document storage reconciliation scheduler")
class DocumentStorageReconciliationSchedulerTest {

    private final DocumentIngestionMetrics metrics = mock(DocumentIngestionMetrics.class);

    @Test
    @DisplayName("Resolves live files in fixed-size batches without per-file database lookups")
    void resolvesLiveFilesInBoundedBatches() {
        DocumentFileReferenceLookup referenceLookup = mock(DocumentFileReferenceLookup.class);
        DocumentUploadStagingService stagingService = mock(DocumentUploadStagingService.class);
        List<Path> candidates = IntStream.rangeClosed(1, 251)
                .mapToObj(index -> Path.of("/storage/published/live-" + index + ".pdf"))
                .toList();
        streamCandidates(stagingService, candidates);
        when(referenceLookup.findReferencedPaths(anyCollection()))
                .thenAnswer(invocation -> new HashSet<>(invocation.<Collection<String>>getArgument(0)));

        new DocumentStorageReconciliationScheduler(referenceLookup, stagingService, metrics).reconcile();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> batches = ArgumentCaptor.forClass(Collection.class);
        verify(referenceLookup, times(2)).findReferencedPaths(batches.capture());
        assertThat(batches.getAllValues()).extracting(Collection::size)
                .containsExactly(DocumentStorageReconciliationScheduler.RECONCILIATION_BATCH_SIZE, 1);
        verify(referenceLookup, never()).isReferenced(any());
        verify(stagingService, never()).discard(any());
        verify(metrics).recordReconciliationCandidates(251);
        verify(metrics).recordEvent(
                DocumentIngestionMetrics.Stage.RECONCILIATION,
                DocumentIngestionMetrics.Outcome.SUCCEEDED,
                DocumentIngestionMetrics.Reason.NONE);
    }

    @Test
    @DisplayName("Rechecks an apparent orphan immediately before deleting it")
    void rechecksApparentOrphanBeforeDeletion() {
        DocumentFileReferenceLookup referenceLookup = mock(DocumentFileReferenceLookup.class);
        DocumentUploadStagingService stagingService = mock(DocumentUploadStagingService.class);
        Path candidate = Path.of("/storage/published/orphan.pdf");
        streamCandidates(stagingService, List.of(candidate));
        when(referenceLookup.findReferencedPaths(anyCollection())).thenReturn(Set.of());
        when(referenceLookup.isReferenced(candidate.toString())).thenReturn(false);
        when(stagingService.discard(candidate)).thenReturn(true);

        new DocumentStorageReconciliationScheduler(referenceLookup, stagingService, metrics).reconcile();

        var ordered = inOrder(referenceLookup, stagingService);
        ordered.verify(referenceLookup).findReferencedPaths(List.of(candidate.toString()));
        ordered.verify(referenceLookup).isReferenced(candidate.toString());
        ordered.verify(stagingService).discard(candidate);
    }

    @Test
    @DisplayName("Preserves a file that becomes referenced after batch resolution")
    void preservesFileReferencedBeforeFinalRecheck() {
        DocumentFileReferenceLookup referenceLookup = mock(DocumentFileReferenceLookup.class);
        DocumentUploadStagingService stagingService = mock(DocumentUploadStagingService.class);
        Path candidate = Path.of("/storage/published/new-reference.pdf");
        streamCandidates(stagingService, List.of(candidate));
        when(referenceLookup.findReferencedPaths(anyCollection())).thenReturn(Set.of());
        when(referenceLookup.isReferenced(candidate.toString())).thenReturn(true);

        new DocumentStorageReconciliationScheduler(referenceLookup, stagingService, metrics).reconcile();

        verify(referenceLookup).isReferenced(candidate.toString());
        verify(stagingService, never()).discard(any());
    }

    @Test
    @DisplayName("Preserves all uncertain files when an authoritative lookup fails")
    void preservesFilesWhenReferenceLookupFails() {
        DocumentFileReferenceLookup referenceLookup = mock(DocumentFileReferenceLookup.class);
        DocumentUploadStagingService stagingService = mock(DocumentUploadStagingService.class);
        Path candidate = Path.of("/storage/published/uncertain.pdf");
        streamCandidates(stagingService, List.of(candidate));
        when(referenceLookup.findReferencedPaths(anyCollection()))
                .thenThrow(new IllegalStateException("database unavailable"));

        new DocumentStorageReconciliationScheduler(referenceLookup, stagingService, metrics).reconcile();

        verify(stagingService, never()).discard(any());
        verify(metrics).recordReconciliationCandidates(1);
        verify(metrics).recordEvent(
                DocumentIngestionMetrics.Stage.RECONCILIATION,
                DocumentIngestionMetrics.Outcome.FAILED,
                DocumentIngestionMetrics.Reason.UNKNOWN);
    }

    @Test
    @DisplayName("Reports a partial reconciliation failure when orphan cleanup is deferred")
    void reportsDeferredOrphanCleanup() {
        DocumentFileReferenceLookup referenceLookup = mock(DocumentFileReferenceLookup.class);
        DocumentUploadStagingService stagingService = mock(DocumentUploadStagingService.class);
        Path candidate = Path.of("/storage/published/deferred.pdf");
        streamCandidates(stagingService, List.of(candidate));
        when(referenceLookup.findReferencedPaths(anyCollection())).thenReturn(Set.of());
        when(referenceLookup.isReferenced(candidate.toString())).thenReturn(false);
        when(stagingService.discard(candidate)).thenReturn(false);

        new DocumentStorageReconciliationScheduler(referenceLookup, stagingService, metrics).reconcile();

        verify(metrics).recordEvent(
                DocumentIngestionMetrics.Stage.RECONCILIATION,
                DocumentIngestionMetrics.Outcome.FAILED,
                DocumentIngestionMetrics.Reason.CLEANUP);
    }

    private void streamCandidates(DocumentUploadStagingService stagingService, List<Path> candidates) {
        doAnswer(invocation -> {
            Consumer<Path> visitor = invocation.getArgument(0);
            candidates.forEach(visitor);
            return null;
        }).when(stagingService).visitExpiredPublishedFiles(any());
    }
}
