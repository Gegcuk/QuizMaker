package uk.gegc.quizmaker.service.document.chunker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.dto.document.ProcessDocumentRequest;
import uk.gegc.quizmaker.exception.DocumentProcessingException;
import uk.gegc.quizmaker.service.document.converter.ConvertedDocument;

import java.util.List;

/**
 * Universal chunking service that works with the standardized ConvertedDocument format.
 * <p>
 * This service orchestrates the chunking process by:
 * 1. Finding the appropriate chunker for the requested strategy
 * 2. Chunking the converted document using the universal chunker
 * 3. Providing a unified interface for all document chunking
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentChunkingService {

    private final List<UniversalChunker> universalChunkers;

    /**
     * Chunk a converted document according to the specified strategy
     */
    public List<UniversalChunker.Chunk> chunkDocument(ConvertedDocument document, ProcessDocumentRequest request) {
        try {
            log.info("Starting universal chunking for document: {} (strategy: {}, maxSize: {})",
                    document.getOriginalFilename(), request.getChunkingStrategy(), request.getMaxChunkSize());

            // Find the appropriate chunker
            UniversalChunker chunker = findChunker(request.getChunkingStrategy());

            // Chunk the document
            List<UniversalChunker.Chunk> chunks = chunker.chunkDocument(document, request);

            log.info("Successfully chunked document: {} ({} chunks created)",
                    document.getOriginalFilename(), chunks.size());

            return chunks;

        } catch (Exception e) {
            String errorMessage = String.format("Failed to chunk document %s: %s",
                    document.getOriginalFilename(), e.getMessage());
            log.error(errorMessage, e);
            throw new DocumentProcessingException(errorMessage, e);
        }
    }

    /**
     * Find the appropriate chunker for the given strategy
     */
    private UniversalChunker findChunker(ProcessDocumentRequest.ChunkingStrategy strategy) {
        log.info("Looking for chunker for strategy: {}", strategy);

        for (UniversalChunker chunker : universalChunkers) {
            if (chunker.canHandle(strategy)) {
                log.info("Found chunker: {} for strategy: {}",
                        chunker.getClass().getSimpleName(), strategy);
                return chunker;
            }
        }

        String errorMessage = String.format("No chunker found for strategy: %s", strategy);
        log.error(errorMessage);
        throw new DocumentProcessingException(errorMessage);
    }

    /**
     * Get all available chunkers
     */
    public List<UniversalChunker> getAllChunkers() {
        return universalChunkers;
    }

    /**
     * Get supported chunking strategies
     */
    public List<ProcessDocumentRequest.ChunkingStrategy> getSupportedStrategies() {
        return universalChunkers.stream()
                .map(UniversalChunker::getSupportedStrategy)
                .distinct()
                .toList();
    }

    /**
     * Check if a chunking strategy is supported
     */
    public boolean isStrategySupported(ProcessDocumentRequest.ChunkingStrategy strategy) {
        return universalChunkers.stream()
                .anyMatch(chunker -> chunker.canHandle(strategy));
    }

    /**
     * Get information about all available chunkers
     */
    public List<ChunkerInfo> getChunkerInfo() {
        return universalChunkers.stream()
                .map(chunker -> new ChunkerInfo(
                        chunker.getClass().getSimpleName(),
                        chunker.getSupportedStrategy(),
                        chunker.canHandle(ProcessDocumentRequest.ChunkingStrategy.AUTO)
                ))
                .toList();
    }

    /**
     * Information about a chunker
     */
    public record ChunkerInfo(
            String chunkerType,
            ProcessDocumentRequest.ChunkingStrategy supportedStrategy,
            boolean supportsAuto
    ) {
    }
} 