package uk.gegc.quizmaker.features.documentProcess.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.NormalizedDocumentRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test-mysql")
@DisplayName("NormalizedDocument Status Migration Integration Tests")
class NormalizedDocumentStatusMigrationIT {

    @Autowired
    private NormalizedDocumentRepository documentRepository;

    @Test
    @DisplayName("statusEnum_includesStructured_roundTripPersistRead")
    @Transactional
    void statusEnum_includesStructured_roundTripPersistRead() {
        // Given
        NormalizedDocument document = new NormalizedDocument();
        document.setOriginalName("test-structured.txt");
        document.setSource(NormalizedDocument.DocumentSource.TEXT);
        document.setStatus(NormalizedDocument.DocumentStatus.STRUCTURED);

        // When - persist
        NormalizedDocument savedDocument = documentRepository.save(document);
        UUID documentId = savedDocument.getId();

        // Clear the persistence context to force a fresh read from database
        documentRepository.flush();

        // When - read back
        Optional<NormalizedDocument> foundDocument = documentRepository.findById(documentId);

        // Then
        assertThat(foundDocument).isPresent();
        assertThat(foundDocument.get().getStatus()).isEqualTo(NormalizedDocument.DocumentStatus.STRUCTURED);
        assertThat(foundDocument.get().getOriginalName()).isEqualTo("test-structured.txt");
    }

    @Test
    @DisplayName("allStatusValues_canBePersistedAndRead")
    @Transactional
    void allStatusValues_canBePersistedAndRead() {
        // Test all enum values to ensure the migration didn't break existing ones
        NormalizedDocument.DocumentStatus[] allStatuses = NormalizedDocument.DocumentStatus.values();

        for (NormalizedDocument.DocumentStatus status : allStatuses) {
            // Given
            NormalizedDocument document = new NormalizedDocument();
            document.setOriginalName("test-" + status.name().toLowerCase() + ".txt");
            document.setSource(NormalizedDocument.DocumentSource.TEXT);
            document.setStatus(status);

            // When - persist
            NormalizedDocument savedDocument = documentRepository.save(document);
            UUID documentId = savedDocument.getId();

            // Clear the persistence context
            documentRepository.flush();

            // When - read back
            Optional<NormalizedDocument> foundDocument = documentRepository.findById(documentId);

            // Then
            assertThat(foundDocument).isPresent();
            assertThat(foundDocument.get().getStatus()).isEqualTo(status);
            assertThat(foundDocument.get().getOriginalName()).isEqualTo("test-" + status.name().toLowerCase() + ".txt");
        }
    }

    @Test
    @DisplayName("structuredStatus_equalsExpectedValue")
    void structuredStatus_equalsExpectedValue() {
        // Verify the STRUCTURED enum value is what we expect
        assertThat(NormalizedDocument.DocumentStatus.STRUCTURED.name()).isEqualTo("STRUCTURED");
        assertThat(NormalizedDocument.DocumentStatus.valueOf("STRUCTURED")).isEqualTo(NormalizedDocument.DocumentStatus.STRUCTURED);
    }
}
