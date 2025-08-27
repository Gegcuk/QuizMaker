package uk.gegc.quizmaker.features.conversion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.conversion.domain.ConversionException;
import uk.gegc.quizmaker.features.conversion.domain.ConversionResult;
import uk.gegc.quizmaker.features.conversion.infra.SrtVttDocumentConverter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SrtVttDocumentConverterTest {

    private SrtVttDocumentConverter converter;

    @BeforeEach
    void setUp() {
        converter = new SrtVttDocumentConverter();
    }

    @Test
    void supports_acceptsSrtAndVttExtensionsAndMimes() {
        // SRT extensions and MIME
        assertThat(converter.supports("file.srt")).isTrue();
        assertThat(converter.supports("application/x-subrip")).isTrue();
        
        // VTT extensions and MIME
        assertThat(converter.supports("file.vtt")).isTrue();
        assertThat(converter.supports("text/vtt")).isTrue();
        
        // Unsupported formats
        assertThat(converter.supports("file.txt")).isFalse();
        assertThat(converter.supports("file.pdf")).isFalse();
        assertThat(converter.supports(null)).isFalse();
    }

    @Test
    void convert_srt_basic_skipsSeqAndTimestamps_andJoinsText() throws ConversionException {
        // Given
        String srtContent = """
            1
            00:00:01,000 --> 00:00:02,000
            Hello
            
            2
            00:00:02,500 --> 00:00:03,000
            world!
            """;
        
        // When
        ConversionResult result = converter.convert(srtContent.getBytes());
        
        // Then
        assertThat(result.text()).isEqualTo("Hello world!");
    }

    @Test
    void convert_vtt_basic_skipsHeaderAndTimestamps() throws ConversionException {
        // Given
        String vttContent = """
            WEBVTT
            
            1
            00:00:01.000 --> 00:00:02.000
            Hello world!
            """;
        
        // When
        ConversionResult result = converter.convert(vttContent.getBytes());
        
        // Then
        assertThat(result.text()).isEqualTo("Hello world!");
    }

    @Test
    void convert_ignores_NOTE_and_STYLE_lines() throws ConversionException {
        // Given
        String vttContent = """
            WEBVTT
            
            NOTE This is a test file
            
            1
            00:00:01.000 --> 00:00:02.000
            Hello
            
            STYLE ::cue {
              color: yellow;
            }
            
            2
            00:00:02.000 --> 00:00:03.000
            world!
            """;
        
        // When
        ConversionResult result = converter.convert(vttContent.getBytes());
        
        // Then
        assertThat(result.text()).isEqualTo("Hello world!");
    }

    @Test
    void convert_preservesUnicodeCharacters() throws ConversionException {
        // Given
        String srtContent = """
            1
            00:00:01,000 --> 00:00:02,000
            café
            
            2
            00:00:02,000 --> 00:00:03,000
            🌍 world!
            """;
        
        // When
        ConversionResult result = converter.convert(srtContent.getBytes());
        
        // Then
        assertThat(result.text()).isEqualTo("café 🌍 world!");
    }

    @Test
    void convert_collapsesWhitespace_multipleSpacesToSingle() throws ConversionException {
        // Given
        String srtContent = """
            1
            00:00:01,000 --> 00:00:02,000
            Hello     world!
            
            2
            00:00:02,000 --> 00:00:03,000
            Multiple    spaces    here
            """;
        
        // When
        ConversionResult result = converter.convert(srtContent.getBytes());
        
        // Then
        assertThat(result.text()).isEqualTo("Hello world! Multiple spaces here");
    }

    @Test
    void supports_acceptsApplicationXSubrip() {
        // Given & When & Then
        assertThat(converter.supports("application/x-subrip")).isTrue();
    }

    @Test
    void convert_robustHandlingOfVariousContent() throws ConversionException {
        // Given - Test that the converter handles various content gracefully
        String mixedContent = """
            WEBVTT
            
            NOTE This is a note
            
            1
            00:00:01.000 --> 00:00:02.000
            Hello world!
            
            STYLE ::cue {
              color: yellow;
            }
            
            2
            00:00:02.000 --> 00:00:03.000
            More content here
            """;
        
        // When
        ConversionResult result = converter.convert(mixedContent.getBytes());
        
        // Then - Should extract text content while filtering out metadata
        assertThat(result.text()).isEqualTo("Hello world! More content here");
    }
}
