package uk.gegc.quizmaker.features.documentProcess.infra.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentNodeRepository extends JpaRepository<DocumentNode, UUID> {

    /**
     * Find all root nodes (nodes without parent) for a document, ordered by index
     */
    @Query("SELECT n FROM DocumentNode n WHERE n.document.id = :documentId AND n.parent IS NULL ORDER BY n.idx ASC")
    List<DocumentNode> findRootNodesByDocumentId(@Param("documentId") UUID documentId);

    /**
     * Find all nodes for a document in flat structure, ordered by start offset
     */
    @Query("SELECT n FROM DocumentNode n WHERE n.document.id = :documentId ORDER BY n.startOffset ASC")
    List<DocumentNode> findAllByDocumentIdOrderByStartOffset(@Param("documentId") UUID documentId);

    /**
     * Find a specific node by document and node ID
     */
    @Query("SELECT n FROM DocumentNode n WHERE n.document.id = :documentId AND n.id = :nodeId")
    Optional<DocumentNode> findByDocumentIdAndNodeId(@Param("documentId") UUID documentId, @Param("nodeId") UUID nodeId);

    /**
     * Find all nodes for a document ordered for tree building (no N+1)
     */
    @Query("""
        SELECT n FROM DocumentNode n
        WHERE n.document.id = :documentId
        ORDER BY CASE WHEN n.parent.id IS NULL THEN 0 ELSE 1 END,
                 n.parent.id, n.idx
    """)
    List<DocumentNode> findAllForTree(@Param("documentId") UUID documentId);

    /**
     * Count total nodes for a document
     */
    @Query("SELECT COUNT(n) FROM DocumentNode n WHERE n.document.id = :documentId")
    long countByDocumentId(@Param("documentId") UUID documentId);

    /**
     * Check if a node with the same index already exists for the same parent
     */
    @Query("SELECT COUNT(n) > 0 FROM DocumentNode n WHERE n.document.id = :documentId AND n.parent.id = :parentId AND n.idx = :idx")
    boolean existsByDocumentAndParentAndIdx(@Param("documentId") UUID documentId, @Param("parentId") UUID parentId, @Param("idx") Integer idx);

    /**
     * Check if a root node with the same index already exists for the document
     */
    @Query("SELECT COUNT(n) > 0 FROM DocumentNode n WHERE n.document.id = :documentId AND n.parent IS NULL AND n.idx = :idx")
    boolean existsByDocumentAndNullParentAndIdx(@Param("documentId") UUID documentId, @Param("idx") Integer idx);

    void deleteByDocument_Id(UUID documentId);

    /**
     * Find nodes by document ID ordered by start offset
     */
    @Query("SELECT n FROM DocumentNode n WHERE n.document.id = :documentId ORDER BY n.startOffset ASC")
    List<DocumentNode> findByDocument_IdOrderByStartOffset(@Param("documentId") UUID documentId);

    /**
     * Find nodes by document ID with depth less than specified value, ordered by start offset
     */
    @Query("SELECT n FROM DocumentNode n WHERE n.document.id = :documentId AND n.depth < :depth ORDER BY n.startOffset ASC")
    List<DocumentNode> findByDocument_IdAndDepthLessThanOrderByStartOffset(@Param("documentId") UUID documentId, @Param("depth") Short depth);
}
