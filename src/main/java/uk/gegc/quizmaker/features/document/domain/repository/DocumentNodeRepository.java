package uk.gegc.quizmaker.features.document.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uk.gegc.quizmaker.features.document.domain.model.DocumentNode;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentNodeRepository extends JpaRepository<DocumentNode, UUID> {

    // Fixed derived method names with proper property traversal
    List<DocumentNode> findByDocument_IdAndParentIsNullOrderByOrdinalAsc(UUID documentId);
    
    List<DocumentNode> findByDocument_IdAndParent_IdOrderByOrdinalAsc(UUID documentId, UUID parentId);
    
    List<DocumentNode> findBySourceVersionHash(String sourceVersionHash);
    
    boolean existsByDocument_Id(UUID documentId);
    
    long countByDocument_Id(UUID documentId);
    
    void deleteByDocument_Id(UUID documentId);

    // Global ordering queries (using startOffset instead of ordinal)
    @Query("SELECT dn FROM DocumentNode dn WHERE dn.document.id = :documentId ORDER BY dn.startOffset ASC, dn.endOffset ASC")
    List<DocumentNode> findByDocumentIdOrderByStartOffset(@Param("documentId") UUID documentId);

    @Query("SELECT dn FROM DocumentNode dn WHERE dn.document.id = :documentId AND dn.type = :type ORDER BY dn.startOffset ASC")
    List<DocumentNode> findByDocumentIdAndTypeOrderByStartOffset(@Param("documentId") UUID documentId, @Param("type") DocumentNode.NodeType type);

    // Range scan for overlapping nodes
    @Query("""
        SELECT dn FROM DocumentNode dn
        WHERE dn.document.id = :documentId
          AND dn.startOffset < :endOffset
          AND dn.endOffset > :startOffset
        ORDER BY dn.startOffset ASC
        """)
    List<DocumentNode> findOverlapping(@Param("documentId") UUID documentId, @Param("startOffset") int startOffset, @Param("endOffset") int endOffset);

    // Helper for finding next ordinal for a specific parent
    @Query("SELECT COALESCE(MAX(dn.ordinal), 0) + 1 FROM DocumentNode dn WHERE dn.document.id = :documentId AND dn.parent.id = :parentId")
    Integer findNextOrdinalForParent(@Param("documentId") UUID documentId, @Param("parentId") UUID parentId);

    // Helper for finding next ordinal for root nodes
    @Query("SELECT COALESCE(MAX(dn.ordinal), 0) + 1 FROM DocumentNode dn WHERE dn.document.id = :documentId AND dn.parent IS NULL")
    Integer nextRootOrdinal(@Param("documentId") UUID documentId);
}
