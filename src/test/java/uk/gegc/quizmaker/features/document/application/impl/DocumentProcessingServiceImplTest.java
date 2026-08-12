package uk.gegc.quizmaker.features.document.application.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.document.api.dto.ProcessDocumentRequest;
import uk.gegc.quizmaker.features.document.application.DocumentChunkingService;
import uk.gegc.quizmaker.features.document.application.DocumentDeletionService;
import uk.gegc.quizmaker.features.document.application.DocumentIngestionMetrics;
import uk.gegc.quizmaker.features.document.application.DocumentParseRequest;
import uk.gegc.quizmaker.features.document.application.DocumentParseExecutor;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingLimits;
import uk.gegc.quizmaker.features.document.application.DocumentUploadStagingService;
import uk.gegc.quizmaker.features.document.application.StagedDocumentUpload;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentChunkRepository;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.features.document.infra.converter.UniversalChunker;
import uk.gegc.quizmaker.features.document.infra.mapping.DocumentMapper;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.DocumentProcessingException;
import uk.gegc.quizmaker.shared.exception.DocumentProcessingCapacityExceededException;
import uk.gegc.quizmaker.shared.exception.DocumentProcessingTimeoutException;
import uk.gegc.quizmaker.shared.exception.DocumentResourceLimitException;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Document processing service")
class DocumentProcessingServiceImplTest {

    @Test
    @DisplayName("Reprocessing keeps existing chunks intact when parsing reaches a resource limit")
    void reprocessingDoesNotDeleteExistingChunksBeforeSuccessfulParse() {
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        DocumentChunkRepository chunkRepository = mock(DocumentChunkRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        DocumentParseExecutor parseExecutor = mock(DocumentParseExecutor.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        DocumentIngestionMetrics metrics = mock(DocumentIngestionMetrics.class);

        User owner = new User();
        owner.setId(UUID.randomUUID());
        owner.setUsername("owner");
        Document document = new Document();
        document.setId(UUID.randomUUID());
        document.setUploadedBy(owner);
        document.setFilePath("/unused/book.pdf");
        document.setOriginalFilename("book.pdf");
        document.setContentType("application/pdf");
        document.setFileSize(100L);

        when(documentRepository.findByIdWithChunksAndUser(document.getId())).thenReturn(Optional.of(document));
        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(parseExecutor.execute(eq("owner"), any())).thenThrow(
                new DocumentProcessingTimeoutException()
        );

        DocumentProcessingServiceImpl service = new DocumentProcessingServiceImpl(
                documentRepository,
                chunkRepository,
                userRepository,
                mock(DocumentMapper.class),
                mock(DocumentChunkingService.class),
                mock(DocumentUploadStagingService.class),
                parseExecutor,
                DocumentProcessingLimits.defaults(),
                transactionTemplate,
                mock(DocumentDeletionService.class),
                metrics
        );

        ProcessDocumentRequest request = new ProcessDocumentRequest();
        request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED);
        request.setMaxChunkSize(1_000);

        assertThatThrownBy(() -> service.reprocessDocument("owner", document.getId(), request))
                .isInstanceOf(DocumentResourceLimitException.class);

        verify(chunkRepository, never()).deleteByDocument(any(Document.class));
        verify(transactionTemplate, never()).execute(any());
        verify(metrics).recordEvent(
                DocumentIngestionMetrics.Stage.CONVERSION,
                DocumentIngestionMetrics.Outcome.REJECTED,
                DocumentIngestionMetrics.Reason.TIMEOUT);
        verify(metrics).recordEvent(
                DocumentIngestionMetrics.Stage.PROCESSING,
                DocumentIngestionMetrics.Outcome.REJECTED,
                DocumentIngestionMetrics.Reason.TIMEOUT);
    }

    @Test
    @DisplayName("Keeps the legacy storeChunks false behavior while publishing document metadata atomically")
    void uploadDoesNotPersistChunksWhenRequestDisablesChunkStorage() throws Exception {
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        DocumentChunkRepository chunkRepository = mock(DocumentChunkRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        DocumentChunkingService chunkingService = mock(DocumentChunkingService.class);
        DocumentUploadStagingService stagingService = mock(DocumentUploadStagingService.class);
        DocumentParseExecutor parseExecutor = mock(DocumentParseExecutor.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        DocumentIngestionMetrics metrics = mock(DocumentIngestionMetrics.class);

        User owner = new User();
        owner.setId(UUID.randomUUID());
        owner.setUsername("owner");
        StagedDocumentUpload upload = new StagedDocumentUpload(
                Path.of("/staging/notes.upload"), "notes.txt", "text/plain", 11L);
        Path publishedPath = Path.of("/published/document.txt");
        var convertedDocument = new uk.gegc.quizmaker.features.document.application.ConvertedDocument();
        convertedDocument.setFullContent("Study notes");
        UniversalChunker.Chunk chunk = new UniversalChunker.Chunk();
        chunk.setChunkIndex(0);
        chunk.setContent("Study notes");
        chunk.setTitle("Document");
        chunk.setWordCount(2);
        chunk.setCharacterCount(11);
        chunk.setChunkType(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED);
        Document resultDocument = new Document();
        resultDocument.setId(UUID.randomUUID());
        var resultDto = new uk.gegc.quizmaker.features.document.api.dto.DocumentDto();
        resultDto.setId(resultDocument.getId());

        when(stagingService.stage(any(InputStream.class), eq("notes.txt"), eq(null), eq(11L))).thenReturn(upload);
        when(stagingService.promote(upload)).thenReturn(publishedPath);
        when(parseExecutor.execute(eq("owner"), any(DocumentParseRequest.class))).thenReturn(convertedDocument);
        when(chunkingService.chunkDocument(eq(convertedDocument), any(ProcessDocumentRequest.class))).thenReturn(List.of(chunk));
        when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
        when(documentRepository.save(any(Document.class))).thenReturn(resultDocument);
        when(documentMapper.toDto(resultDocument)).thenReturn(resultDto);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
        });

        DocumentProcessingServiceImpl service = new DocumentProcessingServiceImpl(
                documentRepository,
                chunkRepository,
                userRepository,
                documentMapper,
                chunkingService,
                stagingService,
                parseExecutor,
                DocumentProcessingLimits.defaults(),
                transactionTemplate,
                mock(DocumentDeletionService.class),
                metrics
        );
        ProcessDocumentRequest request = new ProcessDocumentRequest();
        request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED);
        request.setMaxChunkSize(1_000);
        request.setStoreChunks(false);

        assertThat(service.uploadAndProcessDocument("owner", "Study notes".getBytes(StandardCharsets.UTF_8), "notes.txt", request))
                .isSameAs(resultDto);

        verify(documentRepository).save(any(Document.class));
        verify(chunkRepository, never()).saveAll(any());
        ArgumentCaptor<DocumentParseRequest> parseRequest = ArgumentCaptor.forClass(DocumentParseRequest.class);
        verify(parseExecutor).execute(eq("owner"), parseRequest.capture());
        assertThat(parseRequest.getValue()).isEqualTo(new DocumentParseRequest(
                upload.stagingPath(), upload.originalFilename(), upload.detectedContentType(), upload.sizeBytes()));
        verify(metrics).recordExtracted(DocumentIngestionMetrics.Format.TEXT, 11, null);
        verify(metrics).recordEvent(
                DocumentIngestionMetrics.Stage.PROCESSING,
                DocumentIngestionMetrics.Outcome.SUCCEEDED,
                DocumentIngestionMetrics.Reason.NONE);
        verify(metrics).ingestionStarted();
        verify(metrics).ingestionStopped();
    }

