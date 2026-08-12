package uk.gegc.quizmaker.features.document.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.LoggerFactory;
import uk.gegc.quizmaker.features.document.infra.converter.EpubDocumentConverter;
import uk.gegc.quizmaker.features.document.infra.converter.PdfDocumentConverter;
import uk.gegc.quizmaker.features.document.infra.converter.TextDocumentConverter;
import uk.gegc.quizmaker.shared.exception.DocumentProcessingException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Document logging privacy contract")
@Execution(ExecutionMode.SAME_THREAD)
class DocumentLoggingPrivacyTest {

    private static final Path DOCUMENT_SOURCE_ROOT = Path.of(
            "src/main/java/uk/gegc/quizmaker/features/document");
    private static final Pattern LOG_STATEMENT = Pattern.compile(
            "\\blog\\.(?:trace|debug|info|warn|error)\\s*\\((.*?)\\);",
            Pattern.DOTALL);
    private static final List<String> FORBIDDEN_LOG_EXPRESSIONS = List.of(
            "getOriginalFilename(",
            "getTitle(",
            "getAuthor(",
            "getContent(",
            "chapterTitle",
            "sectionTitle",
            "documentId",
            "authentication.getName(",
            "username",
            "filePath",
            "stagingPath",
            ".getMessage()"
    );

    @TempDir
    Path temporaryDirectory;

    @Test
    @DisplayName("Omits source filename, headings, content, and failure details from runtime logs")
    void omitsPrivateCanariesFromRuntimeLogs() throws Exception {
        String filenameCanary = "PRIVATE_FILENAME_722.txt";
        String headingCanary = "PRIVATE_CHAPTER_HEADING_722";
        String sectionCanary = "PRIVATE_SECTION_HEADING_722";
        String failureCanary = "PRIVATE_FAILURE_DETAIL_722";
        CapturedPackageLogs logs = new CapturedPackageLogs();
        try {
            TextDocumentConverter textConverter = new TextDocumentConverter(DocumentProcessingLimits.defaults());
            String text = "1. " + headingCanary + "\n1.1 " + sectionCanary + "\nStudy content\n";
            textConverter.convert(
                    new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                    filenameCanary,
                    (long) text.length());

            DocumentConverter failingConverter = new FailingConverter(failureCanary);
            DocumentConversionService conversionService = new DocumentConversionService(
                    new DocumentConverterFactory(List.of(failingConverter)));
            assertThatThrownBy(() -> conversionService.convertDocument(
                    "content".getBytes(StandardCharsets.UTF_8),
                    filenameCanary,
                    "application/x-" + headingCanary))
                    .isInstanceOf(DocumentProcessingException.class);

            assertThat(logs.messages())
                    .noneMatch(message -> message.contains(filenameCanary)
                            || message.contains(headingCanary)
                            || message.contains(sectionCanary)
                            || message.contains(failureCanary));
        } finally {
            logs.close();
        }
    }

    @Test
    @DisplayName("Omits PDF and EPUB filenames, metadata, headings, and content from runtime logs")
    void omitsPdfAndEpubCanariesFromRuntimeLogs() throws Exception {
        String pdfFilename = "PRIVATE_PDF_FILENAME_722.pdf";
        String pdfChapter = "PRIVATE_PDF_CHAPTER_722";
        String pdfSection = "PRIVATE_PDF_SECTION_722";
        String epubFilename = "PRIVATE_EPUB_FILENAME_722.epub";
        String epubTitle = "PRIVATE_EPUB_TITLE_722";
        String epubChapter = "PRIVATE_EPUB_CHAPTER_722";
        String epubSection = "PRIVATE_EPUB_SECTION_722";
        CapturedPackageLogs logs = new CapturedPackageLogs();
        try {
            DocumentProcessingLimits limits = DocumentProcessingLimits.defaults();
            limits.setStorageRoot(temporaryDirectory.resolve("documents").toString());

            byte[] pdf = createPdf(pdfChapter, pdfSection);
            new PdfDocumentConverter(limits).convert(
                    new ByteArrayInputStream(pdf), pdfFilename, (long) pdf.length);

            byte[] epub = createEpub(epubTitle, epubChapter, epubSection);
            new EpubDocumentConverter(limits).convert(
                    new ByteArrayInputStream(epub), epubFilename, (long) epub.length);

            assertThat(logs.messages()).noneMatch(message -> List.of(
                            pdfFilename,
                            pdfChapter,
                            pdfSection,
                            epubFilename,
                            epubTitle,
                            epubChapter,
                            epubSection)
                    .stream()
                    .anyMatch(message::contains));
        } finally {
            logs.close();
        }
    }

