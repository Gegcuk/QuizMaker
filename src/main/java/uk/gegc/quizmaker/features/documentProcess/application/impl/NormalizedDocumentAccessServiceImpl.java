package uk.gegc.quizmaker.features.documentProcess.application.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import uk.gegc.quizmaker.features.documentProcess.api.dto.ExtractResponse;
import uk.gegc.quizmaker.features.documentProcess.api.dto.StructureFlatResponse;
import uk.gegc.quizmaker.features.documentProcess.api.dto.StructureTreeResponse;
import uk.gegc.quizmaker.features.documentProcess.application.DocumentIngestionService;
import uk.gegc.quizmaker.features.documentProcess.application.DocumentQueryService;
import uk.gegc.quizmaker.features.documentProcess.application.LlmClient;
import uk.gegc.quizmaker.features.documentProcess.application.NormalizedDocumentAccessMetrics;
import uk.gegc.quizmaker.features.documentProcess.application.NormalizedDocumentAccessService;
import uk.gegc.quizmaker.features.documentProcess.application.StructureService;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.NormalizedDocumentRepository;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.NormalizedDocumentRepository.OwnerAuthorization;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.features.conversion.domain.ConversionException;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.util.UUID;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
@Slf4j
public class NormalizedDocumentAccessServiceImpl implements NormalizedDocumentAccessService {

    private static final String DOCUMENT_NOT_FOUND = "Document not found";

    private final UserRepository userRepository;
    private final NormalizedDocumentRepository documentRepository;
    private final DocumentIngestionService ingestionService;
    private final DocumentQueryService queryService;
    private final StructureService structureService;
    private final NormalizedDocumentAccessMetrics metrics;

    @Override
    @Transactional
    public NormalizedDocument ingestFromText(
            String username,
            String originalName,
            String language,
            String text
    ) {
        User owner = resolveActiveOwner(username);
        record(NormalizedDocumentAccessMetrics.Outcome.AUTHORIZED);
        try {
            return ingestionService.ingestFromText(owner, originalName, language, text);
        } catch (RuntimeException failure) {
            recordDependencyFailure(failure);
            throw failure;
        }
    }

    @Override
    @Transactional
    public NormalizedDocument ingestFromFile(String username, String originalName, byte[] bytes) {
        User owner = resolveActiveOwner(username);
        record(NormalizedDocumentAccessMetrics.Outcome.AUTHORIZED);
        try {
            return ingestionService.ingestFromFile(owner, originalName, bytes);
        } catch (RuntimeException failure) {
            recordDependencyFailure(failure);
            throw failure;
        }
    }

    @Override
    @Transactional
    public NormalizedDocument getDocument(String username, UUID documentId) {
        return withOwnedDocument(username, documentId, () -> queryService.getDocument(documentId));
    }

    @Override
    @Transactional
    public int getTextLength(String username, UUID documentId) {
        return withOwnedDocument(username, documentId, () -> queryService.getTextLength(documentId));
    }

    @Override
    @Transactional
    public String getTextSlice(String username, UUID documentId, int start, int end) {
        return withOwnedDocument(
                username,
                documentId,
                () -> queryService.getTextSlice(documentId, start, end)
        );
    }

    @Override
    @Transactional
    public StructureTreeResponse getTree(String username, UUID documentId) {
        return withOwnedDocument(username, documentId, () -> structureService.getTree(documentId));
    }

    @Override
    @Transactional
    public StructureFlatResponse getFlat(String username, UUID documentId) {
        return withOwnedDocument(username, documentId, () -> structureService.getFlat(documentId));
    }

    @Override
    @Transactional
    public void buildStructure(String username, UUID documentId) {
        withOwnedDocument(username, documentId, () -> {
            structureService.buildStructure(documentId);
            return null;
        });
    }

    @Override
    @Transactional
    public ExtractResponse extractByNode(String username, UUID documentId, UUID nodeId) {
        return withOwnedDocument(
                username,
                documentId,
                () -> structureService.extractByNode(documentId, nodeId)
        );
    }

    private User resolveActiveOwner(String username) {
        requirePrincipal(username);
        try {
            User owner = userRepository.findByUsername(username).orElse(null);
            if (owner == null || !owner.isActive() || owner.isDeleted()) {
                record(NormalizedDocumentAccessMetrics.Outcome.OWNER_DENIED);
                throw new ResourceNotFoundException(DOCUMENT_NOT_FOUND);
            }
            return owner;
        } catch (RuntimeException failure) {
            recordDependencyFailure(failure);
            throw failure;
        }
    }

    private <T> T withOwnedDocument(
            String username,
            UUID documentId,
            Supplier<T> operation
    ) {
        requirePrincipal(username);

        OwnerAuthorization authorization;
        try {
            authorization = documentRepository.findOwnerForAuthorization(documentId).orElse(null);
        } catch (RuntimeException failure) {
            recordDependencyFailure(failure);
            throw failure;
        }

        if (authorization == null) {
            throw denied(NormalizedDocumentAccessMetrics.Outcome.OWNER_DENIED);
        }

        String ownerUsername = authorization.getOwnerUsername();
        if (!StringUtils.hasText(ownerUsername)) {
            throw denied(NormalizedDocumentAccessMetrics.Outcome.LEGACY_DENIED);
        }
        if (!Boolean.TRUE.equals(authorization.getOwnerActive())
                || Boolean.TRUE.equals(authorization.getOwnerDeleted())
                || !username.equals(ownerUsername)) {
            throw denied(NormalizedDocumentAccessMetrics.Outcome.OWNER_DENIED);
        }

        record(NormalizedDocumentAccessMetrics.Outcome.AUTHORIZED);
        try {
            return operation.get();
        } catch (RuntimeException failure) {
            recordDependencyFailure(failure);
            throw failure;
        }
    }

    private void requirePrincipal(String username) {
        if (!StringUtils.hasText(username)) {
            record(NormalizedDocumentAccessMetrics.Outcome.UNAUTHENTICATED);
            throw new ResourceNotFoundException(DOCUMENT_NOT_FOUND);
        }
    }

    private ResourceNotFoundException denied(NormalizedDocumentAccessMetrics.Outcome outcome) {
        record(outcome);
        return new ResourceNotFoundException(DOCUMENT_NOT_FOUND);
    }

    private void record(NormalizedDocumentAccessMetrics.Outcome outcome) {
        try {
            metrics.record(outcome);
        } catch (RuntimeException telemetryFailure) {
            log.warn("Normalized-document access metric could not be recorded");
        }
    }

    private void recordDependencyFailure(RuntimeException failure) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth++ < 8) {
            if (current instanceof DataAccessException
                    || current instanceof ConversionException
                    || current instanceof LlmClient.LlmException) {
                record(NormalizedDocumentAccessMetrics.Outcome.DEPENDENCY_FAILURE);
                return;
            }
            current = current.getCause();
        }
    }
}
