package uk.gegc.quizmaker.features.document.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import uk.gegc.quizmaker.features.document.application.DocumentSourceFileCleanup;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentChunkRepository;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.DocumentNotFoundException;
import uk.gegc.quizmaker.shared.exception.UserNotAuthorizedException;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Document deletion service")
class DocumentDeletionServiceImplTest {

    private DocumentRepository documentRepository;
    private DocumentChunkRepository chunkRepository;
    private UserRepository userRepository;
    private DocumentSourceFileCleanup sourceFileCleanup;
    private DocumentDeletionServiceImpl service;
    private User owner;
    private Document document;
    private Path publishedPath;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        chunkRepository = mock(DocumentChunkRepository.class);
        userRepository = mock(UserRepository.class);
        sourceFileCleanup = mock(DocumentSourceFileCleanup.class);
        service = new DocumentDeletionServiceImpl(
                documentRepository, chunkRepository, userRepository, sourceFileCleanup);

        owner = new User();
        owner.setId(UUID.randomUUID());
        owner.setUsername("owner");
        publishedPath = Path.of("/published/document.pdf");
        document = new Document();
        document.setId(UUID.randomUUID());
        document.setUploadedBy(owner);
        document.setFilePath(publishedPath.toString());
    }

    @Test
    @DisplayName("Deletes database state before scheduling source removal after commit")
    void deletesDatabaseStateBeforeSchedulingSourceCleanup() {
        when(documentRepository.findByIdForDeletion(document.getId())).thenReturn(Optional.of(document));
        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));

        service.deleteDocument("owner", document.getId());

        InOrder order = inOrder(documentRepository, userRepository, chunkRepository, sourceFileCleanup);
        order.verify(documentRepository).findByIdForDeletion(document.getId());
        order.verify(userRepository).findByUsername("owner");
        order.verify(chunkRepository).deleteAllByDocumentId(document.getId());
        order.verify(documentRepository).delete(document);
        order.verify(sourceFileCleanup).deleteAfterCommit(publishedPath);
    }

    @Test
    @DisplayName("Rejects a non-owner before database or storage mutation")
    void rejectsNonOwnerBeforeMutation() {
        User otherUser = new User();
        otherUser.setId(UUID.randomUUID());
        otherUser.setUsername("other");
        when(documentRepository.findByIdForDeletion(document.getId())).thenReturn(Optional.of(document));
        when(userRepository.findByUsername("other")).thenReturn(Optional.of(otherUser));

        assertThatThrownBy(() -> service.deleteDocument("other", document.getId()))
                .isInstanceOf(UserNotAuthorizedException.class);

        verify(chunkRepository, never()).deleteAllByDocumentId(document.getId());
        verify(documentRepository, never()).delete(document);
        verify(sourceFileCleanup, never()).deleteAfterCommit(publishedPath);
    }

    @Test
    @DisplayName("Returns the existing not-found failure before resolving a user or mutating state")
    void rejectsMissingDocumentBeforeMutation() {
        when(documentRepository.findByIdForDeletion(document.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteDocument("owner", document.getId()))
                .isInstanceOf(DocumentNotFoundException.class);

        verify(userRepository, never()).findByUsername("owner");
        verify(chunkRepository, never()).deleteAllByDocumentId(document.getId());
        verify(sourceFileCleanup, never()).deleteAfterCommit(publishedPath);
    }

    @Test
    @DisplayName("Rejects a missing authenticated user without exposing the username or mutating state")
    void rejectsMissingAuthenticatedUserBeforeMutation() {
        when(documentRepository.findByIdForDeletion(document.getId())).thenReturn(Optional.of(document));
        when(userRepository.findByUsername("missing-owner")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteDocument("missing-owner", document.getId()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Authenticated user not found")
                .hasMessageNotContaining("missing-owner");

        verify(chunkRepository, never()).deleteAllByDocumentId(document.getId());
        verify(documentRepository, never()).delete(document);
        verify(sourceFileCleanup, never()).deleteAfterCommit(publishedPath);
    }

    @Test
    @DisplayName("Does not schedule source removal when chunk deletion fails")
    void preservesSourceSchedulingWhenChunkDeletionFails() {
        RuntimeException databaseFailure = new RuntimeException("simulated chunk delete failure");
        when(documentRepository.findByIdForDeletion(document.getId())).thenReturn(Optional.of(document));
        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        org.mockito.Mockito.doThrow(databaseFailure).when(chunkRepository).deleteAllByDocumentId(document.getId());

        assertThatThrownBy(() -> service.deleteDocument("owner", document.getId()))
                .isSameAs(databaseFailure);

        verify(documentRepository, never()).delete(document);
        verify(sourceFileCleanup, never()).deleteAfterCommit(publishedPath);
    }

    @Test
    @DisplayName("Does not schedule source removal when document deletion fails")
    void preservesSourceSchedulingWhenDocumentDeletionFails() {
        RuntimeException databaseFailure = new RuntimeException("simulated document delete failure");
        when(documentRepository.findByIdForDeletion(document.getId())).thenReturn(Optional.of(document));
        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        org.mockito.Mockito.doThrow(databaseFailure).when(documentRepository).delete(document);

        assertThatThrownBy(() -> service.deleteDocument("owner", document.getId()))
                .isSameAs(databaseFailure);

        verify(chunkRepository).deleteAllByDocumentId(document.getId());
        verify(sourceFileCleanup, never()).deleteAfterCommit(publishedPath);
    }
}
