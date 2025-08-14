package uk.gegc.quizmaker.features.document.infra.mapping;

import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.document.api.dto.DocumentChunkDto;
import uk.gegc.quizmaker.features.document.api.dto.DocumentDto;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.model.DocumentChunk;

import java.util.stream.Collectors;

@Component
public class DocumentMapper {

    public DocumentDto toDto(Document document) {
        if (document == null) {
            return null;
        }

        DocumentDto dto = new DocumentDto();
        dto.setId(document.getId());
        dto.setOriginalFilename(document.getOriginalFilename());
        dto.setContentType(document.getContentType());
        dto.setFileSize(document.getFileSize());
        dto.setStatus(document.getStatus());
        dto.setUploadedAt(document.getUploadedAt());
        dto.setProcessedAt(document.getProcessedAt());
        dto.setTitle(document.getTitle());
        dto.setAuthor(document.getAuthor());
        dto.setTotalPages(document.getTotalPages());
        dto.setTotalChunks(document.getTotalChunks());
        dto.setProcessingError(document.getProcessingError());

        if (document.getChunks() != null) {
            dto.setChunks(document.getChunks().stream()
                    .map(this::toChunkDto)
                    .collect(Collectors.toList()));
        }

        return dto;
    }

    public DocumentChunkDto toChunkDto(DocumentChunk chunk) {
        if (chunk == null) {
            return null;
        }

        DocumentChunkDto dto = new DocumentChunkDto();
        dto.setId(chunk.getId());
        dto.setChunkIndex(chunk.getChunkIndex());
        dto.setTitle(chunk.getTitle());
        dto.setContent(chunk.getContent());
        dto.setStartPage(chunk.getStartPage());
        dto.setEndPage(chunk.getEndPage());
        dto.setWordCount(chunk.getWordCount());
        dto.setCharacterCount(chunk.getCharacterCount());
        dto.setCreatedAt(chunk.getCreatedAt());
        dto.setChapterTitle(chunk.getChapterTitle());
        dto.setSectionTitle(chunk.getSectionTitle());
        dto.setChapterNumber(chunk.getChapterNumber());
        dto.setSectionNumber(chunk.getSectionNumber());
        dto.setChunkType(chunk.getChunkType());

        return dto;
    }
} 