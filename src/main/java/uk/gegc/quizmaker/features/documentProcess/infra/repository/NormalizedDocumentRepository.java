package uk.gegc.quizmaker.features.documentProcess.infra.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;

import java.util.UUID;

@Repository("documentProcessRepository")
public interface NormalizedDocumentRepository extends JpaRepository<NormalizedDocument, UUID> {
}