    @Test
    @DisplayName("Does not promote or publish an upload when the parser rejects its worker response")
    void uploadDoesNotPublishAfterParserProtocolFailure() throws Exception {
        UploadScenario scenario = new UploadScenario();
        DocumentProcessingException parserFailure = new DocumentProcessingException(
                "Document parser returned an invalid response");
        when(scenario.parseExecutor.execute(eq("owner"), any(DocumentParseRequest.class)))
                .thenThrow(parserFailure);

        assertThatThrownBy(scenario::upload).isSameAs(parserFailure);

        verify(scenario.stagingService, never()).promote(any(StagedDocumentUpload.class));
        verify(scenario.transactionTemplate, never()).execute(any());
        verify(scenario.documentRepository, never()).save(any(Document.class));
        verify(scenario.stagingService).discard(scenario.upload.stagingPath());
        verify(scenario.metrics).recordEvent(
                DocumentIngestionMetrics.Stage.CONVERSION,
                DocumentIngestionMetrics.Outcome.FAILED,
                DocumentIngestionMetrics.Reason.PROCESSING);
        verify(scenario.metrics).recordEvent(
                DocumentIngestionMetrics.Stage.PROCESSING,
                DocumentIngestionMetrics.Outcome.FAILED,
                DocumentIngestionMetrics.Reason.PROCESSING);
    }

