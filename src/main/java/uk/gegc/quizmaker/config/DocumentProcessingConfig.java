package uk.gegc.quizmaker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import uk.gegc.quizmaker.dto.document.ProcessDocumentRequest;

@Data
@Configuration
@ConfigurationProperties(prefix = "document.chunking")
public class DocumentProcessingConfig {

    private Integer defaultMaxChunkSize;
    private String defaultStrategy;

    public ProcessDocumentRequest createDefaultRequest() {
        ProcessDocumentRequest request = new ProcessDocumentRequest();
        request.setMaxChunkSize(defaultMaxChunkSize);
        request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.valueOf(defaultStrategy));
        return request;
    }
} 