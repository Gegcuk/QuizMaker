package uk.gegc.quizmaker.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import uk.gegc.quizmaker.dto.document.ProcessDocumentRequest;

@Data
@Configuration
@ConfigurationProperties(prefix = "document.chunking")
public class DocumentProcessingConfig {

    @Value("${document.chunking.max-chunk-size:50000}")
    private Integer defaultMaxChunkSize;

    @Value("${document.chunking.default-strategy:CHAPTER_BASED}")
    private String defaultStrategy;

    @Value("${document.chunking.min-chunk-size:1000}")
    private Integer minChunkSize = 1000; // Default minimum chunk size

    @Value("${document.chunking.aggressive-combination-threshold:5000}")
    private Integer aggressiveCombinationThreshold = 5000; // Default aggressive combination threshold

    public ProcessDocumentRequest createDefaultRequest() {
        ProcessDocumentRequest request = new ProcessDocumentRequest();
        request.setMaxChunkSize(defaultMaxChunkSize);
        request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.valueOf(defaultStrategy));
        request.setMinChunkSize(minChunkSize);
        request.setAggressiveCombinationThreshold(aggressiveCombinationThreshold);
        return request;
    }
} 