    @Test
    @DisplayName("Records capacity rejection without promoting or publishing the upload")
    void recordsCapacityRejectionWithoutPublishing() throws Exception {
        UploadScenario scenario = new UploadScenario();
        when(scenario.parseExecutor.execute(eq("owner"), any(DocumentParseRequest.class)))
                .thenThrow(new DocumentProcessingCapacityExceededException());

        assertThatThrownBy(scenario::upload)
                .isInstanceOf(DocumentProcessingCapacityExceededException.class);

        verify(scenario.stagingService, never()).promote(any(StagedDocumentUpload.class));
        verify(scenario.transactionTemplate, never()).execute(any());
        verify(scenario.metrics).recordEvent(
                DocumentIngestionMetrics.Stage.CONVERSION,
                DocumentIngestionMetrics.Outcome.REJECTED,
                DocumentIngestionMetrics.Reason.CAPACITY);
        verify(scenario.metrics).recordEvent(
                DocumentIngestionMetrics.Stage.PROCESSING,
                DocumentIngestionMetrics.Outcome.REJECTED,
                DocumentIngestionMetrics.Reason.CAPACITY);
    }

    @Test
    @DisplayName("Records a resource-limit rejection without promoting or publishing the upload")
    void recordsResourceLimitRejectionWithoutPublishing() throws Exception {
        UploadScenario scenario = new UploadScenario();
        when(scenario.parseExecutor.execute(eq("owner"), any(DocumentParseRequest.class)))
                .thenThrow(new DocumentResourceLimitException("Extracted content exceeded the configured limit"));

        assertThatThrownBy(scenario::upload)
                .isInstanceOf(DocumentResourceLimitException.class);

        verify(scenario.stagingService, never()).promote(any(StagedDocumentUpload.class));
        verify(scenario.transactionTemplate, never()).execute(any());
        verify(scenario.metrics).recordEvent(
                DocumentIngestionMetrics.Stage.CONVERSION,
                DocumentIngestionMetrics.Outcome.REJECTED,
                DocumentIngestionMetrics.Reason.RESOURCE_LIMIT);
        verify(scenario.metrics).recordEvent(
                DocumentIngestionMetrics.Stage.PROCESSING,
                DocumentIngestionMetrics.Outcome.REJECTED,
                DocumentIngestionMetrics.Reason.RESOURCE_LIMIT);
    }

    @Test
    @DisplayName("Deletes the promoted file when document publication rolls back")
    void uploadDiscardsPromotedFileWhenPublicationFails() throws Exception {
        UploadScenario scenario = new UploadScenario();
        RuntimeException persistenceFailure = new DataIntegrityViolationException("Database publication failed");
        when(scenario.documentRepository.save(any(Document.class))).thenThrow(persistenceFailure);

        assertThatThrownBy(scenario::upload)
                .isSameAs(persistenceFailure);

        verify(scenario.stagingService).discard(scenario.publishedPath);
        verify(scenario.stagingService).discard(scenario.upload.stagingPath());
        verify(scenario.documentMapper, never()).toDto(any(Document.class));
        verify(scenario.metrics).recordEvent(
                DocumentIngestionMetrics.Stage.PUBLICATION,
                DocumentIngestionMetrics.Outcome.FAILED,
                DocumentIngestionMetrics.Reason.PERSISTENCE);
        verify(scenario.metrics).recordEvent(
                DocumentIngestionMetrics.Stage.COMPENSATION,
                DocumentIngestionMetrics.Outcome.SUCCEEDED,
                DocumentIngestionMetrics.Reason.NONE);
    }

    @Test
    @DisplayName("Reports failed compensation when a rolled-back publication file cannot be removed")
    void uploadReportsDeferredCompensationAfterPublicationFailure() throws Exception {
        UploadScenario scenario = new UploadScenario();
        RuntimeException persistenceFailure = new DataIntegrityViolationException("Database publication failed");
        when(scenario.documentRepository.save(any(Document.class))).thenThrow(persistenceFailure);
        when(scenario.stagingService.discard(scenario.publishedPath)).thenReturn(false);

        assertThatThrownBy(scenario::upload)
                .isSameAs(persistenceFailure);

        verify(scenario.stagingService).discard(scenario.publishedPath);
        verify(scenario.metrics).recordEvent(
                DocumentIngestionMetrics.Stage.COMPENSATION,
                DocumentIngestionMetrics.Outcome.FAILED,
                DocumentIngestionMetrics.Reason.CLEANUP);
    }

