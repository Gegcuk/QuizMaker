package uk.gegc.quizmaker.features.document.infra.text;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SentenceBoundaryDetectorTest {

    private SentenceBoundaryDetector detector;

    @BeforeEach
    void setUp() {
        detector = new SentenceBoundaryDetector();
    }

    @Test
    void findLastSentenceEnd_shouldReturnNegativeOneForNullText() {
        assertThat(detector.findLastSentenceEnd(null)).isEqualTo(-1);
    }

    @Test
    void findLastSentenceEnd_shouldReturnNegativeOneForEmptyText() {
        assertThat(detector.findLastSentenceEnd("")).isEqualTo(-1);
    }

    @Test
    void findNextSentenceEnd_shouldReturnNegativeOneForNullText() {
        assertThat(detector.findNextSentenceEnd(null)).isEqualTo(-1);
    }

    @Test
    void findNextSentenceEnd_shouldReturnNegativeOneForEmptyText() {
        assertThat(detector.findNextSentenceEnd("")).isEqualTo(-1);
    }

    @Test
    void findLastSentenceEnd_shouldFindPeriodEnding() {
        String text = "Hello world. This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length());
    }

    @Test
    void findLastSentenceEnd_shouldFindExclamationEnding() {
        String text = "Hello world! This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(28); // Position after the final period, not after "Hello world!"
    }

    @Test
    void findLastSentenceEnd_shouldFindQuestionEnding() {
        String text = "Hello world? This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(28); // Position after the final period, not after "Hello world?"
    }

    @Test
    void findNextSentenceEnd_shouldFindFirstPeriodEnding() {
        String text = "Hello world. This is a test.";
        int result = detector.findNextSentenceEnd(text);
        assertThat(result).isEqualTo(12); // Position after "Hello world."
    }

    @Test
    void findNextSentenceEnd_shouldFindFirstExclamationEnding() {
        String text = "Hello world! This is a test.";
        int result = detector.findNextSentenceEnd(text);
        assertThat(result).isEqualTo(12); // Position after "Hello world!"
    }

    @Test
    void findNextSentenceEnd_shouldFindFirstQuestionEnding() {
        String text = "Hello world? This is a test.";
        int result = detector.findNextSentenceEnd(text);
        assertThat(result).isEqualTo(12); // Position after "Hello world?"
    }

    @Test
    void shouldNotTreatAbbreviationsAsSentenceEndings() {
        String text = "Dr. Smith went to the store. Mr. Jones was there too.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period, not Dr. or Mr.
    }

    @Test
    void shouldNotTreatDecimalsAsSentenceEndings() {
        String text = "The value is 3.14. This is pi.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period, not 3.14
    }

    @Test
    void shouldNotTreatEllipsisAsSentenceEnding() {
        String text = "The story continues... This is the end.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period, not ...
    }

    @Test
    void shouldHandleCJKPunctuation() {
        String text = "これは日本語です。これはテストです。";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final 。
    }

    @Test
    void shouldHandleCJKExclamation() {
        String text = "これは素晴らしい！これはテストです。";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(18); // Position after the final 。
    }

    @Test
    void shouldHandleCJKQuestion() {
        String text = "これは何ですか？これはテストです。";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(17); // Position after the final 。
    }

    @Test
    void shouldHandleClosingQuotesAfterPunctuation() {
        String text = "He said \"Hello world.\" This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period, not the one in quotes
    }

    @Test
    void shouldHandleClosingBracketsAfterPunctuation() {
        String text = "The answer is (42.) This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period, not the one in brackets
    }

    @Test
    void shouldHandleMultipleClosingPunctuation() {
        String text = "He said \"Hello world.\"\" This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period
    }

    @Test
    void shouldHandleUnicodeClosingPunctuation() {
        String text = "He said »Hello world.« This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period
    }

    @Test
    void shouldHandleNonBreakingSpaceAfterPunctuation() {
        String text = "Hello world.\u00A0This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(28); // Position after the final period, not before non-breaking space
    }

    @Test
    void shouldHandleEndOfTextWithoutPunctuation() {
        String text = "Hello world";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(-1); // No sentence ending found
    }

    @Test
    void shouldHandleTextWithOnlyWhitespace() {
        String text = "   \n\t  ";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(-1); // No sentence ending found
    }

    @Test
    void shouldHandlePeriodFollowedByNonWhitespace() {
        String text = "Hello world.This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period, not the one without space
    }

    @Test
    void shouldHandlePeriodAtEndOfText() {
        String text = "Hello world.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the period at the end
    }

    @Test
    void shouldHandleExclamationAtEndOfText() {
        String text = "Hello world!";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the exclamation at the end
    }

    @Test
    void shouldHandleQuestionAtEndOfText() {
        String text = "Hello world?";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the question mark at the end
    }

    @Test
    void shouldHandleCJKPunctuationAtEndOfText() {
        String text = "これはテストです。";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the 。 at the end
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Dr. Smith",
            "Mr. Jones",
            "Mrs. Brown",
            "Ms. Davis",
            "Prof. Wilson",
            "Sr. Garcia",
            "Jr. Martinez",
            "Inc. Corp",
            "Ltd. Company",
            "Corp. Business",
            "Co. Enterprise",
            "vs. opponent",
            "etc. items",
            "i.e. example",
            "e.g. sample",
            "a.m. time",
            "p.m. time",
            "U.S. America",
            "U.K. Britain",
            "Ph.D. degree",
            "M.A. degree",
            "B.A. degree"
    })
    void shouldNotTreatCommonAbbreviationsAsSentenceEndings(String abbreviation) {
        String text = abbreviation + " went to the store. This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period, not the abbreviation
    }

    @Test
    void shouldHandleComplexAbbreviationPatterns() {
        String text = "Dr. Smith and Mr. Jones went to U.S. Inc. headquarters. This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period, not any abbreviation
    }

    @Test
    void shouldHandleDecimalNumbers() {
        String text = "The value is 3.14159. This is pi.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period, not 3.14159
    }

    @Test
    void shouldHandleMultipleDecimals() {
        String text = "Values are 1.5, 2.7, and 3.14. This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period, not any decimal
    }

    @Test
    void shouldHandleEllipsis() {
        String text = "The story continues... This is the end.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period, not ...
    }

    @Test
    void shouldHandleMultipleEllipsis() {
        String text = "First... then... finally. This is the end.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period, not any ...
    }

    @Test
    void shouldHandleMixedPunctuation() {
        String text = "Hello world! How are you? I'm fine. This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period
    }

    @Test
    void shouldHandleMixedCJKAndLatinPunctuation() {
        String text = "Hello world! これはテストです。How are you?";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final question mark
    }

    @Test
    void shouldHandleUnicodeQuotesAndBrackets() {
        String text = "He said »Hello world.« She replied 'Yes.' This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period
    }

    @Test
    void shouldHandleComplexUnicodePunctuation() {
        String text = "He said \"Hello world.\" She replied 'Yes.' This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period
    }

    @Test
    void shouldHandlePeriodInMiddleOfWord() {
        String text = "Hello.world This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period, not the one in "Hello.world"
    }

    @Test
    void shouldHandleMultipleSpacesAfterPunctuation() {
        String text = "Hello world.    This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(31); // Position after the final period, not before multiple spaces
    }

    @Test
    void shouldHandleTabsAfterPunctuation() {
        String text = "Hello world.\tThis is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(28); // Position after the final period, not before tab
    }

    @Test
    void shouldHandleNewlinesAfterPunctuation() {
        String text = "Hello world.\nThis is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(28); // Position after the final period, not before newline
    }

    @Test
    void shouldHandleCarriageReturnsAfterPunctuation() {
        String text = "Hello world.\rThis is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(28); // Position after the final period, not before carriage return
    }

    @Test
    void shouldHandleFormFeedsAfterPunctuation() {
        String text = "Hello world.\fThis is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(28); // Position after the final period, not before form feed
    }

    @Test
    void shouldHandleAllWhitespaceTypesAfterPunctuation() {
        String text = "Hello world.\u00A0\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200A\u202F\u205F\u3000This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(42); // Position after the final period, not before various space characters
    }

    @Test
    void shouldHandlePeriodFollowedByLetter() {
        String text = "Hello world.This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period, not the one without space
    }

    @Test
    void shouldHandlePeriodFollowedByDigit() {
        String text = "Hello world.123 This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period, not the one without space
    }

    @Test
    void shouldHandlePeriodFollowedByPunctuation() {
        String text = "Hello world., This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period, not the one followed by comma
    }

    @Test
    void shouldHandlePeriodFollowedBySymbol() {
        String text = "Hello world.$ This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period, not the one followed by $
    }

    @Test
    void shouldHandlePeriodFollowedByControlCharacter() {
        String text = "Hello world.\u0000This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period, not the one followed by null
    }

    @Test
    void shouldHandleVeryLongText() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            text.append("This is sentence number ").append(i).append(". ");
        }
        text.append("This is the final sentence.");

        int result = detector.findLastSentenceEnd(text.toString());
        assertThat(result).isEqualTo(text.length()); // Should find the final period
    }

    @Test
    void shouldHandleTextWithOnlyAbbreviations() {
        String text = "Dr. Mr. Mrs. Ms. Prof. Sr. Jr. Inc. Ltd. Corp. Co. vs. etc. i.e. e.g. a.m. p.m. U.S. U.K. Ph.D. M.A. B.A.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(-1); // No sentence ending found (all are abbreviations)
    }

    @Test
    void shouldHandleTextWithOnlyDecimals() {
        String text = "1.5 2.7 3.14 42.0 0.1";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(-1); // No sentence ending found (all are decimals)
    }

    @Test
    void shouldHandleTextWithOnlyEllipsis() {
        String text = "... ... ...";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(-1); // No sentence ending found (all are ellipsis)
    }

    @Test
    void shouldHandleTextWithMixedAbbreviationsAndRealEndings() {
        String text = "Dr. Smith went to the store. Mr. Jones was there. This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period
    }

    @Test
    void shouldHandleTextWithMixedDecimalsAndRealEndings() {
        String text = "The values are 1.5, 2.7, and 3.14. This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period
    }

    @Test
    void shouldHandleTextWithMixedEllipsisAndRealEndings() {
        String text = "The story continues... This is the end.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period
    }

    @Test
    void shouldHandleTextWithCJKAndLatinMixed() {
        String text = "Hello world! これは素晴らしい！How are you? これは何ですか？This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period
    }

    @Test
    void shouldHandleTextWithUnicodeQuotesAndBracketsMixed() {
        String text = "He said \"Hello world.\" She replied 'Yes.' He asked \"How are you?\" This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period
    }

    @Test
    void shouldHandleTextWithAllPunctuationTypes() {
        String text = "Hello world! How are you? I'm fine. これはテストです。これは素晴らしい！これは何ですか？This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period
    }

    @Test
    void shouldHandleTextWithAllWhitespaceTypes() {
        String text = "Hello world.\u00A0\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200A\u202F\u205F\u3000This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(42); // Position after the final period, not before various space characters
    }

    @Test
    void shouldHandleTextWithAllAbbreviationTypes() {
        String text = "Dr. Smith, Mr. Jones, Mrs. Brown, Ms. Davis, Prof. Wilson, Sr. Garcia, Jr. Martinez, Inc. Corp, Ltd. Company, Corp. Business, Co. Enterprise, vs. opponent, etc. items, i.e. example, e.g. sample, a.m. time, p.m. time, U.S. America, U.K. Britain, Ph.D. degree, M.A. degree, B.A. degree went to the store. This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period
    }

    @Test
    void shouldHandleTextWithAllDecimalTypes() {
        String text = "Values are 1.5, 2.7, 3.14159, 42.0, 0.1, 100.99, 0.001, 999.999. This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period
    }

    @Test
    void shouldHandleTextWithAllEllipsisTypes() {
        String text = "First... then... finally... and so on... This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period
    }

    @Test
    void shouldHandleTextWithAllUnicodePunctuationTypes() {
        String text = "He said \"Hello world.\" She replied 'Yes.' He asked \"How are you?\" They exclaimed \"Wonderful!\" This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period
    }

    @Test
    void shouldHandleTextWithAllCJKPunctuationTypes() {
        String text = "これはテストです。これは素晴らしい！これは何ですか？これは驚きです！これは質問ですか？This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period
    }

    @Test
    void shouldHandleTextWithAllLatinPunctuationTypes() {
        String text = "Hello world! How are you? I'm fine. This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period
    }

    @Test
    void shouldHandleTextWithAllMixedPunctuationTypes() {
        String text = "Hello world! How are you? I'm fine. これはテストです。これは素晴らしい！これは何ですか？He said »Hello world.« She replied 'Yes.' This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period
    }

    // ===== MULTILINGUAL TESTS =====

    @Test
    void shouldHandleArabicText() {
        String text = "هذا هو الجملة الأولى. هذا هو الجملة الثانية؟ هذا هو الجملة الثالثة!";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final exclamation mark
    }

    @Test
    void shouldHandleHindiText() {
        String text = "यह पहला वाक्य है। यह दूसरा वाक्य है? यह तीसरा वाक्य है!";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final exclamation mark
    }

    @Test
    void shouldHandleCJKTextWithMultipleSentenceEndings() {
        String text = "这是第一句。这是第二句？这是第三句！";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final ！
    }

    @Test
    void shouldHandleCJKTextWithMixedPunctuation() {
        String text = "这是第一句。这是第二句？这是第三句！这是第四句。";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final 。
    }

    // ===== QUOTES/BRACKETS TESTS =====

    @Test
    void shouldHandleQuotesAfterPunctuation() {
        String text = "He said \"Hello world.\" Next sentence.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period, not the one in quotes
    }

    @Test
    void shouldHandleBracketsAfterPunctuation() {
        String text = "The answer is (42.) Next sentence.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period, not the one in brackets
    }

    @Test
    void shouldHandleMultipleClosingPunctuationAfterPeriod() {
        String text = "He said \"Hello world.\"\" Next sentence.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period
    }

    @Test
    void shouldHandleUnicodeQuotesAfterPunctuation() {
        String text = "He said »Hello world.« Next sentence.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period
    }

    @Test
    void shouldHandleUnicodeBracketsAfterPunctuation() {
        String text = "He said \"Hello world.\" Next sentence.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period
    }

    // ===== ABBREVIATION AT END OF SENTENCE TESTS =====

    @Test
    void shouldHandleAbbreviationAtEndOfSentence() {
        String text = "He met Dr. Smith. The next day...";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(-1); // No sentence ending found (Dr. is abbreviation, ... is ellipsis)
    }

    @Test
    void shouldHandleAbbreviationAtEndOfSentenceWithUppercase() {
        String text = "See etc. The result...";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(-1); // No sentence ending found (etc. is abbreviation, ... is ellipsis)
    }

    @Test
    void shouldHandleAbbreviationAtEndOfSentenceWithNewline() {
        String text = "See etc.\nThe result...";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(-1); // No sentence ending found (etc. is abbreviation, ... is ellipsis)
    }

    @Test
    void shouldHandleAbbreviationAtEndOfSentenceWithSpace() {
        String text = "See etc. The result...";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(-1); // No sentence ending found (etc. is abbreviation, ... is ellipsis)
    }

    // ===== DECIMAL TESTS =====

    @Test
    void shouldHandleDecimalInMiddleOfSentence() {
        String text = "Speed is 3.14 m/s. Next value...";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(-1); // No sentence ending found (3.14 is decimal, ... is ellipsis)
    }

    @Test
    void shouldHandleMultipleDecimalsInSentence() {
        String text = "Values are 1.5, 2.7, and 3.14. Next value...";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(-1); // No sentence ending found (all are decimals, ... is ellipsis)
    }

    @Test
    void shouldHandleDecimalAtEndOfSentence() {
        String text = "The value is 3.14.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(-1); // No sentence ending found (3.14 is decimal)
    }

    // ===== ELLIPSIS TESTS =====

    @Test
    void shouldHandleEllipsisInMiddleOfSentence() {
        String text = "So... we go.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period, not the middle dots
    }

    @Test
    void shouldHandleEllipsisAtEndOfSentence() {
        String text = "The story continues...";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(-1); // No sentence ending found (all are ellipsis)
    }

    @Test
    void shouldHandleEllipsisFollowedBySentence() {
        String text = "So... we go. Next sentence.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period
    }

    // ===== TITLE TRUNCATION WITH EMOJI/SURROGATES TESTS =====

    @Test
    void shouldHandleEmojiInText() {
        String text = "Hello world! 🎉 This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period
    }

    @Test
    void shouldHandleSurrogatePairsInText() {
        String text = "Hello world! 🌍 This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period
    }

    @Test
    void shouldHandleMultipleEmojisInText() {
        String text = "Hello world! 🎉🌍🚀 This is a test.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period
    }

    @Test
    void shouldHandleEmojiAtEndOfSentence() {
        String text = "Hello world! 🎉";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(12); // Should find the exclamation mark before emoji
    }

    // ===== PROPERTY TESTS =====

    @Test
    void shouldHandleRandomParagraphsAndHeadings() {
        // Generate random text with headings and paragraphs
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            text.append("Chapter ").append(i).append(": Random Content\n\n");
            for (int j = 1; j <= 5; j++) {
                text.append("This is paragraph ").append(j).append(" in chapter ").append(i).append(". ");
                text.append("It contains some random content. ");
                text.append("We need enough text to test the splitting behavior. ");
                text.append("This should create multiple windows for each chapter.\n\n");
            }
        }

        // Test that we can find sentence boundaries in the generated text
        int result = detector.findLastSentenceEnd(text.toString());
        assertThat(result).isGreaterThan(0); // Should find at least one sentence ending
    }

    // ===== BOUNDARY TESTS AROUND MAX_WINDOW_CHARS =====

    @Test
    void shouldHandleTextExactlyAtMaxWindowChars() {
        StringBuilder text = new StringBuilder();
        // Generate text that's exactly 8000 characters
        while (text.length() < 8000) {
            text.append("This is a sentence. ");
        }
        text.setLength(8000); // Ensure exactly 8000 characters

        int result = detector.findLastSentenceEnd(text.toString());
        assertThat(result).isGreaterThan(0); // Should find at least one sentence ending
    }

    @Test
    void shouldHandleTextOneCharBelowMaxWindowChars() {
        StringBuilder text = new StringBuilder();
        // Generate text that's exactly 7999 characters
        while (text.length() < 7999) {
            text.append("This is a sentence. ");
        }
        text.setLength(7999); // Ensure exactly 7999 characters

        int result = detector.findLastSentenceEnd(text.toString());
        assertThat(result).isGreaterThan(0); // Should find at least one sentence ending
    }

    @Test
    void shouldHandleTextOneCharAboveMaxWindowChars() {
        StringBuilder text = new StringBuilder();
        // Generate text that's exactly 8001 characters
        while (text.length() < 8001) {
            text.append("This is a sentence. ");
        }
        text.setLength(8001); // Ensure exactly 8001 characters

        int result = detector.findLastSentenceEnd(text.toString());
        assertThat(result).isGreaterThan(0); // Should find at least one sentence ending
    }

    // ===== COMPLEX MULTILINGUAL TESTS =====

    @Test
    void shouldHandleMixedLanguagesInSameText() {
        String text = "Hello world! 这是中文。नमस्ते दुनिया! مرحبا بالعالم! This is English.";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period
    }

    @Test
    void shouldHandleCJKWithLatinPunctuation() {
        String text = "这是第一句. 这是第二句? 这是第三句!";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final exclamation mark
    }

    @Test
    void shouldHandleArabicWithLatinPunctuation() {
        String text = "هذا هو الجملة الأولى. هذا هو الجملة الثانية? هذا هو الجملة الثالثة!";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final exclamation mark
    }

    @Test
    void shouldHandleHindiWithLatinPunctuation() {
        String text = "यह पहला वाक्य है. यह दूसरा वाक्य है? यह तीसरा वाक्य है!";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final exclamation mark
    }

    // ===== EDGE CASE TESTS =====

    @Test
    void shouldHandleVeryLongTextWithoutHeadings() {
        StringBuilder text = new StringBuilder();
        // Generate text that's exactly 16000 characters (2x MAX_WINDOW_CHARS)
        while (text.length() < 16000) {
            text.append("This is a very long sentence without any headings. ");
            text.append("It contains some content to make it longer. ");
            text.append("We need enough text to test the splitting behavior. ");
            text.append("This should create multiple windows for processing.\n\n");
        }
        text.setLength(16000); // Ensure exactly 16000 characters

        int result = detector.findLastSentenceEnd(text.toString());
        assertThat(result).isGreaterThan(0); // Should find at least one sentence ending
    }

    @Test
    void shouldHandleTextWithOnlyPunctuation() {
        String text = "... !!! ??? ...";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(11); // Should find the last period (ellipsis is not treated as sentence ending)
    }

    @Test
    void shouldHandleTextWithOnlyEmojis() {
        String text = "🎉🌍🚀🎊";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(-1); // No sentence ending found
    }

    @Test
    void shouldHandleTextWithMixedEmojisAndPunctuation() {
        String text = "Hello world! 🎉 This is a test? 🌍";
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(31); // Should find the final question mark
    }

    @Test
    void shouldHandleTextWithUnicodeSurrogates() {
        String text = "Hello world! \uD83C\uDF89 This is a test."; // 🎉 as surrogate pair
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period
    }

    @Test
    void shouldHandleTextWithComplexUnicodeSequences() {
        String text = "Hello world! \uD83C\uDF89\uD83C\uDF0D\uD83D\uDE80 This is a test."; // 🎉🌍🚀 as surrogate pairs
        int result = detector.findLastSentenceEnd(text);
        assertThat(result).isEqualTo(text.length()); // Should find the final period
    }
}
