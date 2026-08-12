package uk.gegc.quizmaker.service.document.converter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import uk.gegc.quizmaker.features.document.application.ConvertedDocument;
import uk.gegc.quizmaker.features.document.application.DocumentConverter;
import uk.gegc.quizmaker.features.document.application.DocumentConverterFactory;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingLimits;
import uk.gegc.quizmaker.features.document.infra.converter.EpubDocumentConverter;
import uk.gegc.quizmaker.features.document.infra.converter.PdfDocumentConverter;
import uk.gegc.quizmaker.features.document.infra.converter.TextDocumentConverter;
import uk.gegc.quizmaker.shared.exception.DocumentProcessingException;

import java.io.InputStream;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Document converter factory")
@Execution(ExecutionMode.CONCURRENT)
class DocumentConverterFactoryTest {

    private DocumentConverterFactory converterFactory;

    @BeforeEach
    void setUp() {
        DocumentProcessingLimits limits = DocumentProcessingLimits.defaults();
        converterFactory = new DocumentConverterFactory(List.of(
                new PdfDocumentConverter(limits),
                new TextDocumentConverter(limits),
                new EpubDocumentConverter(limits)
        ));
    }

    @Test
    @DisplayName("Exposes every configured converter without requiring a Spring context")
    void getAllConvertersReturnsAllConverters() {
        assertThat(converterFactory.getAllConverters())
                .extracting(DocumentConverter::getConverterType)
                .containsExactly(
                        "PDF_DOCUMENT_CONVERTER",
                        "TEXT_DOCUMENT_CONVERTER",
                        "EPUB_DOCUMENT_CONVERTER"
                );
    }

    @ParameterizedTest(name = "{0} with {1} selects {2}")
    @MethodSource("canonicalAndLegacyTypes")
    @DisplayName("Selects converters by canonical and legacy content type")
    void findConverterSelectsByCanonicalAndLegacyContentType(
            String contentType,
            String filename,
            String expectedConverterType
    ) {
        DocumentConverter converter = converterFactory.findConverter(contentType, filename);

        assertThat(converter.getConverterType()).isEqualTo(expectedConverterType);
    }

    @ParameterizedTest(name = "{0} selects {1}")
    @MethodSource("extensionFallbacks")
    @DisplayName("Falls back to supported extensions for generic content")
    void findConverterFallsBackToSupportedExtension(String filename, String expectedConverterType) {
        DocumentConverter converter = converterFactory.findConverter("application/octet-stream", filename);

        assertThat(converter.getConverterType()).isEqualTo(expectedConverterType);
    }

    @Test
    @DisplayName("Returns the first configured converter when strategies overlap")
    void findConverterUsesConfiguredOrderForOverlappingStrategies() {
        DocumentConverter first = new StubConverter("FIRST", true);
        DocumentConverter second = new StubConverter("SECOND", true);
        DocumentConverterFactory orderedFactory = new DocumentConverterFactory(List.of(first, second));

        DocumentConverter selected = orderedFactory.findConverter("application/example", "example.bin");

        assertThat(selected).isSameAs(first);
    }

    @Test
    @DisplayName("Rejects unsupported content after checking every configured strategy")
    void findConverterRejectsUnsupportedContent() {
        assertThatThrownBy(() -> converterFactory.findConverter("application/x-unsupported", "notes.bin"))
                .isInstanceOf(DocumentProcessingException.class)
                .hasMessageContaining("No converter found");
    }

    @Test
    @DisplayName("Aggregates supported content types once in converter order")
    void getSupportedContentTypesReturnsDistinctTypes() {
        assertThat(converterFactory.getSupportedContentTypes()).containsExactly(
                "application/pdf",
                "text/plain",
                "text/txt",
                "application/epub+zip",
                "application/epub",
                "application/x-epub"
        );
    }

    @Test
    @DisplayName("Aggregates supported extensions once in converter order")
    void getSupportedExtensionsReturnsDistinctExtensions() {
        assertThat(converterFactory.getSupportedExtensions()).containsExactly(
                ".pdf",
                ".txt",
                ".text",
                ".epub"
        );
    }

    @Test
    @DisplayName("De-duplicates overlapping converter metadata while preserving first-seen order")
    void supportedFormatsAreDeduplicatedAcrossConverters() {
        DocumentConverter first = new StubConverter(
                "FIRST",
                false,
                List.of("application/first", "application/shared"),
                List.of(".first", ".shared")
        );
        DocumentConverter second = new StubConverter(
                "SECOND",
                false,
                List.of("application/shared", "application/second"),
                List.of(".shared", ".second")
        );
        DocumentConverterFactory duplicateAwareFactory = new DocumentConverterFactory(List.of(first, second));

        assertThat(duplicateAwareFactory.getSupportedContentTypes()).containsExactly(
                "application/first",
                "application/shared",
                "application/second"
        );
        assertThat(duplicateAwareFactory.getSupportedExtensions()).containsExactly(
                ".first",
                ".shared",
                ".second"
        );
    }

    private static Stream<Arguments> canonicalAndLegacyTypes() {
        return Stream.of(
                Arguments.of("application/pdf", "document.pdf", "PDF_DOCUMENT_CONVERTER"),
                Arguments.of("text/plain", "document.txt", "TEXT_DOCUMENT_CONVERTER"),
                Arguments.of("text/txt", "document.txt", "TEXT_DOCUMENT_CONVERTER"),
                Arguments.of("application/epub+zip", "document.epub", "EPUB_DOCUMENT_CONVERTER"),
                Arguments.of("application/epub", "document.epub", "EPUB_DOCUMENT_CONVERTER"),
                Arguments.of("application/x-epub", "document.epub", "EPUB_DOCUMENT_CONVERTER")
        );
    }

    private static Stream<Arguments> extensionFallbacks() {
        return Stream.of(
                Arguments.of("document.pdf", "PDF_DOCUMENT_CONVERTER"),
                Arguments.of("document.txt", "TEXT_DOCUMENT_CONVERTER"),
                Arguments.of("document.text", "TEXT_DOCUMENT_CONVERTER"),
                Arguments.of("document.epub", "EPUB_DOCUMENT_CONVERTER"),
                Arguments.of("DOCUMENT.EPUB", "EPUB_DOCUMENT_CONVERTER")
        );
    }

    private record StubConverter(
            String converterType,
            boolean matches,
            List<String> supportedContentTypes,
            List<String> supportedExtensions
    ) implements DocumentConverter {

        private StubConverter(String converterType, boolean matches) {
            this(converterType, matches, List.of(), List.of());
        }

        @Override
        public boolean canConvert(String contentType, String filename) {
            return matches;
        }

        @Override
        public ConvertedDocument convert(InputStream inputStream, String filename, Long fileSize) {
            throw new UnsupportedOperationException("Selection test converter cannot convert content");
        }

        @Override
        public List<String> getSupportedContentTypes() {
            return supportedContentTypes;
        }

        @Override
        public List<String> getSupportedExtensions() {
            return supportedExtensions;
        }

        @Override
        public String getConverterType() {
            return converterType;
        }
    }
}
