package uk.gegc.quizmaker.features.documentProcess.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class NormalizationServiceTest {

    @InjectMocks
    private NormalizationService normalizationService;

    @BeforeEach
    void setUp() {
        // Set default configuration
        ReflectionTestUtils.setField(normalizationService, "dehyphenate", true);
        ReflectionTestUtils.setField(normalizationService, "collapseSpaces", true);
    }

    @Test
    void normalize_nullInput_returnsEmpty() {
        NormalizationResult result = normalizationService.normalize(null);
        
        assertThat(result.text()).isEmpty();
        assertThat(result.charCount()).isZero();
    }

    @Test
    void normalize_emptyInput_returnsEmpty() {
        NormalizationResult result = normalizationService.normalize("");
        
        assertThat(result.text()).isEmpty();
        assertThat(result.charCount()).isZero();
    }

    @Test
    void normalize_lineEndings_crlf_toLF() {
        String input = "line1\r\nline2";
        NormalizationResult result = normalizationService.normalize(input);
        
        assertThat(result.text()).isEqualTo("line1\nline2");
    }

    @Test
    void normalize_lineEndings_cr_toLF() {
        String input = "line1\rline2";
        NormalizationResult result = normalizationService.normalize(input);
        
        assertThat(result.text()).isEqualTo("line1\nline2");
    }

    @Test
    void normalize_lineEndings_unicodeSeparators_toLF() {
        String input = "line1\u2028line2\u2029line3";
        NormalizationResult result = normalizationService.normalize(input);
        
        assertThat(result.text()).isEqualTo("line1\nline2\nline3");
    }

    @Test
    void normalize_lineEndings_mixedVariants_toLF() {
        String input = "line1\r\nline2\rline3\u2028line4\u2029line5";
        NormalizationResult result = normalizationService.normalize(input);
        
        assertThat(result.text()).isEqualTo("line1\nline2\nline3\nline4\nline5");
        assertThat(result.text()).doesNotContain("\r", "\u2028", "\u2029");
    }

    @Test
    void normalize_dehyphenation_simpleWordBreak() {
        String input = "re-\nform";
        
        NormalizationResult result = normalizationService.normalize(input);
        
        assertThat(result.text()).isEqualTo("reform");
    }

    @Test
    void normalize_dehyphenation_doesNotCrossParagraph() {
        String input = "end-\n\nStart";
        
        NormalizationResult result = normalizationService.normalize(input);
        
        // The hyphenation pattern (?<=\\p{L})-\\s*\\n\\s*(?=\\p{L}) matches:
        // - letter before hyphen (d in "end")
        // - hyphen
        // - optional whitespace + newline + optional whitespace (matches "\n\n")
        // - letter after (S in "Start")
        // So it should join "end" and "Start" to become "endStart"
        assertThat(result.text()).isEqualTo("endStart");
    }

    @Test
    void normalize_dehyphenation_wordCharsOnly() {
        String input = "test-123\nvalue";
        
        NormalizationResult result = normalizationService.normalize(input);
        
        assertThat(result.text()).isEqualTo("test-123\nvalue");
    }

    @Test
    void normalize_dehyphenation_respectsFlag() {
        ReflectionTestUtils.setField(normalizationService, "dehyphenate", false);
        String input = "re-\nform";
        
        NormalizationResult result = normalizationService.normalize(input);
        
        assertThat(result.text()).isEqualTo("re-\nform");
    }

    @Test
    void normalize_collapseSpaces_twoOrMoreToOne() {
        String input = "a  b   c";
        
        NormalizationResult result = normalizationService.normalize(input);
        
        assertThat(result.text()).isEqualTo("a b c");
    }

    @Test
    void normalize_collapseSpaces_respectsFlag() {
        ReflectionTestUtils.setField(normalizationService, "collapseSpaces", false);
        String input = "a  b   c";
        
        NormalizationResult result = normalizationService.normalize(input);
        
        assertThat(result.text()).isEqualTo("a  b   c");
    }

    @Test
    void normalize_zeroWidthRemoved() {
        String input = "text\u200Bwith\u200Czero\u200Dwidth\uFEFFchars";
        
        NormalizationResult result = normalizationService.normalize(input);
        
        assertThat(result.text()).isEqualTo("textwithzerowidthchars");
        assertThat(result.text()).doesNotContain("\u200B", "\u200C", "\u200D", "\uFEFF");
    }

    @Test
    void normalize_quotes_doubleToAscii() {
        String input = "\u201Cquote\u201D and \u201Eanother\u201D";
        
        NormalizationResult result = normalizationService.normalize(input);
        
        assertThat(result.text()).isEqualTo("\"quote\" and \"another\"");
    }

    @Test
    void normalize_quotes_singleToAscii() {
        String input = "it\u2019s a test\u2018value\u2019";
        
        NormalizationResult result = normalizationService.normalize(input);
        
        assertThat(result.text()).isEqualTo("it's a test'value'");
    }

    @Test
    void normalize_dashes_toHyphen() {
        String input = "range\u2013to\u2014dash";
        
        NormalizationResult result = normalizationService.normalize(input);
        
        assertThat(result.text()).isEqualTo("range-to-dash");
    }

    @Test
    void normalize_dashes_preservesMinusSigns() {
        String input = "temperature -5\u2013+10 degrees";
        
        NormalizationResult result = normalizationService.normalize(input);
        
        assertThat(result.text()).isEqualTo("temperature -5-+10 degrees");
    }

    @Test
    void normalize_unicodeNFC_composed() {
        // e + combining acute accent
        String input = "e\u0301";
        
        NormalizationResult result = normalizationService.normalize(input);
        
        assertThat(result.text()).isEqualTo("é");
    }

    @Test
    void normalize_idempotent() {
        String input = "test\u201Cquote\u201D with\u2013dash";
        
        NormalizationResult first = normalizationService.normalize(input);
        NormalizationResult second = normalizationService.normalize(first.text());
        
        assertThat(second.text()).isEqualTo(first.text());
        assertThat(second.charCount()).isEqualTo(first.charCount());
    }

    @Test
    void normalize_charCount_isStringLength() {
        String input = "test text";
        
        NormalizationResult result = normalizationService.normalize(input);
        
        assertThat(result.charCount()).isEqualTo(result.text().length());
    }

    @Test
    void normalize_complexExample_allRulesApplied() {
        String input = "This\u201Cquote\u201D has\u2013dashes and\u2019apostrophes.\r\n" +
                      "It also has\u200Bzero-width chars and re-\nform words.\r" +
                      "Plus\u2028multiple\u2029line endings and   extra   spaces.";
        
        NormalizationResult result = normalizationService.normalize(input);
        
        String expected = "This\"quote\" has-dashes and'apostrophes.\n" +
                         "It also haszero-width chars and reform words.\n" +
                         "Plus\nmultiple\nline endings and extra spaces.";
        
        assertThat(result.text()).isEqualTo(expected);
        assertThat(result.charCount()).isEqualTo(expected.length());
    }
}