    @Test
    @DisplayName("Keeps the promoted file when response mapping fails after publication commits")
    void uploadKeepsPromotedFileWhenPostCommitMappingFails() throws Exception {
        UploadScenario scenario = new UploadScenario();
        RuntimeException mappingFailure = new RuntimeException("Response mapping failed");
        AtomicBoolean publicationCompleted = new AtomicBoolean();
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            Object result = callback.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
            publicationCompleted.set(true);
            return result;
        }).when(scenario.transactionTemplate).execute(any());
        when(scenario.documentMapper.toDto(scenario.persistedDocument)).thenAnswer(invocation -> {
            assertThat(publicationCompleted).isTrue();
            throw mappingFailure;
        });

        assertThatThrownBy(scenario::upload)
                .isSameAs(mappingFailure);

        verify(scenario.documentRepository).save(any(Document.class));
        verify(scenario.stagingService, never()).discard(scenario.publishedPath);
        verify(scenario.stagingService).discard(scenario.upload.stagingPath());
        verify(scenario.metrics, never()).recordEvent(
                DocumentIngestionMetrics.Stage.PROCESSING,
                DocumentIngestionMetrics.Outcome.SUCCEEDED,
                DocumentIngestionMetrics.Reason.NONE);
        verify(scenario.metrics).recordEvent(
                DocumentIngestionMetrics.Stage.PROCESSING,
                DocumentIngestionMetrics.Outcome.FAILED,
                DocumentIngestionMetrics.Reason.UNKNOWN);
    }

    @Test
    @DisplayName("Keeps the legacy processing-service delete contract while delegating the transaction boundary")
    void delegatesDocumentDeletionWithoutChangingThePublicServiceContract() throws Exception {
        UploadScenario scenario = new UploadScenario();
        UUID documentId = UUID.randomUUID();

        scenario.service.deleteDocument("owner", documentId);

        verify(scenario.deletionService).deleteDocument("owner", documentId);
    }

    private static final class UploadScenario {

        private final byte[] content = "Study notes".getBytes(StandardCharsets.UTF_8);
        private final StagedDocumentUpload upload = new StagedDocumentUpload(
                Path.of("/staging/notes.upload"), "notes.txt", "text/plain", content.length);
        private final Path publishedPath = Path.of("/published/document.txt");
        private final DocumentRepository documentRepository = mock(DocumentRepository.class);
        private final DocumentChunkRepository chunkRepository = mock(DocumentChunkRepository.class);
        private final UserRepository userRepository = mock(UserRepository.class);
        private final DocumentMapper documentMapper = mock(DocumentMapper.class);
        private final DocumentChunkingService chunkingService = mock(DocumentChunkingService.class);
        private final DocumentUploadStagingService stagingService = mock(DocumentUploadStagingService.class);
        private final DocumentParseExecutor parseExecutor = mock(DocumentParseExecutor.class);
        private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        private final DocumentDeletionService deletionService = mock(DocumentDeletionService.class);
        private final DocumentIngestionMetrics metrics = mock(DocumentIngestionMetrics.class);
        private final Document persistedDocument = new Document();
        private final ProcessDocumentRequest request = new ProcessDocumentRequest();
        private final DocumentProcessingServiceImpl service;

        private UploadScenario() throws Exception {
            User owner = new User();
            owner.setId(UUID.randomUUID());
            owner.setUsername("owner");
            persistedDocument.setId(UUID.randomUUID());

            var convertedDocument = new uk.gegc.quizmaker.features.document.application.ConvertedDocument();
            convertedDocument.setFullContent("Study notes");

            when(stagingService.stage(any(InputStream.class), eq("notes.txt"), eq(null), eq((long) content.length)))
                    .thenReturn(upload);
            when(stagingService.promote(upload)).thenReturn(publishedPath);
            when(stagingService.discard(any(Path.class))).thenReturn(true);
            when(parseExecutor.execute(eq("owner"), any(DocumentParseRequest.class))).thenReturn(convertedDocument);
            when(chunkingService.chunkDocument(eq(convertedDocument), any(ProcessDocumentRequest.class)))
                    .thenReturn(List.of());
            when(userRepository.findByUsername("owner")).thenReturn(Optional.of(owner));
            when(documentRepository.save(any(Document.class))).thenReturn(persistedDocument);
            when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                TransactionCallback<?> callback = invocation.getArgument(0);
                return callback.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
            });

            request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED);
            request.setMaxChunkSize(1_000);
            request.setStoreChunks(false);
            service = new DocumentProcessingServiceImpl(
                    documentRepository,
                    chunkRepository,
                    userRepository,
                    documentMapper,
                    chunkingService,
                    stagingService,
                    parseExecutor,
                    DocumentProcessingLimits.defaults(),
                    transactionTemplate,
                    deletionService,
                    metrics
            );
        }

        private void upload() {
            service.uploadAndProcessDocument("owner", content, "notes.txt", request);
        }
    }
}
