package uk.gegc.quizmaker.features.document.application.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.document.application.DocumentDeletionService;
import uk.gegc.quizmaker.features.document.application.DocumentSourceFileCleanup;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentChunkRepository;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.DocumentNotFoundException;
import uk.gegc.quizmaker.shared.exception.UserNotAuthorizedException;

import java.nio.file.Path;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentDeletionServiceImpl implements DocumentDeletionService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final UserRepository userRepository;
    private final DocumentSourceFileCleanup sourceFileCleanup;

    @Override
    @Transactional
    public void deleteDocument(String username, UUID documentId) {
        Document document = documentRepository.findByIdForDeletion(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId.toString(), "Document not found"));
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"));

        if (!document.getUploadedBy().equals(user)) {
            throw new UserNotAuthorizedException(username, documentId.toString(), "delete");
        }

        Path publishedPath = Path.of(document.getFilePath());
        chunkRepository.deleteAllByDocumentId(documentId);
        documentRepository.delete(document);
        sourceFileCleanup.deleteAfterCommit(publishedPath);
    }
}
