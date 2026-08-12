package uk.gegc.quizmaker.features.document.application.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import uk.gegc.quizmaker.features.document.application.DocumentIngestionMetrics;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingLimits;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("Document upload validation compatibility")
class DocumentValidationServiceImplTest {

    private final DocumentIngestionMetrics metrics = mock(DocumentIngestionMetrics.class);
    private final DocumentValidationServiceImpl validationService =
            new DocumentValidationServiceImpl(
                    DocumentProcessingLimits.defaults(),
                    metrics);

    @Test
    @DisplayName("Preserves the legacy 100-character minimum for document-only uploads")
    void documentOnlyUploadStillAcceptsLegacyMinimum() {
        assertThatCode(() -> validationService.validateFileUpload(validFile(), null, 100))
                .doesNotThrowAnyException();
        verify(metrics).recordEvent(
                DocumentIngestionMetrics.Stage.VALIDATION,
                DocumentIngestionMetrics.Outcome.ACCEPTED,
                DocumentIngestionMetrics.Reason.NONE);
    }

    @Test
    @DisplayName("Continues rejecting document-only uploads below the legacy minimum")
    void documentOnlyUploadRejectsBelowLegacyMinimum() {
        assertThatThrownBy(() -> validationService.validateFileUpload(validFile(), null, 99))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 100 and 100000");
        verify(metrics).recordEvent(
                DocumentIngestionMetrics.Stage.VALIDATION,
                DocumentIngestionMetrics.Outcome.REJECTED,
                DocumentIngestionMetrics.Reason.INVALID_INPUT);
    }

    private MockMultipartFile validFile() {
        return new MockMultipartFile("file", "notes.txt", "text/plain", new byte[]{0x41});
    }
}
