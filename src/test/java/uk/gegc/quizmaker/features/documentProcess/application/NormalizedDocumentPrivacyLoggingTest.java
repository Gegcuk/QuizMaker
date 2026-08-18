package uk.gegc.quizmaker.features.documentProcess.application;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import uk.gegc.quizmaker.features.conversion.application.DocumentConversionService;
import uk.gegc.quizmaker.features.conversion.application.MimeTypeDetector;
import uk.gegc.quizmaker.features.conversion.domain.UnsupportedFormatException;
import uk.gegc.quizmaker.features.documentProcess.config.DocumentChunkingConfig;
import uk.gegc.quizmaker.features.documentProcess.domain.ValidationErrorException;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;
import uk.gegc.quizmaker.features.documentProcess.infra.mapper.DocumentNodeMapper;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.DocumentNodeRepository;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.NormalizedDocumentRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Normalized document privacy logging")
class NormalizedDocumentPrivacyLoggingTest {

    private static final String CANARY = "CANARY_PRIVATE_DOCUMENT_VALUE";

    @Mock
    private DocumentConversionService conversionService;
    @Mock
    private NormalizationService normalizationService;
    @Mock
    private NormalizedDocumentRepository documentRepository;
    @Mock
    private MimeTypeDetector mimeTypeDetector;
    @Mock
    private DocumentNodeRepository nodeRepository;
    @Mock
    private DocumentNodeMapper nodeMapper;
    @Mock
    private LlmClient llmClient;
    @Mock
    private NodeHierarchyBuilder hierarchyBuilder;
    @Mock
    private DocumentQueryService queryService;
    @Mock
    private ChunkedStructureService chunkedStructureService;

    private Logger attachedLogger;
    private ListAppender<ILoggingEvent> appender;

    @AfterEach
    void detachAppender() {
        if (attachedLogger != null && appender != null) {
            attachedLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("conversion failure logs and client-safe error omit the original filename canary")
    void conversionFailureOmitsFilenameAndProviderMessage() throws Exception {
        capture(DocumentIngestionService.class);
        DocumentIngestionService service = new DocumentIngestionService(
                conversionService, normalizationService, documentRepository, mimeTypeDetector
        );
        User owner = new User();
        owner.setUsername("owner");
        when(conversionService.convert(eq(CANARY + ".secret"), any(byte[].class)))
                .thenThrow(new UnsupportedFormatException("No converter for " + CANARY));
        when(mimeTypeDetector.detectMimeType(CANARY + ".secret")).thenReturn("application/octet-stream");
        when(documentRepository.save(any(NormalizedDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> service.ingestFromFile(owner, CANARY + ".secret", new byte[]{1}))
                .isInstanceOf(UnsupportedFormatException.class)
                .hasMessage("Unsupported document format")
                .hasMessageNotContaining(CANARY);

        assertCapturedLogsExcludeCanary();
    }

    @Test
    @DisplayName("successful ingestion logs omit the original filename and source-text canary")
    void successfulIngestionOmitsSourceValues() {
        capture(DocumentIngestionService.class);
        DocumentIngestionService service = new DocumentIngestionService(
                conversionService, normalizationService, documentRepository, mimeTypeDetector
        );
        User owner = new User();
        owner.setUsername("owner");
        when(normalizationService.normalize(CANARY + " source text"))
                .thenReturn(new NormalizationResult(CANARY + " source text", 41));
        when(documentRepository.save(any(NormalizedDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        NormalizedDocument result = service.ingestFromText(
                owner,
                CANARY + ".txt",
                "en",
                CANARY + " source text"
        );

        assertThat(result.getOriginalName()).contains(CANARY);
        assertThat(result.getNormalizedText()).contains(CANARY);
        assertCapturedLogsExcludeCanary();
    }

    @Test
    @DisplayName("text-query failure omits private source content from logs and public error detail")
    void queryFailureOmitsSourceText() {
        capture(DocumentQueryService.class);
        DocumentQueryService service = new DocumentQueryService(documentRepository);
        UUID documentId = UUID.randomUUID();
        NormalizedDocument document = new NormalizedDocument();
        document.setId(documentId);
        document.setNormalizedText(CANARY);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));

        assertThatThrownBy(() -> service.getTextSlice(documentId, CANARY.length() + 1, CANARY.length() + 2))
                .isInstanceOf(ValidationErrorException.class)
                .hasMessageNotContaining(CANARY);

        assertCapturedLogsExcludeCanary();
    }

    @Test
    @DisplayName("fake AI response failure is sanitized before logging and before the application error")
    void fakeAiFailureOmitsRawResponse() {
        capture(StructureService.class);
        AnchorOffsetCalculator anchorOffsetCalculator = new AnchorOffsetCalculator();
        StructureService service = new StructureService(
                nodeRepository,
                documentRepository,
                nodeMapper,
                llmClient,
                anchorOffsetCalculator,
                hierarchyBuilder,
                queryService,
                chunkedStructureService
        );
        UUID documentId = UUID.randomUUID();
        NormalizedDocument document = new NormalizedDocument();
        document.setId(documentId);
        document.setNormalizedText("safe text");
        document.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(chunkedStructureService.getChunkingConfig()).thenReturn(new DocumentChunkingConfig());
        when(llmClient.generateStructure("safe text", LlmClient.StructureOptions.defaultOptions()))
                .thenThrow(new LlmClient.LlmException("Raw provider response: " + CANARY));

        assertThatThrownBy(() -> service.buildStructure(documentId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to generate document structure")
                .hasMessageNotContaining(CANARY);

        assertCapturedLogsExcludeCanary();
    }

    @Test
    @DisplayName("anchor fallback logs omit section titles, anchors, and document previews")
    void anchorFailureOmitsSourceDerivedText() {
        capture(AnchorOffsetCalculator.class);
        AnchorOffsetCalculator calculator = new AnchorOffsetCalculator();
        DocumentNode node = new DocumentNode();
        node.setTitle(CANARY + " title");
        node.setStartAnchor(CANARY + " start anchor long enough");
        node.setEndAnchor(CANARY + " end anchor long enough");

        assertThatThrownBy(() -> calculator.calculateOffsets(List.of(node), CANARY + " document preview"))
                .isInstanceOfAny(
                        AnchorOffsetCalculator.AnchorNotFoundException.class,
                        IllegalArgumentException.class
                )
                .hasMessageNotContaining(CANARY);

        assertCapturedLogsExcludeCanary();
    }

    private void capture(Class<?> type) {
        attachedLogger = (Logger) LoggerFactory.getLogger(type);
        appender = new ListAppender<>();
        appender.start();
        attachedLogger.addAppender(appender);
    }

    private void assertCapturedLogsExcludeCanary() {
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .allSatisfy(message -> assertThat(message).doesNotContain(CANARY));
    }
}
