package uk.gegc.quizmaker.features.document.application.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.document.api.dto.ProcessDocumentRequest;
import uk.gegc.quizmaker.features.document.application.DocumentChunkingService;
import uk.gegc.quizmaker.features.document.application.DocumentConversionService;
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
import uk.gegc.quizmaker.shared.exception.DocumentResourceLimitException;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
                new DocumentResourceLimitException("Document processing exceeded the configured time limit")
        );

        DocumentProcessingServiceImpl service = new DocumentProcessingServiceImpl(
                documentRepository,
                chunkRepository,
                userRepository,
                mock(DocumentMapper.class),
                mock(DocumentConversionService.class),
                mock(DocumentChunkingService.class),
                mock(DocumentUploadStagingService.class),
                parseExecutor,
                DocumentProcessingLimits.defaults(),
                transactionTemplate
        );

        ProcessDocumentRequest request = new ProcessDocumentRequest();
        request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED);
        request.setMaxChunkSize(1_000);

        assertThatThrownBy(() -> service.reprocessDocument("owner", document.getId(), request))
                .isInstanceOf(DocumentResourceLimitException.class);

        verify(chunkRepository, never()).deleteByDocument(any(Document.class));
        verify(transactionTemplate, never()).execute(any());
    }

    @Test
    @DisplayName("Keeps the legacy storeChunks false behavior while publishing document metadata atomically")
    void uploadDoesNotPersistChunksWhenRequestDisablesChunkStorage() throws Exception {
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        DocumentChunkRepository chunkRepository = mock(DocumentChunkRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        DocumentConversionService conversionService = mock(DocumentConversionService.class);
        DocumentChunkingService chunkingService = mock(DocumentChunkingService.class);
        DocumentUploadStagingService stagingService = mock(DocumentUploadStagingService.class);
        DocumentParseExecutor parseExecutor = mock(DocumentParseExecutor.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);

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
        when(parseExecutor.execute(eq("owner"), any())).thenAnswer(invocation ->
                ((Callable<?>) invocation.getArgument(1)).call());
        when(conversionService.convertDocument(upload.stagingPath(), upload.originalFilename(),
                upload.detectedContentType(), upload.sizeBytes())).thenReturn(convertedDocument);
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
                conversionService,
                chunkingService,
                stagingService,
                parseExecutor,
                DocumentProcessingLimits.defaults(),
                transactionTemplate
        );
        ProcessDocumentRequest request = new ProcessDocumentRequest();
        request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED);
        request.setMaxChunkSize(1_000);
        request.setStoreChunks(false);

        assertThat(service.uploadAndProcessDocument("owner", "Study notes".getBytes(StandardCharsets.UTF_8), "notes.txt", request))
                .isSameAs(resultDto);

        verify(documentRepository).save(any(Document.class));
        verify(chunkRepository, never()).saveAll(any());
    }
}
