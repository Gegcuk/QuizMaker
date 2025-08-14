package uk.gegc.quizmaker.repository.document;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.model.document.Document;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Page<Document> findByUploadedBy(User user, Pageable pageable);

    List<Document> findByUploadedByAndStatus(User user, Document.DocumentStatus status);

    @Query("SELECT d FROM Document d WHERE d.uploadedBy = :user AND d.status = :status")
    Page<Document> findByUserAndStatus(@Param("user") User user,
                                       @Param("status") Document.DocumentStatus status,
                                       Pageable pageable);

    boolean existsByOriginalFilenameAndUploadedBy(String filename, User user);

    /**
     * Find document by ID with chunks eagerly loaded
     */
    @Query("SELECT d FROM Document d LEFT JOIN FETCH d.chunks WHERE d.id = :id")
    Optional<Document> findByIdWithChunks(@Param("id") UUID id);
} 