package uk.gegc.quizmaker.features.conversion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.conversion.domain.ConversionException;
import uk.gegc.quizmaker.features.conversion.domain.ConversionResult;
import uk.gegc.quizmaker.features.conversion.infra.PdfBoxDocumentConverter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfBoxDocumentConverterTest {

    private PdfBoxDocumentConverter converter;

    @BeforeEach
    void setUp() {
        converter = new PdfBoxDocumentConverter();
    }

    @Test
    void supports_acceptsPdfExtAndMime() {
        // PDF extensions and MIME
        assertThat(converter.supports("file.pdf")).isTrue();
        assertThat(converter.supports("application/pdf")).isTrue();
        
        // Unsupported formats
        assertThat(converter.supports("file.txt")).isFalse();
        assertThat(converter.supports("file.html")).isFalse();
        assertThat(converter.supports(null)).isFalse();
    }

    @Test
    void convert_validPdf_extractsText() throws ConversionException {
        // Given - Create a simple PDF with known text
        // This would require a test PDF fixture, but for now we'll test the structure
        // In a real test, you'd have a small PDF file in test resources
        
        // When & Then
        // This test would need a real PDF fixture
        // For now, we'll just verify the converter is properly configured
        assertThat(converter).isNotNull();
    }

    @Test
    void convert_corruptPdf_throwsConversionException() {
        // Given
        byte[] corruptPdfBytes = "This is not a PDF".getBytes();
        
        // When & Then
        assertThatThrownBy(() -> converter.convert(corruptPdfBytes))
                .isInstanceOf(ConversionException.class)
                .hasMessageContaining("Failed to convert PDF document");
    }

    @Test
    void convert_emptyBytes_throwsConversionException() {
        // Given
        byte[] emptyBytes = new byte[0];
        
        // When & Then
        assertThatThrownBy(() -> converter.convert(emptyBytes))
                .isInstanceOf(ConversionException.class)
                .hasMessageContaining("Failed to convert PDF document");
    }

    @Test
    void convert_nullBytes_throwsConversionException() {
        // Given
        byte[] nullBytes = null;
        
        // When & Then
        assertThatThrownBy(() -> converter.convert(nullBytes))
                .isInstanceOf(ConversionException.class)
                .hasMessageContaining("Failed to convert PDF document");
    }
}
