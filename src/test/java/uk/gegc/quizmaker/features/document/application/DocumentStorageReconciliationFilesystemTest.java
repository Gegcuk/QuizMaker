package uk.gegc.quizmaker.features.document.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gegc.quizmaker.features.document.application.impl.LocalDocumentUploadStagingService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Document storage reconciliation on the local filesystem")
class DocumentStorageReconciliationFilesystemTest {

    @TempDir
    Path storageRoot;

    @Test
    @DisplayName("Deletes expired orphans while preserving live and retention-protected files")
    void deletesOnlyExpiredUnreferencedFiles() throws IOException {
        DocumentProcessingLimits limits = limits();
        Path expiredOrphan = publishedFile("expired-orphan.pdf", true, limits);
        Path referenced = publishedFile("referenced.pdf", true, limits);
        Path freshOrphan = publishedFile("fresh-orphan.pdf", false, limits);
        DocumentFileReferenceLookup referenceLookup = referenceLookup(Set.of(referencePath(referenced)));
        var stagingService = new LocalDocumentUploadStagingService(limits);

        new DocumentStorageReconciliationScheduler(referenceLookup, stagingService).reconcile();

        assertThat(expiredOrphan).doesNotExist();
        assertThat(referenced).exists();
        assertThat(freshOrphan).exists();
    }

    @Test
    @DisplayName("Concurrent reconcilers delete one orphan idempotently without failing")
    void concurrentReconcilersDeleteOrphanIdempotently() throws Exception {
        DocumentProcessingLimits limits = limits();
        Path orphan = publishedFile("duplicate-scan.pdf", true, limits);
        DocumentFileReferenceLookup referenceLookup = referenceLookup(Set.of());
        var stagingService = new LocalDocumentUploadStagingService(limits);
        var firstScheduler = new DocumentStorageReconciliationScheduler(referenceLookup, stagingService);
        var secondScheduler = new DocumentStorageReconciliationScheduler(referenceLookup, stagingService);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> runAfter(start, firstScheduler));
            Future<?> second = executor.submit(() -> runAfter(start, secondScheduler));
            start.countDown();

            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(orphan).doesNotExist();
    }

    private void runAfter(CountDownLatch start, DocumentStorageReconciliationScheduler scheduler) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Reconciliation start barrier timed out");
            }
            scheduler.reconcile();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Reconciliation test was interrupted", e);
        }
    }

    private DocumentFileReferenceLookup referenceLookup(Set<String> referencedPaths) {
        return new DocumentFileReferenceLookup() {
            @Override
            public Set<String> findReferencedPaths(Collection<String> candidatePaths) {
                Set<String> matches = new HashSet<>(candidatePaths);
                matches.retainAll(referencedPaths);
                return matches;
            }

            @Override
            public boolean isReferenced(String candidatePath) {
                return referencedPaths.contains(candidatePath);
            }
        };
    }

    private Path publishedFile(String filename, boolean expired, DocumentProcessingLimits limits) throws IOException {
        Path publishedDirectory = Files.createDirectories(storageRoot.resolve("published"));
        Path file = Files.writeString(publishedDirectory.resolve(filename), "fixture");
        if (expired) {
            Files.setLastModifiedTime(file, FileTime.from(
                    Instant.now().minus(limits.getStagingRetention()).minusSeconds(1)));
        }
        return file;
    }

    private String referencePath(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private DocumentProcessingLimits limits() {
        DocumentProcessingLimits limits = DocumentProcessingLimits.defaults();
        limits.setStorageRoot(storageRoot.toString());
        return limits;
    }
}
