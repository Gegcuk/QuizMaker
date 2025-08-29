package uk.gegc.quizmaker.features.conversion.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.conversion.domain.ConversionException;
import uk.gegc.quizmaker.features.conversion.domain.ConversionResult;
import uk.gegc.quizmaker.features.conversion.domain.DocumentConverter;
import uk.gegc.quizmaker.features.conversion.domain.UnsupportedFormatException;
import uk.gegc.quizmaker.features.conversion.infra.TxtPassthroughConverter;
import uk.gegc.quizmaker.features.conversion.application.MimeTypeDetector;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NormalizedDocumentConversionServiceTest {

    @Mock
    private DocumentConverter mockConverter;
    
    @Mock
    private MimeTypeDetector mimeTypeDetector;

    private TxtPassthroughConverter txtConverter;
    private DocumentConversionService conversionService;

    @BeforeEach
    void setUp() {
        txtConverter = new TxtPassthroughConverter();
        conversionService = new DocumentConversionService(List.of(txtConverter), mimeTypeDetector);
    }

    @Test
    void convert_picksTxtConverterByExtension() throws Exception {
        String text = "Hello, World!";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        
        ConversionResult result = conversionService.convert("document.txt", bytes);
        
        assertThat(result.text()).isEqualTo(text);
    }

    @Test
    void convert_picksTxtConverterByMime() throws Exception {
        String text = "Hello, World!";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        
        ConversionResult result = conversionService.convert("text/plain", bytes);
        
        assertThat(result.text()).isEqualTo(text);
    }

    @Test
    void convert_caseInsensitiveExtension() throws Exception {
        String text = "Hello, World!";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        
        ConversionResult result = conversionService.convert("document.TXT", bytes);
        
        assertThat(result.text()).isEqualTo(text);
    }

    @Test
    void convert_throwsWhenNoConverterFound() {
        byte[] bytes = "test".getBytes(StandardCharsets.UTF_8);
        
        assertThatThrownBy(() -> conversionService.convert("document.pdf", bytes))
                .isInstanceOf(UnsupportedFormatException.class)
                .hasMessageContaining("No suitable converter found for: document.pdf");
    }

    @Test
    void convert_throwsWhenNullFilename() {
        byte[] bytes = "test".getBytes(StandardCharsets.UTF_8);
        
        assertThatThrownBy(() -> conversionService.convert(null, bytes))
                .isInstanceOf(UnsupportedFormatException.class)
                .hasMessageContaining("No suitable converter found for: null");
    }

    @Test
    void isSupported_trueForTxt_falseForUnknown() {
        assertThat(conversionService.isSupported("document.txt")).isTrue();
        assertThat(conversionService.isSupported("text/plain")).isTrue();
        assertThat(conversionService.isSupported("document.TXT")).isTrue();
        
        assertThat(conversionService.isSupported("document.pdf")).isFalse();
        assertThat(conversionService.isSupported("text/html")).isFalse();
        assertThat(conversionService.isSupported(null)).isFalse();
    }

    @Test
    void convert_withMultipleConverters_picksFirstMatch() throws Exception {
        // Setup mock converter that supports PDF
        when(mockConverter.supports("document.pdf")).thenReturn(true);
        when(mockConverter.convert("test".getBytes(StandardCharsets.UTF_8)))
                .thenReturn(new ConversionResult("converted text"));
        
        DocumentConversionService serviceWithMultipleConverters = 
                new DocumentConversionService(List.of(txtConverter, mockConverter), mimeTypeDetector);
        
        ConversionResult result = serviceWithMultipleConverters.convert("document.pdf", 
                "test".getBytes(StandardCharsets.UTF_8));
        
        assertThat(result.text()).isEqualTo("converted text");
    }

    @Test
    void convert_converterThrowsException_propagatesException() throws Exception {
        // Setup mock converter that throws exception
        when(mockConverter.supports("document.pdf")).thenReturn(true);
        when(mockConverter.convert("test".getBytes(StandardCharsets.UTF_8)))
                .thenThrow(new ConversionException("Conversion failed"));
        
        DocumentConversionService serviceWithMockConverter = 
                new DocumentConversionService(List.of(mockConverter), mimeTypeDetector);
        
        assertThatThrownBy(() -> serviceWithMockConverter.convert("document.pdf", 
                "test".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ConversionException.class)
                .hasMessage("Conversion failed");
    }
}
