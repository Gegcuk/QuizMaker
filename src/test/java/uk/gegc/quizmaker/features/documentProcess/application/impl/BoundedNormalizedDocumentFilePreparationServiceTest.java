package uk.gegc.quizmaker.features.documentProcess.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;
import uk.gegc.quizmaker.features.document.application.ConvertedDocument;
import uk.gegc.quizmaker.features.document.application.DocumentIngestionMetrics;
import uk.gegc.quizmaker.features.document.application.DocumentParseExecutor;
import uk.gegc.quizmaker.features.document.application.DocumentParseRequest;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingLimits;
import uk.gegc.quizmaker.features.document.application.DocumentUploadStagingService;
import uk.gegc.quizmaker.features.document.application.StagedDocumentUpload;
import uk.gegc.quizmaker.features.documentProcess.application.NormalizationResult;
import uk.gegc.quizmaker.features.documentProcess.application.NormalizationService;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;
import uk.gegc.quizmaker.shared.exception.DocumentProcessingTimeoutException;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Bounded normalized-document file preparation")
class BoundedNormalizedDocumentFilePreparationServiceTest {

    @Mock private DocumentUploadStagingService stagingService;
    @Mock private DocumentParseExecutor parseExecutor;
    @Mock private NormalizationService normalizationService;
    @Mock private DocumentIngestionMetrics metrics;

    private DocumentProcessingLimits limits;
    private BoundedNormalizedDocumentFilePreparationService service;
    private MultipartFile file;
    private StagedDocumentUpload upload;

    @BeforeEach
    void setUp() {
        limits = DocumentProcessingLimits.defaults();
        service = new BoundedNormalizedDocumentFilePreparationService(
                stagingService, parseExecutor, normalizationService, limits, metrics);
        file = org.mockito.Mockito.mock(MultipartFile.class);
        upload = new StagedDocumentUpload(Path.of("staged.upload"), "notes.txt", "text/plain", 12);
    }

    @Test
    @DisplayName("Streams through the shared parser and returns an unowned document for publication")
    void preparesThroughSharedParserWithoutReadingMultipartIntoMemory() throws Exception {
        stageUpload();
        ConvertedDocument converted = converted("raw text", 1);
        when(parseExecutor.execute(org.mockito.ArgumentMatchers.eq("owner-id"), any())).thenReturn(converted);
        when(normalizationService.normalize("raw text")).thenReturn(new NormalizationResult("normalized", 10));

        NormalizedDocument result = service.prepare("owner-id", "notes.txt", file);

        assertThat(result.getOwner()).isNull();
        assertThat(result.getOriginalName()).isEqualTo("notes.txt");
        assertThat(result.getMime()).isEqualTo("text/plain");
        assertThat(result.getNormalizedText()).isEqualTo("normalized");
        assertThat(result.getStatus()).isEqualTo(NormalizedDocument.DocumentStatus.NORMALIZED);
        ArgumentCaptor<DocumentParseRequest> request = ArgumentCaptor.forClass(DocumentParseRequest.class);
        verify(parseExecutor).execute(org.mockito.ArgumentMatchers.eq("owner-id"), request.capture());
        assertThat(request.getValue().sourcePath()).isEqualTo(upload.stagingPath().toAbsolutePath());
        verify(file, never()).getBytes();
        verify(stagingService).discard(upload.stagingPath());
    }

    @Test
    @DisplayName("Keeps the parser failure when best-effort staging cleanup also fails")
    void parserFailureIsNotMaskedByCleanupFailure() {
        stageUpload();
        DocumentProcessingTimeoutException timeout = new DocumentProcessingTimeoutException();
        when(parseExecutor.execute(org.mockito.ArgumentMatchers.eq("owner-id"), any())).thenThrow(timeout);
        when(stagingService.discard(upload.stagingPath())).thenThrow(new IllegalStateException("cleanup unavailable"));

        assertThatThrownBy(() -> service.prepare("owner-id", "notes.txt", file))
                .isSameAs(timeout);

        verifyNoInteractions(normalizationService);
    }

    private ConvertedDocument converted(String text, int pages) {
        ConvertedDocument converted = new ConvertedDocument();
        converted.setFullContent(text);
        converted.setTotalPages(pages);
        return converted;
    }

    private void stageUpload() {
        when(stagingService.stage(file, "notes.txt")).thenReturn(upload);
    }
}
