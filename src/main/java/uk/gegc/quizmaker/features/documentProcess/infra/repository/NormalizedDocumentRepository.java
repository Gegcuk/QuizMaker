package uk.gegc.quizmaker.features.documentProcess.infra.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;

import java.util.Optional;
import java.util.UUID;

@Repository("documentProcessRepository")
public interface NormalizedDocumentRepository extends JpaRepository<NormalizedDocument, UUID> {
    
    @Query("select d.charCount from NormalizedDocument d where d.id = :id")
    Integer findCharCountById(@Param("id") UUID id);

    @Query(value = """
            SELECT u.username AS ownerUsername,
                   u.is_active AS ownerActive,
                   u.is_deleted AS ownerDeleted
            FROM normalized_documents d
            LEFT JOIN users u ON u.user_id = d.owner_id
            WHERE d.id = :id
            FOR SHARE
            """, nativeQuery = true)
    Optional<OwnerAuthorization> findOwnerForAuthorization(@Param("id") UUID id);

    interface OwnerAuthorization {

        String getOwnerUsername();

        Boolean getOwnerActive();

        Boolean getOwnerDeleted();
    }
}
