package uk.gegc.quizmaker.service.document.chunker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import uk.gegc.quizmaker.dto.document.ProcessDocumentRequest;
import uk.gegc.quizmaker.service.document.chunker.ContentChunker.Chunk;
import uk.gegc.quizmaker.service.document.parser.ParsedDocument;
import uk.gegc.quizmaker.util.ChunkTitleGenerator;
import uk.gegc.quizmaker.util.SentenceBoundaryDetector;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@Execution(ExecutionMode.CONCURRENT)
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChunkingBoundaryTest {

    @InjectMocks
    private uk.gegc.quizmaker.service.document.chunker.impl.ChapterBasedChunker chunker;

    @Mock
    private SentenceBoundaryDetector sentenceBoundaryDetector;

    @Mock
    private ChunkTitleGenerator titleGenerator;

    private ProcessDocumentRequest request;

    @BeforeEach
    void setUp() {
        request = new ProcessDocumentRequest();
        request.setMaxChunkSize(100);
        request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
        
        // Mock the utilities
        when(sentenceBoundaryDetector.findBestSplitPoint(anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    String text = invocation.getArgument(0);
                    Integer maxLength = invocation.getArgument(1);
                    return Math.min(text.length(), maxLength);
                });
        
        when(titleGenerator.generateChunkTitle(anyString(), anyInt(), anyInt(), anyBoolean()))
                .thenAnswer(invocation -> {
                    String title = invocation.getArgument(0);
                    Integer chunkIndex = invocation.getArgument(1);
                    Integer totalChunks = invocation.getArgument(2);
                    Boolean isMultipleChunks = invocation.getArgument(3);
                    
                    if (isMultipleChunks) {
                        return title + " (Part " + (chunkIndex + 1) + ")";
                    }
                    return title;
                });
    }

    @Test
    void chunkDocument_RespectsSentenceBoundaries() {
        // Arrange
        ParsedDocument document = createDocumentWithLongSentences();
        request.setMaxChunkSize(50);
        
        // Mock sentence boundary detection to respect sentence endings
        when(sentenceBoundaryDetector.findBestSplitPoint(anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    String text = invocation.getArgument(0);
                    Integer maxLength = invocation.getArgument(1);
                    
                    // Find the last sentence boundary within maxLength
                    int lastPeriod = text.lastIndexOf('.');
                    int lastExclamation = text.lastIndexOf('!');
                    int lastQuestion = text.lastIndexOf('?');
                    
                    int lastSentenceEnd = Math.max(Math.max(lastPeriod, lastExclamation), lastQuestion);
                    
                    if (lastSentenceEnd > 0 && lastSentenceEnd <= maxLength) {
                        return lastSentenceEnd + 1; // Include the punctuation
                    }
                    
                    return Math.min(text.length(), maxLength);
                });

        // Act
        List<Chunk> chunks = chunker.chunkDocument(document, request);

        // Assert
        assertNotNull(chunks);
        assertTrue(chunks.size() > 1);
        
        // Verify that chunks don't break in the middle of sentences
        for (Chunk chunk : chunks) {
            String content = chunk.getContent();
            // Should not end with an incomplete sentence
            assertFalse(content.endsWith("This is"));
            assertFalse(content.endsWith("The second"));
            assertFalse(content.endsWith("A third"));
        }
    }

    @Test
    void chunkDocument_RespectsWordBoundaries() {
        // Arrange
        ParsedDocument document = createDocumentWithLongWords();
        request.setMaxChunkSize(30);
        
        // Mock sentence boundary detection to fall back to word boundaries
        when(sentenceBoundaryDetector.findBestSplitPoint(anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    String text = invocation.getArgument(0);
                    Integer maxLength = invocation.getArgument(1);
                    
                    // Find the last word boundary within maxLength
                    for (int i = maxLength; i > 0; i--) {
                        if (i < text.length() && Character.isWhitespace(text.charAt(i))) {
                            return i;
                        }
                    }
                    
                    return Math.min(text.length(), maxLength);
                });

        // Act
        List<Chunk> chunks = chunker.chunkDocument(document, request);

        // Assert
        assertNotNull(chunks);
        assertTrue(chunks.size() > 1);
        
        // Verify that chunks don't break in the middle of words
        for (Chunk chunk : chunks) {
            String content = chunk.getContent();
            // Should not end with a partial word
            assertFalse(content.endsWith("supercalifragilistic"));
            assertFalse(content.endsWith("pneumonoultramicroscopicsilicovolcanoconiosi"));
        }
    }

    @Test
    void chunkDocument_HandlesAbbreviationsCorrectly() {
        // Arrange
        ParsedDocument document = createDocumentWithAbbreviations();
        request.setMaxChunkSize(80);
        
        // Mock sentence boundary detection to handle abbreviations and names
        when(sentenceBoundaryDetector.findBestSplitPoint(anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    String text = invocation.getArgument(0);
                    Integer maxLength = invocation.getArgument(1);

                    // Only split at a period if it's not part of an abbreviation and not followed by a capitalized word
                    int lastPeriod = -1;
                    for (int i = 0; i < text.length(); i++) {
                        if (text.charAt(i) == '.') {
                            // Check for abbreviation before
                            String before = text.substring(Math.max(0, i - 6), i + 1); // "Prof.", "Dr.", "Mr."
                            boolean isAbbreviation = before.endsWith("Mr.") || before.endsWith("Dr.") || before.endsWith("Prof.");
                            // Check for capitalized word after
                            boolean followedByCapital = false;
                            int j = i + 1;
                            while (j < text.length() && Character.isWhitespace(text.charAt(j))) j++;
                            if (j < text.length() && Character.isUpperCase(text.charAt(j))) {
                                followedByCapital = true;
                            }
                            if (!isAbbreviation || !followedByCapital) {
                                lastPeriod = i;
                            }
                        }
                    }
                    if (lastPeriod > 0 && lastPeriod <= maxLength) {
                        return lastPeriod + 1;
                    }
                    return Math.min(text.length(), maxLength);
                });

        // Act
        List<Chunk> chunks = chunker.chunkDocument(document, request);

        // Assert
        assertNotNull(chunks);
        assertTrue(chunks.size() > 1);
        
        // Verify that abbreviations are handled correctly
        for (Chunk chunk : chunks) {
            String content = chunk.getContent().trim();
            System.out.println("CHUNK: [" + content + "]");
            // Should not break at abbreviations (with or without trailing whitespace or punctuation)
            assertFalse(content.matches(".*Mr\\.[\\s\\p{Punct}]*$"));
            assertFalse(content.matches(".*Dr\\.[\\s\\p{Punct}]*$"));
            assertFalse(content.matches(".*Prof\\.[\\s\\p{Punct}]*$"));
        }
    }

    @Test
    void chunkDocument_HandlesDecimalNumbersCorrectly() {
        // Arrange
        ParsedDocument document = createDocumentWithDecimals();
        request.setMaxChunkSize(60);
        
        // Mock sentence boundary detection to handle decimals
        when(sentenceBoundaryDetector.findBestSplitPoint(anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    String text = invocation.getArgument(0);
                    Integer maxLength = invocation.getArgument(1);
                    
                    // Look for sentence endings, but ignore decimal numbers
                    int lastPeriod = text.lastIndexOf('.');
                    if (lastPeriod > 0 && lastPeriod <= maxLength) {
                        // Check if it's a decimal number by looking for digits before and after
                        String beforePeriod = text.substring(Math.max(0, lastPeriod - 5), lastPeriod);
                        String afterPeriod = text.substring(lastPeriod + 1, Math.min(text.length(), lastPeriod + 6));
                        
                        // If it's a decimal number (digits before and after), don't split here
                        if (beforePeriod.matches(".*\\d$") && afterPeriod.matches("^\\d.*")) {
                            // Find the previous sentence boundary
                            int prevPeriod = text.lastIndexOf('.', lastPeriod - 1);
                            if (prevPeriod > 0 && prevPeriod <= maxLength) {
                                return prevPeriod + 1;
                            }
                            // If no previous sentence boundary, just return maxLength
                            return maxLength;
                        }
                        // If it's not a decimal, it's a sentence ending
                        return lastPeriod + 1;
                    }
                    
                    return Math.min(text.length(), maxLength);
                });

        // Act
        List<Chunk> chunks = chunker.chunkDocument(document, request);

        // Assert
        assertNotNull(chunks);
        assertTrue(chunks.size() > 1);
        
        // Verify that decimal numbers are handled correctly
        for (Chunk chunk : chunks) {
            String content = chunk.getContent();
            // Should not break IN THE MIDDLE of decimal numbers
            // It's acceptable for a chunk to end with a complete decimal number
            // The important thing is that we don't split decimal numbers in half
            assertFalse(content.endsWith("3.1")); // Should not end with partial decimal
            assertFalse(content.endsWith("2.7")); // Should not end with partial decimal
            assertFalse(content.endsWith("1.6")); // Should not end with partial decimal
            
            // It's okay for chunks to end with complete decimal numbers like "3.14" or "2.718"
            // The boundary detection should avoid splitting at decimal periods
        }
    }

    @Test
    void chunkDocument_HandlesEllipsisCorrectly() {
        // Arrange
        ParsedDocument document = createDocumentWithEllipsis();
        request.setMaxChunkSize(70);
        
        // Mock sentence boundary detection to handle ellipsis
        when(sentenceBoundaryDetector.findBestSplitPoint(anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    String text = invocation.getArgument(0);
                    Integer maxLength = invocation.getArgument(1);
                    
                    // Look for sentence endings, but ignore ellipsis
                    int lastPeriod = text.lastIndexOf('.');
                    if (lastPeriod > 0 && lastPeriod <= maxLength) {
                        // Check if it's an ellipsis by looking for multiple periods
                        String aroundPeriod = text.substring(Math.max(0, lastPeriod - 2), 
                                                          Math.min(text.length(), lastPeriod + 3));
                        if (aroundPeriod.contains("...")) {
                            // Find the previous sentence boundary
                            int prevPeriod = text.lastIndexOf('.', lastPeriod - 1);
                            if (prevPeriod > 0 && prevPeriod <= maxLength) {
                                return prevPeriod + 1;
                            }
                            // If no previous sentence boundary, just return maxLength
                            return maxLength;
                        }
                        // If it's not an ellipsis, it's a sentence ending
                        return lastPeriod + 1;
                    }
                    
                    return Math.min(text.length(), maxLength);
                });

        // Act
        List<Chunk> chunks = chunker.chunkDocument(document, request);

        // Assert
        assertNotNull(chunks);
        assertTrue(chunks.size() > 1);
        
        // Verify that ellipsis is handled correctly
        for (Chunk chunk : chunks) {
            String content = chunk.getContent();
            // Should not break IN THE MIDDLE of ellipsis
            // It's acceptable for a chunk to end with a complete ellipsis
            assertFalse(content.endsWith("..")); // Should not end with partial ellipsis
            
            // It's okay for chunks to end with complete ellipsis like "..." or legitimate sentence endings
            // The boundary detection should avoid splitting at ellipsis periods
            // But single periods are legitimate sentence endings, so we don't check for those
        }
    }

    @Test
    void chunkDocument_MaintainsChunkSizeLimits() {
        // Arrange
        ParsedDocument document = createDocumentWithMixedContent();
        request.setMaxChunkSize(50);
        
        // Act
        List<Chunk> chunks = chunker.chunkDocument(document, request);

        // Assert
        assertNotNull(chunks);
        assertTrue(chunks.size() > 1);
        
        // Verify that all chunks respect the size limit
        for (Chunk chunk : chunks) {
            assertTrue(chunk.getCharacterCount() <= request.getMaxChunkSize());
        }
    }

    @Test
    void chunkDocument_HandlesEmptyContent() {
        // Arrange
        ParsedDocument document = createDocumentWithEmptyContent();
        request.setMaxChunkSize(100);

        // Act
        List<Chunk> chunks = chunker.chunkDocument(document, request);

        // Assert
        assertNotNull(chunks);
        assertTrue(chunks.isEmpty() || chunks.size() == 1);
        
        if (!chunks.isEmpty()) {
            Chunk chunk = chunks.get(0);
            assertTrue(chunk.getContent().isEmpty() || chunk.getContent().trim().isEmpty());
        }
    }

    @Test
    void chunkDocument_HandlesVerySmallChunkSize() {
        // Arrange
        ParsedDocument document = createDocumentWithLongContent();
        request.setMaxChunkSize(10); // Very small chunk size

        // Act
        List<Chunk> chunks = chunker.chunkDocument(document, request);

        // Assert
        assertNotNull(chunks);
        assertTrue(chunks.size() > 1);
        
        // Verify that all chunks respect the size limit
        for (Chunk chunk : chunks) {
            assertTrue(chunk.getCharacterCount() <= request.getMaxChunkSize());
        }
    }

    private ParsedDocument createDocumentWithLongSentences() {
        ParsedDocument document = new ParsedDocument();
        document.setTitle("Test Document");
        document.setContent("This is a very long sentence that should be split at appropriate boundaries. " +
                "The second sentence is also quite long and should be handled properly. " +
                "A third sentence follows with more content to test the chunking algorithm.");
        return document;
    }

    private ParsedDocument createDocumentWithLongWords() {
        ParsedDocument document = new ParsedDocument();
        document.setTitle("Test Document");
        document.setContent("This document contains supercalifragilisticexpialidocious words. " +
                "It also has pneumonoultramicroscopicsilicovolcanoconiosistype words. " +
                "These should be handled properly by the chunking algorithm.");
        return document;
    }

    private ParsedDocument createDocumentWithAbbreviations() {
        ParsedDocument document = new ParsedDocument();
        document.setTitle("Test Document");
        document.setContent("Mr. Smith went to the store. Dr. Johnson was also there. " +
                "Prof. Brown gave a lecture. The meeting ended at 3:00 p.m. " +
                "We used etc. for examples. The document was processed correctly.");
        return document;
    }

    private ParsedDocument createDocumentWithDecimals() {
        ParsedDocument document = new ParsedDocument();
        document.setTitle("Test Document");
        document.setContent("The value of pi is 3.14. The natural logarithm base is 2.718. " +
                "The golden ratio is 1.618. These are important mathematical constants. " +
                "The calculations were performed accurately.");
        return document;
    }

    private ParsedDocument createDocumentWithEllipsis() {
        ParsedDocument document = new ParsedDocument();
        document.setTitle("Test Document");
        document.setContent("He paused... Then he continued. The story was... interesting. " +
                "The ellipsis should be handled properly. The sentence continues normally. " +
                "Another example with... more content.");
        return document;
    }

    private ParsedDocument createDocumentWithMixedContent() {
        ParsedDocument document = new ParsedDocument();
        document.setTitle("Test Document");
        document.setContent("Short sentence. This is a longer sentence with more content. " +
                "Another sentence follows. The content varies in length. " +
                "Some sentences are brief. Others are more detailed and contain additional information.");
        return document;
    }

    private ParsedDocument createDocumentWithEmptyContent() {
        ParsedDocument document = new ParsedDocument();
        document.setTitle("Test Document");
        document.setContent("");
        return document;
    }

    private ParsedDocument createDocumentWithLongContent() {
        ParsedDocument document = new ParsedDocument();
        document.setTitle("Test Document");
        document.setContent("This is a very long document with many sentences. " +
                "Each sentence contains multiple words and should be processed correctly. " +
                "The chunking algorithm should handle this content appropriately. " +
                "Even with very small chunk sizes, the content should be split properly. " +
                "The boundaries should be respected and the content should remain coherent.");
        return document;
    }
} 