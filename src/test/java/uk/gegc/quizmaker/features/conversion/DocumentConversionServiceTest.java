package uk.gegc.quizmaker.features.conversion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.conversion.application.DocumentConversionService;
import uk.gegc.quizmaker.features.conversion.application.MimeTypeDetector;
import uk.gegc.quizmaker.features.conversion.domain.ConversionException;
import uk.gegc.quizmaker.features.conversion.domain.ConversionResult;
import uk.gegc.quizmaker.features.conversion.domain.DocumentConverter;
import uk.gegc.quizmaker.features.conversion.domain.UnsupportedFormatException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentConversionServiceTest {

    @Mock
    private DocumentConverter txtConverter;
    
    @Mock
    private DocumentConverter pdfConverter;
    
    @Mock
    private MimeTypeDetector mimeTypeDetector;

    private DocumentConversionService service;

    @BeforeEach
    void setUp() {
        service = new DocumentConversionService(List.of(txtConverter, pdfConverter), mimeTypeDetector);
    }

    @Test
    void convert_picksConverterByFilename_whenSupported() throws ConversionException {
        // Given
        when(txtConverter.supports("file.txt")).thenReturn(true);
        when(txtConverter.convert(any(byte[].class)))
                .thenReturn(new ConversionResult("Converted text"));
        
        // When
        ConversionResult result = service.convert("file.txt", "test content".getBytes());
        
        // Then
        assertThat(result.text()).isEqualTo("Converted text");
    }

    @Test
    void convert_fallsBackToMime_whenFilenameUnknown() throws ConversionException {
        // Given
        when(txtConverter.supports("file.txt")).thenReturn(false);
        when(mimeTypeDetector.detectMimeType("file.txt")).thenReturn("text/plain");
        when(txtConverter.supports("text/plain")).thenReturn(true);
        when(txtConverter.convert(any(byte[].class)))
                .thenReturn(new ConversionResult("Converted text"));
        
        // When
        ConversionResult result = service.convert("file.txt", "test content".getBytes());
        
        // Then
        assertThat(result.text()).isEqualTo("Converted text");
    }

    @Test
    void convert_noConverter_throwsUnsupportedFormatException() {
        // Given
        when(txtConverter.supports("file.xyz")).thenReturn(false);
        when(pdfConverter.supports("file.xyz")).thenReturn(false);
        when(mimeTypeDetector.detectMimeType("file.xyz")).thenReturn("application/octet-stream");
        when(txtConverter.supports("application/octet-stream")).thenReturn(false);
        when(pdfConverter.supports("application/octet-stream")).thenReturn(false);
        
        // When & Then
        assertThatThrownBy(() -> service.convert("file.xyz", "test content".getBytes()))
                .isInstanceOf(UnsupportedFormatException.class)
                .hasMessageContaining("No suitable converter found for: file.xyz");
    }

    @Test
    void convert_noConverterWithNullMime_throwsUnsupportedFormatException() {
        // Given
        when(txtConverter.supports("file.xyz")).thenReturn(false);
        when(pdfConverter.supports("file.xyz")).thenReturn(false);
        when(mimeTypeDetector.detectMimeType("file.xyz")).thenReturn(null);
        
        // When & Then
        assertThatThrownBy(() -> service.convert("file.xyz", "test content".getBytes()))
                .isInstanceOf(UnsupportedFormatException.class)
                .hasMessageContaining("No suitable converter found for: file.xyz");
    }

    @Test
    void convert_converterThrowsException_propagatesConversionException() throws ConversionException {
        // Given
        when(txtConverter.supports("file.txt")).thenReturn(true);
        when(txtConverter.convert(any(byte[].class)))
                .thenThrow(new ConversionException("Conversion failed"));
        
        // When & Then
        assertThatThrownBy(() -> service.convert("file.txt", "test content".getBytes()))
                .isInstanceOf(ConversionException.class)
                .hasMessageContaining("Conversion failed");
    }

    @Test
    void convert_multipleConverters_picksFirstSupported() throws ConversionException {
        // Given
        when(txtConverter.supports("file.txt")).thenReturn(true);
        when(txtConverter.convert(any(byte[].class)))
                .thenReturn(new ConversionResult("First converter result"));
        
        // When
        ConversionResult result = service.convert("file.txt", "test content".getBytes());
        
        // Then - Should pick the first converter in the list
        assertThat(result.text()).isEqualTo("First converter result");
    }

    @Test
    void convert_mimeFallbackWorks_whenFilenameNotSupported() throws ConversionException {
        // Given
        when(txtConverter.supports("file.txt")).thenReturn(false);
        when(mimeTypeDetector.detectMimeType("file.txt")).thenReturn("text/plain");
        when(txtConverter.supports("text/plain")).thenReturn(true);
        when(txtConverter.convert(any(byte[].class)))
                .thenReturn(new ConversionResult("MIME fallback result"));
        
        // When
        ConversionResult result = service.convert("file.txt", "test content".getBytes());
        
        // Then
        assertThat(result.text()).isEqualTo("MIME fallback result");
    }

    @Test
    void convert_emptyConvertersList_throwsUnsupportedFormatException() {
        // Given
        DocumentConversionService emptyService = new DocumentConversionService(List.of(), mimeTypeDetector);
        
        // When & Then
        assertThatThrownBy(() -> emptyService.convert("file.txt", "test content".getBytes()))
                .isInstanceOf(UnsupportedFormatException.class)
                .hasMessageContaining("No suitable converter found for: file.txt");
    }
}
