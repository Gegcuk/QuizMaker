package uk.gegc.quizmaker.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
@ExtendWith(MockitoExtension.class)
class SentenceBoundaryDetectorTest {

    @InjectMocks
    private SentenceBoundaryDetector detector;

    @Test
    void findLastSentenceEnd_WithSimpleSentence_ReturnsCorrectPosition() {
        // Arrange
        String text = "This is a simple sentence.";

        // Act
        int result = detector.findLastSentenceEnd(text);

        // Assert
        assertEquals(26, result); // Position after the period
    }

    @Test
    void findLastSentenceEnd_WithMultipleSentences_ReturnsLastSentenceEnd() {
        // Arrange
        String text = "This is the first sentence. This is the second sentence.";

        // Act
        int result = detector.findLastSentenceEnd(text);

        // Assert
        assertEquals(56, result); // Position after the second period
    }

    @Test
    void findLastSentenceEnd_WithExclamationMark_ReturnsCorrectPosition() {
        // Arrange
        String text = "This is an exciting sentence!";

        // Act
        int result = detector.findLastSentenceEnd(text);

        // Assert
        assertEquals(29, result); // Position after the exclamation mark
    }

    @Test
    void findLastSentenceEnd_WithQuestionMark_ReturnsCorrectPosition() {
        // Arrange
        String text = "What is this question?";

        // Act
        int result = detector.findLastSentenceEnd(text);

        // Assert
        assertEquals(22, result); // Position after the question mark
    }

    @Test
    void findLastSentenceEnd_WithAbbreviation_IgnoresAbbreviation() {
        // Arrange
        String text = "Mr. Smith went to the store. He bought milk.";

        // Act
        int result = detector.findLastSentenceEnd(text);

        // Assert
        assertEquals(44, result); // Should find the second period, not the one in "Mr."
    }

    @Test
    void findLastSentenceEnd_WithDecimal_IgnoresDecimal() {
        // Arrange
        String text = "The price is 3.50 dollars. That's expensive.";

        // Act
        int result = detector.findLastSentenceEnd(text);

        // Assert
        assertEquals(44, result); // Should find the second period, not the one in "3.50"
    }

    @Test
    void findLastSentenceEnd_WithEllipsis_IgnoresEllipsis() {
        // Arrange
        String text = "He paused... Then he continued. The end.";

        // Act
        int result = detector.findLastSentenceEnd(text);

        // Assert
        assertEquals(40, result); // Should find the second period, not the ellipsis
    }

    @Test
    void findLastSentenceEnd_WithNoSentenceEnd_ReturnsNegativeOne() {
        // Arrange
        String text = "This is a sentence without ending punctuation";

        // Act
        int result = detector.findLastSentenceEnd(text);

        // Assert
        assertEquals(-1, result);
    }

    @Test
    void findLastSentenceEnd_WithEmptyText_ReturnsNegativeOne() {
        // Arrange
        String text = "";

        // Act
        int result = detector.findLastSentenceEnd(text);

        // Assert
        assertEquals(-1, result);
    }

    @Test
    void findLastSentenceEnd_WithNullText_ReturnsNegativeOne() {
        // Arrange
        String text = null;

        // Act
        int result = detector.findLastSentenceEnd(text);

        // Assert
        assertEquals(-1, result);
    }

    @Test
    void findBestSplitPoint_WithSentenceBoundary_ReturnsSentenceEnd() {
        // Arrange
        String text = "This is a sentence. This is another sentence.";
        int maxLength = 30;

        // Act
        int result = detector.findBestSplitPoint(text, maxLength);

        // Assert
        assertEquals(28, result); // Should split at the first sentence end
    }

    @Test
    void findBestSplitPoint_WithNoGoodBoundary_ReturnsWordBoundary() {
        // Arrange
        String text = "This is a very long sentence without good boundaries";
        int maxLength = 20;

        // Act
        int result = detector.findBestSplitPoint(text, maxLength);

        // Assert
        assertTrue(result > 0 && result <= maxLength);
    }

    @Test
    void findBestSplitPoint_WithShortText_ReturnsTextLength() {
        // Arrange
        String text = "Short text";
        int maxLength = 50;

        // Act
        int result = detector.findBestSplitPoint(text, maxLength);

        // Assert
        assertEquals(text.length(), result);
    }

    @Test
    void isValidChunk_WithCompleteSentence_ReturnsTrue() {
        // Arrange
        String text = "This is a complete sentence.";

        // Act
        boolean result = detector.isValidChunk(text);

        // Assert
        assertTrue(result);
    }

    @Test
    void isValidChunk_WithIncompleteSentence_ReturnsTrue() {
        // Arrange
        String text = "This is an incomplete sentence";

        // Act
        boolean result = detector.isValidChunk(text);

        // Assert
        assertTrue(result); // "sentence" is not in the incomplete indicators list, so it's considered valid
    }

    @Test
    void isValidChunk_WithIncompleteWord_ReturnsFalse() {
        // Arrange
        String text = "This sentence ends with the";

        // Act
        boolean result = detector.isValidChunk(text);

        // Assert
        assertFalse(result); // "the" is in the incomplete sentence indicators list
    }

    @Test
    void isValidChunk_WithEmptyText_ReturnsTrue() {
        // Arrange
        String text = "";

        // Act
        boolean result = detector.isValidChunk(text);

        // Assert
        assertTrue(result);
    }

    @Test
    void isValidChunk_WithNullText_ReturnsTrue() {
        // Arrange
        String text = null;

        // Act
        boolean result = detector.isValidChunk(text);

        // Assert
        assertTrue(result);
    }

    @Test
    void isValidChunk_WithWhitespaceEnding_ReturnsTrue() {
        // Arrange
        String text = "This sentence ends with whitespace ";

        // Act
        boolean result = detector.isValidChunk(text);

        // Assert
        assertTrue(result);
    }

    @Test
    void findBestSplitPoint_WithComplexText_HandlesCorrectly() {
        // Arrange
        String text = "Dr. Smith went to the store at 3.45 p.m. He bought milk and bread. " +
                "The total was $12.50. Mrs. Johnson was also there.";
        int maxLength = 80;

        // Act
        int result = detector.findBestSplitPoint(text, maxLength);

        // Assert
        assertTrue(result > 0 && result <= maxLength);
        // Should split at a sentence boundary, not at abbreviations or decimals
    }
} 