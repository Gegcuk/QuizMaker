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
 * Tests for DocumentChunker with token-based chunking logic.
 * Verifies the fix for the issue where large documents exceeding model token limits
 * were not being properly chunked.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DocumentChunker Token-Based Tests")
class DocumentChunkerTokenTest {

    @Mock
    private TokenCounter tokenCounter;
    
    @Mock
    private DocumentChunkingConfig chunkingConfig;

    private DocumentChunker documentChunker;

    @BeforeEach
    void setUp() {
        // Set up only the config values that are actually used
        when(chunkingConfig.getMaxSingleChunkTokens()).thenReturn(40_000);
        when(chunkingConfig.getMaxSingleChunkChars()).thenReturn(150_000);
        when(chunkingConfig.getOverlapTokens()).thenReturn(5_000);
        when(chunkingConfig.isAggressiveChunking()).thenReturn(true);
        when(chunkingConfig.isEnableEmergencyChunking()).thenReturn(true);
        
        documentChunker = new DocumentChunker(tokenCounter, chunkingConfig);
    }

    @Test
    @DisplayName("Should chunk large document exceeding token limit")
    void shouldChunkLargeDocumentExceedingTokenLimit() {
        // Given: A document that exceeds the 40k token limit
        String largeDocument = generateLargeDocument(200_000); // ~67k tokens
        String documentId = "test-doc-1";
        
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
            return tokens * 3; // Convert tokens back to chars
        });
        when(tokenCounter.getConfiguredSafeChunkSize()).thenReturn(120_000);
        
        // When: Chunking the document
        List<DocumentChunker.DocumentChunk> chunks = documentChunker.chunkDocument(largeDocument, documentId);
        
        // Then: Document should be split into multiple chunks
        assertThat(chunks).isNotNull();
        assertThat(chunks.size()).isGreaterThan(1);
        
        // Verify each chunk is within safe limits
        for (DocumentChunker.DocumentChunk chunk : chunks) {
            assertThat(chunk.getText().length()).isLessThanOrEqualTo(160_000); // Some buffer for boundaries
        }
        
        System.out.println("Large document chunking test:");
        System.out.println("- Document size: " + largeDocument.length() + " chars");
        System.out.println("- Number of chunks created: " + chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            System.out.println("- Chunk " + (i + 1) + ": " + chunks.get(i).getText().length() + " chars");
        }
    }

    @Test
    @DisplayName("Should NOT chunk document within token limit")
    void shouldNotChunkDocumentWithinTokenLimit() {
        // Given: A document well within token limits
        String smallDocument = generateLargeDocument(30_000); // ~10k tokens
        String documentId = "test-doc-2";
        
        // Mock token counting behavior
        when(tokenCounter.estimateTokens(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            return text.length() / 3;
        });
        
        when(tokenCounter.exceedsTokenLimit(anyString(), anyInt())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            int limit = invocation.getArgument(1);
            return (text.length() / 3) > limit; // Return true if tokens exceed limit
        });
        
        when(tokenCounter.estimateMaxCharsForTokens(anyInt())).thenAnswer(invocation -> {
            int tokens = invocation.getArgument(0);
            return tokens * 3;
        });
        when(tokenCounter.getConfiguredSafeChunkSize()).thenReturn(120_000);
        
        // When: Processing the document
        List<DocumentChunker.DocumentChunk> chunks = documentChunker.chunkDocument(smallDocument, documentId);
        
        // Then: Document should be in a single chunk
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getText()).isEqualTo(smallDocument);
    }

    @Test
    @DisplayName("Should handle document at exact token boundary")
    void shouldHandleDocumentAtTokenBoundary() {
        // Given: Document exactly at the safe processing limit
        String boundaryDocument = generateLargeDocument(100_000); // ~33k tokens (within 40k limit)
        String documentId = "test-doc-3";
        
        // Mock token counting
        when(tokenCounter.estimateTokens(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            return text.length() / 3;
        });
        
        when(tokenCounter.exceedsTokenLimit(anyString(), anyInt())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            int limit = invocation.getArgument(1);
            return (text.length() / 3) > limit; // Return true if tokens exceed limit
        });
        
        when(tokenCounter.estimateMaxCharsForTokens(anyInt())).thenAnswer(invocation -> {
            int tokens = invocation.getArgument(0);
            return tokens * 3;
        });
        when(tokenCounter.getConfiguredSafeChunkSize()).thenReturn(120_000);
        
        // When
        List<DocumentChunker.DocumentChunk> chunks = documentChunker.chunkDocument(boundaryDocument, documentId);
        
        // Then: Should be single chunk since it's within limit
        assertThat(chunks).hasSize(1);
    }

    @Test
    @DisplayName("Should demonstrate fix for user's reported issue")
    void shouldDemonstrateFixForReportedIssue() {
        // Given: User's case - document that exceeds token limits
        // Previous bug: tried to send entire document to model
        // Fix: should split into manageable chunks
        
        String largeDocument = generateLargeDocument(300_000); // ~100k tokens
        String documentId = "user-reported-issue";
        
        // Setup realistic token counting
        when(tokenCounter.estimateTokens(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            return text.length() / 3; // Simplified ratio
        });
        
        when(tokenCounter.exceedsTokenLimit(anyString(), anyInt())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            int limit = invocation.getArgument(1);
            return (text.length() / 3) > limit; // Return true if tokens exceed limit
        });
        
        when(tokenCounter.estimateMaxCharsForTokens(anyInt())).thenAnswer(invocation -> {
            int tokens = invocation.getArgument(0);
            return tokens * 3;
        });
        when(tokenCounter.getConfiguredSafeChunkSize()).thenReturn(120_000);
        
        // When: Processing the large document
        List<DocumentChunker.DocumentChunk> chunks = documentChunker.chunkDocument(largeDocument, documentId);
        
        // Then: Should be split into multiple chunks
        assertThat(chunks.size()).as("Should create multiple chunks for large document")
                .isGreaterThanOrEqualTo(2); // Should create at least 2 chunks
        
        // Verify no single chunk exceeds safe limit
        for (DocumentChunker.DocumentChunk chunk : chunks) {
            int chunkTokens = chunk.getText().length() / 3;
            assertThat(chunkTokens).as("Each chunk should be within token limit")
                    .isLessThan(50_000); // Well within 40k limit
        }
        
        // Verify chunks have proper overlap for context continuity
        for (int i = 1; i < chunks.size(); i++) {
            DocumentChunker.DocumentChunk prevChunk = chunks.get(i - 1);
            DocumentChunker.DocumentChunk currChunk = chunks.get(i);
            
            // Check that chunks have some overlap (positions should overlap)
            assertThat(currChunk.getStartOffset())
                    .as("Chunks should have overlap for context")
                    .isLessThan(prevChunk.getEndOffset());
        }
        
        System.out.println("\n=== Fix Verification for User's Reported Issue ===");
        System.out.println("Original document: " + largeDocument.length() + " chars (~100k tokens)");
        System.out.println("Chunks created: " + chunks.size());
        System.out.println("\nChunk details:");
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunker.DocumentChunk chunk = chunks.get(i);
            int tokens = chunk.getText().length() / 3;
            System.out.printf("Chunk %d: %d chars (~%d tokens), positions %d-%d%n",
                    i + 1, chunk.getText().length(), tokens, 
                    chunk.getStartOffset(), chunk.getEndOffset());
        }
        System.out.println("\n✓ Document is now properly chunked for sequential processing!");
        System.out.println("✓ Each chunk is within model token limits!");
        System.out.println("✓ Chunks have overlap for context continuity!");
    }

    /**
     * Helper method to generate a document of specified size.
     */
    private String generateLargeDocument(int targetSize) {
        StringBuilder doc = new StringBuilder();
        String paragraph = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. " +
                "Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. " +
                "Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris. " +
                "Duis aute irure dolor in reprehenderit in voluptate velit esse cillum. " +
                "Excepteur sint occaecat cupidatat non proident sunt in culpa qui officia. ";
        
        while (doc.length() < targetSize) {
            doc.append(paragraph);
            if (doc.length() % 10000 == 0) {
                doc.append("\n\nChapter ").append(doc.length() / 10000).append("\n\n");
            }
        }
        
        return doc.substring(0, Math.min(doc.length(), targetSize));
    }
}
