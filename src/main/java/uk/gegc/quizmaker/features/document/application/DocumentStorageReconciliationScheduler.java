package uk.gegc.quizmaker.features.document.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;

import java.util.ArrayList;
import java.util.List;

/** Removes expired staging files and published files not referenced by a document row. */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentStorageReconciliationScheduler {

    private static final int RECONCILIATION_PAGE_SIZE = 250;

    private final DocumentRepository documentRepository;
    private final DocumentUploadStagingService uploadStagingService;

    @Scheduled(fixedDelayString = "${quizmaker.document.processing.reconciliation-interval:PT1H}")
    public void reconcile() {
        try {
            uploadStagingService.reconcile(findReferencedFilePaths());
        } catch (RuntimeException e) {
            // Storage maintenance is best effort and must never affect uploads.
            log.warn("Document storage reconciliation did not complete", e);
        }
    }

    private List<String> findReferencedFilePaths() {
        List<String> filePaths = new ArrayList<>();
        Page<Document> page;
        int pageNumber = 0;
        do {
            page = documentRepository.findAll(PageRequest.of(pageNumber++, RECONCILIATION_PAGE_SIZE));
            page.map(Document::getFilePath).forEach(filePaths::add);
        } while (page.hasNext());
        return filePaths;
    }
}
