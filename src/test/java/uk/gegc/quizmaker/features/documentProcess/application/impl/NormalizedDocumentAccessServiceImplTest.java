package uk.gegc.quizmaker.features.documentProcess.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.document.application.DocumentIngestionMetrics;
import uk.gegc.quizmaker.features.documentProcess.api.dto.ExtractResponse;
import uk.gegc.quizmaker.features.documentProcess.api.dto.StructureFlatResponse;
import uk.gegc.quizmaker.features.documentProcess.api.dto.StructureTreeResponse;
import uk.gegc.quizmaker.features.documentProcess.application.DocumentIngestionService;
import uk.gegc.quizmaker.features.documentProcess.application.DocumentQueryService;
import uk.gegc.quizmaker.features.documentProcess.application.LlmClient;
import uk.gegc.quizmaker.features.documentProcess.application.NormalizedDocumentAccessMetrics;
import uk.gegc.quizmaker.features.documentProcess.application.NormalizedDocumentFilePreparationService;
import uk.gegc.quizmaker.features.documentProcess.application.StructureService;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.NormalizedDocumentRepository;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.NormalizedDocumentRepository.OwnerAuthorization;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Normalized document owner boundary")
class NormalizedDocumentAccessServiceImplTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private NormalizedDocumentRepository documentRepository;
    @Mock
    private DocumentIngestionService ingestionService;
    @Mock
    private DocumentQueryService queryService;
    @Mock
    private StructureService structureService;
    @Mock
    private NormalizedDocumentAccessMetrics metrics;
    @Mock
    private NormalizedDocumentFilePreparationService filePreparationService;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private DocumentIngestionMetrics ingestionMetrics;

    @InjectMocks
    private NormalizedDocumentAccessServiceImpl service;

    private UUID documentId;
    private User owner;
    private NormalizedDocument document;

    @BeforeEach
    void setUp() {
        documentId = UUID.randomUUID();
        owner = user("owner", true, false);
        document = new NormalizedDocument();
        document.setId(documentId);
        document.setOwner(owner);
        document.setCharCount(12);
        document.setNormalizedText("private text");
        document.setSource(NormalizedDocument.DocumentSource.TEXT);
        document.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);
    }

    @Nested
    @DisplayName("Creation")
    class Creation {

        @Test
        @DisplayName("resolves the owner from authentication and never accepts a client owner")
        void resolvesOwnerServerSide() {
            when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
            when(ingestionService.ingestFromText(owner, "notes.txt", "en", "private text"))
                    .thenReturn(document);

            NormalizedDocument result = service.ingestFromText("owner", "notes.txt", "en", "private text");

            assertThat(result).isSameAs(document);
            verify(ingestionService).ingestFromText(owner, "notes.txt", "en", "private text");
            verify(metrics).record(NormalizedDocumentAccessMetrics.Outcome.AUTHORIZED);
        }

        @Test
        @DisplayName("rejects a deleted authenticated owner before the upload is staged")
        void rejectsDeletedOwnerBeforeIngestion() {
            User deletedOwner = user("owner", true, true);
            when(userRepository.findByUsername("owner")).thenReturn(Optional.of(deletedOwner));
            MockMultipartFile file = new MockMultipartFile(
                    "file", "notes.txt", "text/plain", new byte[]{1});

            assertThatThrownBy(() -> service.ingestFromFile("owner", "notes.txt", file))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Document not found");

            verifyNoInteractions(filePreparationService, ingestionMetrics);
            verify(metrics).record(NormalizedDocumentAccessMetrics.Outcome.OWNER_DENIED);
        }

        @Test
        @DisplayName("does not publish when the owner is deleted while parsing is in progress")
        void rejectsOwnerDeletedBeforePublication() {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "notes.txt", "text/plain", "content".getBytes());
            NormalizedDocument prepared = preparedUpload();
            User deletedOwner = user("owner", true, true);
            deletedOwner.setId(owner.getId());
            when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
            when(filePreparationService.prepare(owner.getId().toString(), "notes.txt", file))
                    .thenReturn(prepared);
            executeTransactions();
            when(userRepository.findByIdForOwnershipWrite(owner.getId()))
                    .thenReturn(Optional.of(deletedOwner));

            assertThatThrownBy(() -> service.ingestFromFile("owner", "notes.txt", file))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Document not found");

            verify(documentRepository, never()).saveAndFlush(any());
            verify(ingestionMetrics).ingestionStopped();
        }
    }

    @Nested
    @DisplayName("Read authorization")
    class ReadAuthorization {

        @Test
        @DisplayName("returns metadata to the active owner")
        void returnsMetadataToOwner() {
            when(documentRepository.findOwnerForAuthorization(documentId))
                    .thenReturn(Optional.of(ownerAuthorization("owner", true, false)));
            when(queryService.getDocument(documentId)).thenReturn(document);

            assertThat(service.getDocument("owner", documentId)).isSameAs(document);

            verify(metrics).record(NormalizedDocumentAccessMetrics.Outcome.AUTHORIZED);
        }

        @Test
        @DisplayName("uses the same non-enumerating 404 for a wrong owner")
        void rejectsWrongOwner() {
            when(documentRepository.findOwnerForAuthorization(documentId))
                    .thenReturn(Optional.of(ownerAuthorization("owner", true, false)));

            assertNotFound(() -> service.getDocument("other-user", documentId));

            verify(metrics).record(NormalizedDocumentAccessMetrics.Outcome.OWNER_DENIED);
        }

        @Test
        @DisplayName("uses the same non-enumerating 404 for a nonexistent document")
        void rejectsMissingDocument() {
            when(documentRepository.findOwnerForAuthorization(documentId)).thenReturn(Optional.empty());

            assertNotFound(() -> service.getDocument("owner", documentId));

            verify(metrics).record(NormalizedDocumentAccessMetrics.Outcome.OWNER_DENIED);
        }

        @Test
        @DisplayName("quarantines a legacy document whose owner is null")
        void rejectsLegacyUnownedDocument() {
            when(documentRepository.findOwnerForAuthorization(documentId))
                    .thenReturn(Optional.of(ownerAuthorization(null, null, null)));

            assertNotFound(() -> service.getDocument("owner", documentId));

            verify(metrics).record(NormalizedDocumentAccessMetrics.Outcome.LEGACY_DENIED);
        }

        @Test
        @DisplayName("rejects a document after its owner has been soft deleted")
        void rejectsDocumentWithDeletedOwner() {
            when(documentRepository.findOwnerForAuthorization(documentId))
                    .thenReturn(Optional.of(ownerAuthorization("owner", true, true)));

            assertNotFound(() -> service.getDocument("owner", documentId));

            verify(metrics).record(NormalizedDocumentAccessMetrics.Outcome.OWNER_DENIED);
        }

        @Test
        @DisplayName("defaults to denial when principal identity is absent")
        void rejectsMissingPrincipal() {
            assertNotFound(() -> service.getDocument(" ", documentId));

            verify(metrics).record(NormalizedDocumentAccessMetrics.Outcome.UNAUTHENTICATED);
            verifyNoInteractions(documentRepository);
        }

        @Test
        @DisplayName("records a database outage without converting it into an authorization result")
        void propagatesDatabaseOutage() {
            DataAccessResourceFailureException outage = new DataAccessResourceFailureException("offline");
            when(documentRepository.findOwnerForAuthorization(documentId)).thenThrow(outage);

            assertThatThrownBy(() -> service.getDocument("owner", documentId)).isSameAs(outage);

            verify(metrics).record(NormalizedDocumentAccessMetrics.Outcome.DEPENDENCY_FAILURE);
            verify(metrics, never()).record(NormalizedDocumentAccessMetrics.Outcome.AUTHORIZED);
        }

        @Test
        @DisplayName("does not let telemetry failure change an authorized decision")
        void telemetryFailureDoesNotChangeAuthorization() {
            when(documentRepository.findOwnerForAuthorization(documentId))
                    .thenReturn(Optional.of(ownerAuthorization("owner", true, false)));
            when(queryService.getDocument(documentId)).thenReturn(document);
            doThrow(new IllegalStateException("metrics unavailable"))
                    .when(metrics).record(NormalizedDocumentAccessMetrics.Outcome.AUTHORIZED);

            assertThat(service.getDocument("owner", documentId)).isSameAs(document);
        }
    }

    @Nested
    @DisplayName("Owner-scoped operations")
    class OwnerScopedOperations {

        @Test
        @DisplayName("authorizes once before text slicing")
        void authorizesTextSlice() {
            when(documentRepository.findOwnerForAuthorization(documentId))
                    .thenReturn(Optional.of(ownerAuthorization("owner", true, false)));
            when(queryService.getTextSlice(documentId, 0, 7)).thenReturn("private");

            assertThat(service.getTextSlice("owner", documentId, 0, 7)).isEqualTo("private");

            verify(queryService).getTextSlice(documentId, 0, 7);
        }

        @Test
        @DisplayName("authorizes a text-length lookup without loading document content at the boundary")
        void authorizesTextLength() {
            when(documentRepository.findOwnerForAuthorization(documentId))
                    .thenReturn(Optional.of(ownerAuthorization("owner", true, false)));
            when(queryService.getTextLength(documentId)).thenReturn(12);

            assertThat(service.getTextLength("owner", documentId)).isEqualTo(12);

            verify(queryService).getTextLength(documentId);
            verify(queryService, never()).getDocument(documentId);
        }

        @Test
        @DisplayName("keeps tree, flat, build, and extraction behind the same owner check")
        void authorizesEveryStructureOperation() {
            UUID nodeId = UUID.randomUUID();
            StructureTreeResponse tree = new StructureTreeResponse(documentId, List.of(), 0);
            StructureFlatResponse flat = new StructureFlatResponse(documentId, List.of(), 0);
            ExtractResponse extract = new ExtractResponse(documentId, nodeId, "Section", 0, 7, "private");
            when(documentRepository.findOwnerForAuthorization(documentId))
                    .thenReturn(Optional.of(ownerAuthorization("owner", true, false)));
            when(structureService.getTree(documentId)).thenReturn(tree);
            when(structureService.getFlat(documentId)).thenReturn(flat);
            when(structureService.extractByNode(documentId, nodeId)).thenReturn(extract);

            assertThat(service.getTree("owner", documentId)).isSameAs(tree);
            assertThat(service.getFlat("owner", documentId)).isSameAs(flat);
            service.buildStructure("owner", documentId);
            assertThat(service.extractByNode("owner", documentId, nodeId)).isSameAs(extract);

            verify(structureService).buildStructure(documentId);
        }

        @Test
        @DisplayName("does not call structure collaborators for a non-owner")
        void rejectsStructureBeforeDelegation() {
            when(documentRepository.findOwnerForAuthorization(documentId))
                    .thenReturn(Optional.of(ownerAuthorization("owner", true, false)));

            assertNotFound(() -> service.getTree("other-user", documentId));

            verifyNoInteractions(structureService);
        }

        @Test
        @DisplayName("records a fake AI failure as a bounded dependency outcome")
        void recordsFakeAiFailure() {
            when(documentRepository.findOwnerForAuthorization(documentId))
                    .thenReturn(Optional.of(ownerAuthorization("owner", true, false)));
            doThrow(new IllegalStateException(
                    "safe wrapper",
                    new LlmClient.LlmException("CANARY_RAW_AI_RESPONSE")
            )).when(structureService).buildStructure(documentId);

            assertThatThrownBy(() -> service.buildStructure("owner", documentId))
                    .isInstanceOf(IllegalStateException.class);

            verify(metrics).record(NormalizedDocumentAccessMetrics.Outcome.DEPENDENCY_FAILURE);
        }
    }

    private void assertNotFound(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Document not found");
    }

    private User user(String username, boolean active, boolean deleted) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setActive(active);
        user.setDeleted(deleted);
        return user;
    }

    private NormalizedDocument preparedUpload() {
        NormalizedDocument prepared = new NormalizedDocument();
        prepared.setOriginalName("notes.txt");
        prepared.setMime("text/plain");
        prepared.setSource(NormalizedDocument.DocumentSource.UPLOAD);
        prepared.setNormalizedText("content");
        prepared.setCharCount(7);
        prepared.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);
        return prepared;
    }

    @SuppressWarnings("unchecked")
    private void executeTransactions() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.<TransactionCallback<NormalizedDocument>>getArgument(0)
                        .doInTransaction(null));
    }

    private OwnerAuthorization ownerAuthorization(String username, Boolean active, Boolean deleted) {
        return new OwnerAuthorization() {
            @Override
            public String getOwnerUsername() {
                return username;
            }

            @Override
            public Boolean getOwnerActive() {
                return active;
            }

            @Override
            public Boolean getOwnerDeleted() {
                return deleted;
            }
        };
    }
}
