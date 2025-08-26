package uk.gegc.quizmaker.features.document.infra.text;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SentenceBoundaryDetectorFindNextTest {

    private SentenceBoundaryDetector detector;

    @BeforeEach
    void setUp() {
        detector = new SentenceBoundaryDetector();
    }

    // ===== BASIC PUNCTUATION TESTS =====

    @Test
    void findNextSentenceEnd_basicPunctuation_period() {
        // Given
        String text = "This is a sentence. This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(19); // Position after "This is a sentence."
    }

    @Test
    void findNextSentenceEnd_basicPunctuation_exclamation() {
        // Given
        String text = "This is exciting! This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(17); // Position after "This is exciting!"
    }

    @Test
    void findNextSentenceEnd_basicPunctuation_question() {
        // Given
        String text = "What is this? This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(13); // Position after "What is this?"
    }

    @Test
    void findNextSentenceEnd_basicPunctuation_mixed() {
        // Given
        String text = "Hello world! How are you? I'm fine. This is a test.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(12); // Position after "Hello world!"
    }

    // ===== ABBREVIATIONS TESTS =====

    @Test
    void findNextSentenceEnd_abbreviations_ignoresDr() {
        // Given
        String text = "Dr. Smith went to the store. This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(28); // Position after "Dr. Smith went to the store."
    }

    @Test
    void findNextSentenceEnd_abbreviations_ignoresMr() {
        // Given
        String text = "Mr. Jones was there. This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(46); // Position after "Mr. Jones was there."
    }

    @Test
    void findNextSentenceEnd_abbreviations_ignoresMrs() {
        // Given
        String text = "Mrs. Brown arrived. This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(45); // Position after "Mrs. Brown arrived."
    }

    @Test
    void findNextSentenceEnd_abbreviations_ignoresMs() {
        // Given
        String text = "Ms. Davis left. This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(41); // Position after "Ms. Davis left."
    }

    @Test
    void findNextSentenceEnd_abbreviations_ignoresProf() {
        // Given
        String text = "Prof. Wilson taught. This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(46); // Position after "Prof. Wilson taught."
    }

    @Test
    void findNextSentenceEnd_abbreviations_ignoresInc() {
        // Given
        String text = "Acme Inc. is a company. This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(49); // Position after "Acme Inc. is a company."
    }

    @Test
    void findNextSentenceEnd_abbreviations_ignoresLtd() {
        // Given
        String text = "Tech Ltd. is private. This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(47); // Position after "Tech Ltd. is private."
    }

    @Test
    void findNextSentenceEnd_abbreviations_ignoresCorp() {
        // Given
        String text = "Big Corp. is public. This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(46); // Position after "Big Corp. is public."
    }

    @Test
    void findNextSentenceEnd_abbreviations_ignoresCo() {
        // Given
        String text = "Small Co. is local. This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(45); // Position after "Small Co. is local."
    }

    @Test
    void findNextSentenceEnd_abbreviations_ignoresVs() {
        // Given
        String text = "Team A vs. Team B won. This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(48); // Position after "Team A vs. Team B won."
    }

    @Test
    void findNextSentenceEnd_abbreviations_ignoresEtc() {
        // Given
        String text = "We need milk, bread, etc. This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(51); // Position after "We need milk, bread, etc."
    }

    @Test
    void findNextSentenceEnd_abbreviations_ignoresIe() {
        // Given
        String text = "Use i.e. for examples. This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(48); // Position after "Use i.e. for examples."
    }

    @Test
    void findNextSentenceEnd_abbreviations_ignoresEg() {
        // Given
        String text = "Use e.g. for examples. This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(48); // Position after "Use e.g. for examples."
    }

    @Test
    void findNextSentenceEnd_abbreviations_ignoresAm() {
        // Given
        String text = "Meet at 9 a.m. tomorrow. This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(50); // Position after "Meet at 9 a.m. tomorrow."
    }

    @Test
    void findNextSentenceEnd_abbreviations_ignoresPm() {
        // Given
        String text = "Meet at 3 p.m. today. This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(47); // Position after "Meet at 3 p.m. today."
    }

    @Test
    void findNextSentenceEnd_abbreviations_ignoresUS() {
        // Given
        String text = "U.S. is a country. This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(44); // Position after "U.S. is a country."
    }

    @Test
    void findNextSentenceEnd_abbreviations_ignoresUK() {
        // Given
        String text = "U.K. is a country. This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(44); // Position after "U.K. is a country."
    }

    @Test
    void findNextSentenceEnd_abbreviations_ignoresPhD() {
        // Given
        String text = "He has a Ph.D. degree. This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(48); // Position after "He has a Ph.D. degree."
    }

    @Test
    void findNextSentenceEnd_abbreviations_ignoresMA() {
        // Given
        String text = "She has an M.A. degree. This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(49); // Position after "She has an M.A. degree."
    }

    @Test
    void findNextSentenceEnd_abbreviations_ignoresBA() {
        // Given
        String text = "He has a B.A. degree. This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(47); // Position after "He has a B.A. degree."
    }

    @Test
    void findNextSentenceEnd_abbreviations_multipleInSentence() {
        // Given
        String text = "Dr. Smith and Mr. Jones went to U.S. Inc. headquarters. This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(81); // Position after the first sentence, ignoring all abbreviations
    }

    // ===== DECIMALS TESTS =====

    @Test
    void findNextSentenceEnd_decimals_ignoresSimpleDecimal() {
        // Given
        String text = "The value is 3.14. This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(44); // Position after "The value is 3.14."
    }

    @Test
    void findNextSentenceEnd_decimals_ignoresMultipleDecimals() {
        // Given
        String text = "Values are 1.5, 2.7, and 3.14. This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(56); // Position after "Values are 1.5, 2.7, and 3.14."
    }

    @Test
    void findNextSentenceEnd_decimals_ignoresComplexDecimal() {
        // Given
        String text = "The value is 3.14159. This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(47); // Position after "The value is 3.14159."
    }

    @Test
    void findNextSentenceEnd_decimals_ignoresDecimalWithCurrency() {
        // Given
        String text = "The price is $12.50. This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(46); // Position after "The price is $12.50."
    }

    @Test
    void findNextSentenceEnd_decimals_ignoresDecimalAtEnd() {
        // Given
        String text = "The value is 3.14. This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(44); // Position after "The value is 3.14."
    }

    // ===== ELLIPSIS TESTS =====

    @Test
    void findNextSentenceEnd_ellipsis_ignoresThreeDots() {
        // Given
        String text = "The story continues... This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(48); // Position after "The story continues... This is another sentence."
    }

    @Test
    void findNextSentenceEnd_ellipsis_ignoresFourDots() {
        // Given
        String text = "The story continues.... This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(49); // Position after "The story continues.... This is another sentence."
    }

    @Test
    void findNextSentenceEnd_ellipsis_ignoresMultipleEllipsis() {
        // Given
        String text = "First... then... finally. This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(25); // Position after "First... then... finally."
    }

    @Test
    void findNextSentenceEnd_ellipsis_ignoresEllipsisInMiddle() {
        // Given
        String text = "So... we go. This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(12); // Position after "So... we go."
    }

    // ===== CJK PUNCTUATION TESTS =====

    @Test
    void findNextSentenceEnd_cjkPunctuation_findsJapanesePeriod() {
        // Given
        String text = "これは日本語です。これはテストです。";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(9); // Position after "これは日本語です。"
    }

    @Test
    void findNextSentenceEnd_cjkPunctuation_findsJapaneseExclamation() {
        // Given
        String text = "これは素晴らしい！これはテストです。";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(9); // Position after "これは素晴らしい！"
    }

    @Test
    void findNextSentenceEnd_cjkPunctuation_findsJapaneseQuestion() {
        // Given
        String text = "これは何ですか？これはテストです。";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(8); // Position after "これは何ですか？"
    }

    @Test
    void findNextSentenceEnd_cjkPunctuation_findsChinesePeriod() {
        // Given
        String text = "这是第一句。这是第二句。";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(6); // Position after "这是第一句。"
    }

    @Test
    void findNextSentenceEnd_cjkPunctuation_findsChineseExclamation() {
        // Given
        String text = "这是第一句！这是第二句。";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(6); // Position after "这是第一句！"
    }

    @Test
    void findNextSentenceEnd_cjkPunctuation_findsChineseQuestion() {
        // Given
        String text = "这是第一句？这是第二句。";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(6); // Position after "这是第一句？"
    }

    @Test
    void findNextSentenceEnd_cjkPunctuation_mixedCJKAndLatin() {
        // Given
        String text = "Hello world! これはテストです。How are you?";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(12); // Position after "Hello world!"
    }

    // ===== UNICODE CLOSERS TESTS =====

    @Test
    void findNextSentenceEnd_unicodeClosers_skipsClosingQuotes() {
        // Given
        String text = "He said \"Hello world.\" This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(48); // Position after "He said \"Hello world.\" This is another sentence."
    }

    @Test
    void findNextSentenceEnd_unicodeClosers_skipsClosingBrackets() {
        // Given
        String text = "The answer is (42.) This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(18); // Position after "The answer is (42.) This is another sentence."
    }

    @Test
    void findNextSentenceEnd_unicodeClosers_skipsMultipleClosingPunctuation() {
        // Given
        String text = "He said \"Hello world.\"\" This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(49); // Position after "He said \"Hello world.\"\" This is another sentence."
    }

    @Test
    void findNextSentenceEnd_unicodeClosers_skipsUnicodeQuotes() {
        // Given
        String text = "He said »Hello world.« This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(48); // Position after "He said »Hello world.« This is another sentence."
    }

    @Test
    void findNextSentenceEnd_unicodeClosers_skipsUnicodeBrackets() {
        // Given
        String text = "He said 「Hello world.」 This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(21); // Position after "He said 「Hello world.」 This is another sentence."
    }

    @Test
    void findNextSentenceEnd_unicodeClosers_skipsMultipleUnicodeClosers() {
        // Given
        String text = "He said \"Hello world.\" She replied 'Yes.' This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(67); // Position after "He said \"Hello world.\" She replied 'Yes.' This is another sentence."
    }

    // ===== EDGE CASES TESTS =====

    @Test
    void findNextSentenceEnd_edgeCases_nullText() {
        // When
        int result = detector.findNextSentenceEnd(null);

        // Then
        assertThat(result).isEqualTo(-1);
    }

    @Test
    void findNextSentenceEnd_edgeCases_emptyText() {
        // When
        int result = detector.findNextSentenceEnd("");

        // Then
        assertThat(result).isEqualTo(-1);
    }

    @Test
    void findNextSentenceEnd_edgeCases_noSentenceEnding() {
        // Given
        String text = "This is a sentence without ending punctuation";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(-1);
    }

    @Test
    void findNextSentenceEnd_edgeCases_periodWithoutSpace() {
        // Given
        String text = "Hello world.This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(37); // Position after "Hello world.This is another sentence."
    }

    @Test
    void findNextSentenceEnd_edgeCases_periodAtEnd() {
        // Given
        String text = "This is a sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(19); // Position after the period
    }

    @Test
    void findNextSentenceEnd_edgeCases_exclamationAtEnd() {
        // Given
        String text = "This is exciting!";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(17); // Position after the exclamation mark
    }

    @Test
    void findNextSentenceEnd_edgeCases_questionAtEnd() {
        // Given
        String text = "What is this?";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(13); // Position after the question mark
    }

    // ===== COMPLEX COMBINATION TESTS =====

    @Test
    void findNextSentenceEnd_complexCombinations_mixedAbbreviationsAndDecimals() {
        // Given
        String text = "Dr. Smith measured 3.14 units. Mr. Jones found 2.7 items. This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(83); // Position after "Dr. Smith measured 3.14 units. Mr. Jones found 2.7 items. This is another sentence."
    }

    @Test
    void findNextSentenceEnd_complexCombinations_mixedCJKAndLatin() {
        // Given
        String text = "Hello world! これは素晴らしい！How are you? これは何ですか？This is a test.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(12); // Position after "Hello world!"
    }

    @Test
    void findNextSentenceEnd_complexCombinations_mixedUnicodeAndAbbreviations() {
        // Given
        String text = "He said \"Dr. Smith is here.\" She replied 'Mr. Jones too.' This is another sentence.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(83); // Position after "He said \"Dr. Smith is here.\" She replied 'Mr. Jones too.' This is another sentence."
    }

    @Test
    void findNextSentenceEnd_complexCombinations_allTypes() {
        // Given
        String text = "Dr. Smith measured 3.14 units... He said \"Hello world.\" これはテストです。This is a test.";

        // When
        int result = detector.findNextSentenceEnd(text);

        // Then
        assertThat(result).isEqualTo(65); // Position after the entire first sentence
    }
}
