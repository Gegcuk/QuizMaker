package uk.gegc.quizmaker.features.document.api.dto;

import lombok.Data;
import uk.gegc.quizmaker.features.document.domain.model.DocumentChunk;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class DocumentChunkDto {
    private UUID id;
    private Integer chunkIndex;
    private String title;
    private String content;
    private Integer startPage;
    private Integer endPage;
    private Integer wordCount;
    private Integer characterCount;
    private LocalDateTime createdAt;
    private String chapterTitle;
    private String sectionTitle;
    private Integer chapterNumber;
    private Integer sectionNumber;
    private DocumentChunk.ChunkType chunkType;
} 