    @Test
    @DisplayName("Prevents source-derived expressions and raw throwables in document log calls")
    void scansDocumentSourceForPrivateLoggingExpressions() throws Exception {
        List<String> violations = new ArrayList<>();
        try (var files = Files.walk(DOCUMENT_SOURCE_ROOT)) {
            for (Path source : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String javaSource = Files.readString(source);
                Matcher matcher = LOG_STATEMENT.matcher(javaSource);
                while (matcher.find()) {
                    String statement = matcher.group();
                    for (String forbidden : FORBIDDEN_LOG_EXPRESSIONS) {
                        if (statement.contains(forbidden)) {
                            violations.add(source + " logs private expression " + forbidden);
                        }
                    }
                    if (statement.matches("(?s).*,\\s*(?:e|exception|failure|cleanupFailure)\\s*\\)\\s*;")) {
                        violations.add(source + " passes a raw throwable to a logger");
                    }
                }
            }
        }

        assertThat(violations).isEmpty();
    }

    private byte[] createPdf(String chapterCanary, String sectionCanary) throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA, 12);
                content.setLeading(16);
                content.newLineAtOffset(72, 720);
                content.showText("1. " + chapterCanary);
                content.newLine();
                content.showText("1.1 " + sectionCanary);
                content.newLine();
                content.showText("PRIVATE_PDF_CONTENT_722");
                content.endText();
            }
            document.save(bytes);
            return bytes.toByteArray();
        }
    }

    private byte[] createEpub(String titleCanary, String chapterCanary, String sectionCanary) throws IOException {
        byte[] mimetype = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(mimetype);
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ZipOutputStream output = new ZipOutputStream(bytes)) {
            ZipEntry mimetypeEntry = new ZipEntry("mimetype");
            mimetypeEntry.setMethod(ZipEntry.STORED);
            mimetypeEntry.setSize(mimetype.length);
            mimetypeEntry.setCompressedSize(mimetype.length);
            mimetypeEntry.setCrc(crc.getValue());
            output.putNextEntry(mimetypeEntry);
            output.write(mimetype);
            output.closeEntry();

            writeEntry(output, "META-INF/container.xml", """
                    <?xml version="1.0"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>
                    """);
            writeEntry(output, "OEBPS/content.opf", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:identifier id="book-id">privacy-fixture</dc:identifier>
                        <dc:title>%s</dc:title>
                        <dc:language>en</dc:language>
                      </metadata>
                      <manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest>
                      <spine><itemref idref="chapter"/></spine>
                    </package>
                    """.formatted(titleCanary));
            writeEntry(output, "OEBPS/chapter.xhtml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <head><title>%s</title></head>
                      <body>
                        <h1>1. %s</h1>
                        <h2>1.1 %s</h2>
                        <p>PRIVATE_EPUB_CONTENT_722</p>
                      </body>
                    </html>
                    """.formatted(titleCanary, chapterCanary, sectionCanary));
            output.finish();
            return bytes.toByteArray();
        }
    }

    private void writeEntry(ZipOutputStream output, String name, String value) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }

    private static final class FailingConverter implements DocumentConverter {

        private final String failureCanary;

        private FailingConverter(String failureCanary) {
            this.failureCanary = failureCanary;
        }

        @Override
        public boolean canConvert(String contentType, String filename) {
            return true;
        }

        @Override
        public ConvertedDocument convert(InputStream inputStream, String filename, Long fileSize) {
            throw new IllegalStateException(failureCanary);
        }

        @Override
        public List<String> getSupportedContentTypes() {
            return List.of("application/x-private");
        }

        @Override
        public List<String> getSupportedExtensions() {
            return List.of(".private");
        }

        @Override
        public String getConverterType() {
            return "TEST_CONVERTER";
        }
    }

    private static final class CapturedPackageLogs implements AutoCloseable {

        private final Logger logger = (Logger) LoggerFactory.getLogger(
                "uk.gegc.quizmaker.features.document");
        private final Level previousLevel = logger.getLevel();
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        private CapturedPackageLogs() {
            logger.setLevel(Level.INFO);
            appender.start();
            logger.addAppender(appender);
        }

        private List<String> messages() {
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }
}
