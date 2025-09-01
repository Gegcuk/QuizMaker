package uk.gegc.quizmaker.features.documentProcess.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import uk.gegc.quizmaker.features.documentProcess.config.DocumentChunkingConfig;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Integration test for document chunking functionality.
 * Tests the complete chunking pipeline with large documents.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Document Chunking Integration Test")
class DocumentChunkingIntegrationTest {

    @Mock
    private TokenCounter tokenCounter;
    
    @Mock
    private DocumentChunkingConfig chunkingConfig;

    private DocumentChunker documentChunker;

    @BeforeEach
    void setUp() {
        // Configure only the settings that are actually used
        when(chunkingConfig.getMaxSingleChunkTokens()).thenReturn(40_000);
        when(chunkingConfig.getOverlapTokens()).thenReturn(5_000);
        when(chunkingConfig.isAggressiveChunking()).thenReturn(true);
        when(chunkingConfig.isEnableEmergencyChunking()).thenReturn(true);
        
        // Mock token counting behavior
        when(tokenCounter.estimateTokens(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            return text.length() / 3; // Simplified: 1 token per 3 chars
        });
        
        when(tokenCounter.exceedsTokenLimit(anyString(), anyInt())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            int limit = invocation.getArgument(1);
            return (text.length() / 3) > limit; // Return true if tokens exceed limit
        });
        
        when(tokenCounter.estimateMaxCharsForTokens(anyInt())).thenAnswer(invocation -> {
            int tokens = invocation.getArgument(0);
            return tokens * 3; // Convert tokens back to chars (1 token = 3 chars in our mock)
        });
        when(tokenCounter.getConfiguredSafeChunkSize()).thenReturn(120_000);
        
