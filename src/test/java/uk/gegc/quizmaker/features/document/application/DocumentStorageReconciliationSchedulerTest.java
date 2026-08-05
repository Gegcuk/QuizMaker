package uk.gegc.quizmaker.features.document.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;

import java.util.Collection;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Document storage reconciliation scheduler")
class DocumentStorageReconciliationSchedulerTest {

    @Test
    @DisplayName("Collects referenced document paths page by page before reconciling storage")
    void reconcilesUsingPagedDocumentPaths() {
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        DocumentUploadStagingService stagingService = mock(DocumentUploadStagingService.class);
        List<Document> firstPageDocuments = IntStream.range(0, 250)
                .mapToObj(index -> document("/storage/published/first-" + index + ".pdf"))
                .toList();
        Document second = document("/storage/published/second.txt");
        PageRequest firstPage = PageRequest.of(0, 250);
        PageRequest secondPage = PageRequest.of(1, 250);
        when(documentRepository.findAll(firstPage))
                .thenReturn(new PageImpl<>(firstPageDocuments, firstPage, 251));
        when(documentRepository.findAll(secondPage))
                .thenReturn(new PageImpl<>(List.of(second), secondPage, 251));

        new DocumentStorageReconciliationScheduler(documentRepository, stagingService).reconcile();

        ArgumentCaptor<Collection<String>> paths = ArgumentCaptor.forClass(Collection.class);
        verify(stagingService).reconcile(paths.capture());
        assertThat(paths.getValue()).hasSize(251);
        assertThat(paths.getValue()).contains(firstPageDocuments.get(0).getFilePath(), second.getFilePath());
    }

    private Document document(String filePath) {
        Document document = new Document();
        document.setFilePath(filePath);
        return document;
    }
}
