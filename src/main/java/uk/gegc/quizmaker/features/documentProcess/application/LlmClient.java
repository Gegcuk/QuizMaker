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
     * Configuration options for structure generation.
     */
    record StructureOptions(
            String model,
            String profile,    // e.g., "academic", "technical", "narrative", "notes", "article", "general"
            String granularity // e.g., "coarse", "medium", "detailed"
    ) {
        public static StructureOptions defaultOptions() {
            return new StructureOptions("gpt-4o-mini", "general", "medium");
        }
        
        public static StructureOptions forNotes() {
            return new StructureOptions("gpt-4o-mini", "notes", "detailed");
        }
        
        public static StructureOptions forArticle() {
            return new StructureOptions("gpt-4o-mini", "article", "medium");
        }
        
        public static StructureOptions forAcademic() {
            return new StructureOptions("gpt-4o-mini", "academic", "detailed");
        }
        
        public static StructureOptions forTechnical() {
            return new StructureOptions("gpt-4o-mini", "technical", "detailed");
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
