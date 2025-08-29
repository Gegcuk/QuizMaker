package uk.gegc.quizmaker.features.documentProcess.application;

import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;

import java.util.List;

/**
 * Interface for LLM-based document structure generation.
 * Abstracts away the specific AI provider for testability and flexibility.
 */
public interface LlmClient {

    /**
     * Generates a document structure from the provided text.
     * 
     * @param text the normalized document text
     * @param options optional parameters for structure generation
     * @return list of document nodes representing the structure
     * @throws LlmException if the AI call fails or returns invalid data
     */
    List<DocumentNode> generateStructure(String text, StructureOptions options);

    /**
     * Generates a document structure from the provided text with context from previous chunks.
     * This ensures continuity and avoids generating duplicate sections.
     * 
     * @param text the normalized document text for the current chunk
     * @param options optional parameters for structure generation
     * @param previousNodes previously generated nodes for context
     * @param chunkIndex current chunk index (0-based)
     * @param totalChunks total number of chunks
     * @return list of document nodes representing the structure
     * @throws LlmException if the AI call fails or returns invalid data
     */
    default List<DocumentNode> generateStructureWithContext(String text, StructureOptions options, 
                                                          List<DocumentNode> previousNodes, 
                                                          int chunkIndex, int totalChunks) {
        // Default implementation falls back to regular generation
        return generateStructure(text, options);
    }

    /**
     * Configuration options for structure generation.
     */
    record StructureOptions(
            String model,
            String profile,    // e.g., "academic", "technical", "narrative", "notes", "article", "general"
            String granularity // e.g., "coarse", "medium", "detailed"
    ) {
        public static StructureOptions defaultOptions() {
            return new StructureOptions("gpt-4o-mini", "general", "coarse");
        }
        
        public static StructureOptions forNotes() {
            return new StructureOptions("gpt-4o-mini", "notes", "coarse");
        }
        
        public static StructureOptions forArticle() {
            return new StructureOptions("gpt-4o-mini", "article", "coarse");
        }
        
        public static StructureOptions forAcademic() {
            return new StructureOptions("gpt-4o-mini", "academic", "coarse");
        }
        
        public static StructureOptions forTechnical() {
            return new StructureOptions("gpt-4o-mini", "technical", "coarse");
        }
    }

    /**
     * Exception thrown when LLM operations fail.
     */
    class LlmException extends RuntimeException {
        public LlmException(String message) {
            super(message);
        }
        
        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