        documentChunker = new DocumentChunker(tokenCounter, chunkingConfig);
    }

    @Test
    @DisplayName("Should correctly chunk huge document with proper overlap")
    void shouldCorrectlyChunkHugeDocumentWithProperOverlap() {
        // Given: A large document that definitely needs chunking (reduced size for faster testing)
        String hugeDocument = createHugeDocument(200_000); // ~67k tokens
        String documentId = "huge-doc-test";
        
        // When: Chunking the document
        List<DocumentChunker.DocumentChunk> chunks = documentChunker.chunkDocument(hugeDocument, documentId);
        
        // Then: Document should be split into multiple chunks
        assertThat(chunks).isNotNull();
        assertThat(chunks.size()).isGreaterThan(1);
        
        // Verify each chunk is within safe limits
        for (DocumentChunker.DocumentChunk chunk : chunks) {
            int chunkTokens = chunk.getText().length() / 3; // Estimate tokens
            assertThat(chunkTokens).as("Each chunk should be within token limit")
                    .isLessThanOrEqualTo(45_000); // Some buffer above 40k limit
            assertThat(chunk.getText().length()).as("Each chunk should be within character limit")
                    .isLessThanOrEqualTo(160_000); // Some buffer above 150k limit
        }
        
        // Verify chunks have proper overlap for context continuity
        for (int i = 1; i < chunks.size(); i++) {
            DocumentChunker.DocumentChunk prevChunk = chunks.get(i - 1);
            DocumentChunker.DocumentChunk currChunk = chunks.get(i);
            
            // Check that chunks have some overlap (positions should overlap)
            assertThat(currChunk.getStartOffset())
                    .as("Chunk %d should start before previous chunk ends for overlap", i)
                    .isLessThan(prevChunk.getEndOffset());
            
            // Verify overlap size is reasonable (should be around 15k chars based on config)
            int overlapSize = prevChunk.getEndOffset() - currChunk.getStartOffset();
            assertThat(overlapSize).as("Overlap between chunks should be reasonable")
                    .isGreaterThan(5_000) // Minimum overlap
                    .isLessThan(25_000);  // Maximum reasonable overlap
        }
        
        // Verify all chunks together cover the entire document
        DocumentChunker.DocumentChunk firstChunk = chunks.get(0);
        DocumentChunker.DocumentChunk lastChunk = chunks.get(chunks.size() - 1);
        
        assertThat(firstChunk.getStartOffset()).as("First chunk should start at beginning")
                .isEqualTo(0);
        assertThat(lastChunk.getEndOffset()).as("Last chunk should end at document end")
                .isEqualTo(hugeDocument.length());
        
        // Verify no gaps in coverage
        for (int i = 1; i < chunks.size(); i++) {
            DocumentChunker.DocumentChunk prevChunk = chunks.get(i - 1);
            DocumentChunker.DocumentChunk currChunk = chunks.get(i);
            
            // There should be no gap between chunks (they should overlap or be adjacent)
            assertThat(currChunk.getStartOffset())
                    .as("No gap between chunks %d and %d", i-1, i)
                    .isLessThanOrEqualTo(prevChunk.getEndOffset());
        }
        
        // Verify chunk indices are sequential
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).getChunkIndex()).as("Chunk index should be sequential")
                    .isEqualTo(i);
        }
        
        // Log detailed results
        System.out.println("\n=== HUGE DOCUMENT CHUNKING TEST RESULTS ===");
        System.out.println("Document size: " + hugeDocument.length() + " characters");
        System.out.println("Estimated tokens: " + (hugeDocument.length() / 3));
        System.out.println("Number of chunks created: " + chunks.size());
        System.out.println("\nChunk details:");
        
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunker.DocumentChunk chunk = chunks.get(i);
            int tokens = chunk.getText().length() / 3;
            System.out.printf("Chunk %d: %d chars (~%d tokens), positions %d-%d%n",
                    i + 1, chunk.getText().length(), tokens, 
                    chunk.getStartOffset(), chunk.getEndOffset());
        }
        
        // Calculate and verify overlap statistics
        System.out.println("\nOverlap analysis:");
        for (int i = 1; i < chunks.size(); i++) {
            DocumentChunker.DocumentChunk prev = chunks.get(i - 1);
            DocumentChunker.DocumentChunk curr = chunks.get(i);
            int overlap = prev.getEndOffset() - curr.getStartOffset();
            System.out.printf("Overlap between chunks %d-%d: %d chars%n", i-1, i, overlap);
        }
        
        System.out.println("\n✓ HUGE DOCUMENT SUCCESSFULLY CHUNKED WITH PROPER OVERLAP!");
    }

    /**
     * Creates a huge document with realistic content structure.
     */
    private String createHugeDocument(int targetSize) {
        StringBuilder doc = new StringBuilder();
        
        // Create realistic document structure with chapters, sections, and content
        String chapterTemplate = """
                CHAPTER %d
                
                This is the beginning of chapter %d. In this chapter, we will explore various topics 
                related to the subject matter. The content is structured to provide comprehensive 
                coverage of the material while maintaining readability and logical flow.
                
                Section %d.1: Introduction
                
                The introduction to this chapter provides an overview of the key concepts that will 
                be discussed. It sets the context for the detailed examination that follows.
                
                Section %d.2: Main Content
                
                This section contains the primary content of the chapter. It includes detailed 
                explanations, examples, and analysis of the subject matter. The content is designed 
                to be educational and informative while remaining accessible to the target audience.
                
                Section %d.3: Examples and Applications
                
                Here we provide practical examples and real-world applications of the concepts 
                discussed in the previous sections. These examples help to illustrate the practical 
                relevance of the theoretical material.
                
                Section %d.4: Summary and Conclusions
                
                This section summarizes the key points covered in the chapter and draws conclusions 
                about the significance of the material. It also provides connections to related topics 
                that will be covered in subsequent chapters.
                
                """;
        
        int chapterCount = 1;
        while (doc.length() < targetSize) {
            doc.append(String.format(chapterTemplate, chapterCount, chapterCount, 
                    chapterCount, chapterCount, chapterCount, chapterCount));
            chapterCount++;
            
            // Add some variation to make it more realistic
            if (chapterCount % 5 == 0) {
                doc.append("\n\nINTERLUDE\n\n");
                doc.append("This is an interlude section that provides additional context and ");
                doc.append("background information. It helps to break up the chapter structure ");
                doc.append("and provides variety in the document format.\n\n");
            }
        }
        
        return doc.substring(0, Math.min(doc.length(), targetSize));
    }
}
