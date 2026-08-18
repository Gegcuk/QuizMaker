package uk.gegc.quizmaker.features.documentProcess.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("V74 normalized-document owner migration")
class NormalizedDocumentOwnerMigrationTest {

    @Test
    @DisplayName("adds a nullable owner foreign key, lookup index, and SET NULL deletion policy without guessing legacy owners")
    void definesAdditiveQuarantineMigration() throws Exception {
        String migration = new ClassPathResource(
                "db/migration/V74__add_normalized_document_owner.sql"
        ).getContentAsString(StandardCharsets.UTF_8);
        String normalized = migration.toLowerCase();

        assertThat(normalized)
                .contains("add column owner_id binary(16) null")
                .contains("create index ix_normalized_documents_owner_id")
                .contains("add constraint fk_normalized_documents_owner")
                .contains("foreign key (owner_id) references users(user_id)")
                .contains("on delete set null")
                .doesNotContain("update normalized_documents")
                .doesNotContain("owner_id =");
    }
}
