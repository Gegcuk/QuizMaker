package uk.gegc.quizmaker.features.document.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.model.DocumentChunk;

import java.util.List;
import java.util.UUID;

@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {

    List<DocumentChunk> findByDocumentOrderByChunkIndex(Document document);

    Page<DocumentChunk> findByDocument(Document document, Pageable pageable);

    @Query("SELECT dc FROM DocumentChunk dc WHERE dc.document = :document AND dc.chunkType = :chunkType ORDER BY dc.chunkIndex")
    List<DocumentChunk> findByDocumentAndChunkType(@Param("document") Document document,
                                                   @Param("chunkType") DocumentChunk.ChunkType chunkType);

    @Query("SELECT COUNT(dc) FROM DocumentChunk dc WHERE dc.document = :document")
    long countByDocument(@Param("document") Document document);

    @Query("SELECT dc FROM DocumentChunk dc WHERE dc.document.id = :documentId AND dc.chunkIndex = :chunkIndex")
    DocumentChunk findByDocumentIdAndChunkIndex(@Param("documentId") UUID documentId,
                                                @Param("chunkIndex") Integer chunkIndex);

    void deleteByDocument(Document document);
} 