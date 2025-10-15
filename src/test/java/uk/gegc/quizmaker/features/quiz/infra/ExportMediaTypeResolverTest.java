package uk.gegc.quizmaker.features.quiz.infra;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.gegc.quizmaker.features.quiz.domain.model.ExportFormat;

import static org.assertj.core.api.Assertions.assertThat;

@Execution(ExecutionMode.CONCURRENT)
@DisplayName("ExportMediaTypeResolver Tests")
class ExportMediaTypeResolverTest {

    private ExportMediaTypeResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ExportMediaTypeResolver();
    }

    // Content Type Tests

    @Test
    @DisplayName("contentTypeFor: JSON_EDITABLE returns application/json")
    void contentTypeFor_jsonEditable_returnsApplicationJson() {
        // When
        String contentType = resolver.contentTypeFor(ExportFormat.JSON_EDITABLE);

        // Then
        assertThat(contentType).isEqualTo("application/json");
    }

    @Test
    @DisplayName("contentTypeFor: XLSX_EDITABLE returns Excel MIME type")
    void contentTypeFor_xlsxEditable_returnsExcelMimeType() {
        // When
        String contentType = resolver.contentTypeFor(ExportFormat.XLSX_EDITABLE);

        // Then
        assertThat(contentType).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    @Test
    @DisplayName("contentTypeFor: HTML_PRINT returns text/html with charset")
    void contentTypeFor_htmlPrint_returnsTextHtmlWithCharset() {
        // When
        String contentType = resolver.contentTypeFor(ExportFormat.HTML_PRINT);

        // Then
        assertThat(contentType).isEqualTo("text/html; charset=utf-8");
        assertThat(contentType).contains("charset=utf-8");
    }

    @Test
    @DisplayName("contentTypeFor: PDF_PRINT returns application/pdf")
    void contentTypeFor_pdfPrint_returnsApplicationPdf() {
        // When
        String contentType = resolver.contentTypeFor(ExportFormat.PDF_PRINT);

        // Then
        assertThat(contentType).isEqualTo("application/pdf");
    }

    @ParameterizedTest
    @EnumSource(ExportFormat.class)
    @DisplayName("contentTypeFor: all formats return non-null, non-blank content type")
    void contentTypeFor_allFormats_returnsNonBlankContentType(ExportFormat format) {
        // When
        String contentType = resolver.contentTypeFor(format);

        // Then
        assertThat(contentType).isNotNull();
        assertThat(contentType).isNotBlank();
    }

    // File Extension Tests

    @Test
    @DisplayName("fileExtensionFor: JSON_EDITABLE returns json")
    void fileExtensionFor_jsonEditable_returnsJson() {
        // When
        String extension = resolver.fileExtensionFor(ExportFormat.JSON_EDITABLE);

        // Then
        assertThat(extension).isEqualTo("json");
    }

    @Test
    @DisplayName("fileExtensionFor: XLSX_EDITABLE returns xlsx")
    void fileExtensionFor_xlsxEditable_returnsXlsx() {
        // When
        String extension = resolver.fileExtensionFor(ExportFormat.XLSX_EDITABLE);

        // Then
        assertThat(extension).isEqualTo("xlsx");
    }

    @Test
    @DisplayName("fileExtensionFor: HTML_PRINT returns html")
    void fileExtensionFor_htmlPrint_returnsHtml() {
        // When
        String extension = resolver.fileExtensionFor(ExportFormat.HTML_PRINT);

        // Then
        assertThat(extension).isEqualTo("html");
    }

    @Test
    @DisplayName("fileExtensionFor: PDF_PRINT returns pdf")
    void fileExtensionFor_pdfPrint_returnsPdf() {
        // When
        String extension = resolver.fileExtensionFor(ExportFormat.PDF_PRINT);

        // Then
        assertThat(extension).isEqualTo("pdf");
    }

    @ParameterizedTest
    @EnumSource(ExportFormat.class)
    @DisplayName("fileExtensionFor: all formats return non-null, non-blank extension")
    void fileExtensionFor_allFormats_returnsNonBlankExtension(ExportFormat format) {
        // When
        String extension = resolver.fileExtensionFor(format);

        // Then
        assertThat(extension).isNotNull();
        assertThat(extension).isNotBlank();
        assertThat(extension).doesNotStartWith(".");
        assertThat(extension).isLowerCase();
    }

    // Consistency Tests

    @ParameterizedTest
    @EnumSource(ExportFormat.class)
    @DisplayName("contentTypeFor and fileExtensionFor: are consistent for all formats")
    void contentTypeAndExtension_allFormats_areConsistent(ExportFormat format) {
        // When
        String contentType = resolver.contentTypeFor(format);
        String extension = resolver.fileExtensionFor(format);

        // Then - verify consistency between content type and extension
        switch (format) {
            case JSON_EDITABLE -> {
                assertThat(contentType).contains("json");
                assertThat(extension).isEqualTo("json");
            }
            case XLSX_EDITABLE -> {
                assertThat(contentType).contains("spreadsheet");
                assertThat(extension).isEqualTo("xlsx");
            }
            case HTML_PRINT -> {
                assertThat(contentType).contains("html");
                assertThat(extension).isEqualTo("html");
            }
            case PDF_PRINT -> {
                assertThat(contentType).contains("pdf");
                assertThat(extension).isEqualTo("pdf");
            }
        }
    }

    @Test
    @DisplayName("contentTypeFor: returns valid MIME types")
    void contentTypeFor_returnsValidMimeTypes() {
        // All content types should follow MIME type format (type/subtype)
        for (ExportFormat format : ExportFormat.values()) {
            String contentType = resolver.contentTypeFor(format);
            
            // Extract main part before parameters (e.g., "text/html" from "text/html; charset=utf-8")
            String mainType = contentType.split(";")[0].trim();
            
            assertThat(mainType).matches("^[a-z]+/[a-z0-9.+-]+$");
        }
    }

    @Test
    @DisplayName("fileExtensionFor: returns lowercase extensions without dots")
    void fileExtensionFor_returnsLowercaseWithoutDots() {
        // All extensions should be lowercase and not start with dot
        for (ExportFormat format : ExportFormat.values()) {
            String extension = resolver.fileExtensionFor(format);
            
            assertThat(extension).isLowerCase();
            assertThat(extension).doesNotStartWith(".");
            assertThat(extension).doesNotContain(" ");
        }
    }

    @Test
    @DisplayName("fileExtensionFor: extensions are filename-safe")
    void fileExtensionFor_extensionsAreFilenameSafe() {
        // Extensions should only contain alphanumeric characters
        for (ExportFormat format : ExportFormat.values()) {
            String extension = resolver.fileExtensionFor(format);
            
            assertThat(extension).matches("^[a-z0-9]+$");
        }
    }

    // Round-trip format tests

    @Test
    @DisplayName("contentTypeFor: round-trip formats return editable MIME types")
    void contentTypeFor_roundTripFormats_returnEditableMimeTypes() {
        // JSON and XLSX are round-trip formats
        String jsonType = resolver.contentTypeFor(ExportFormat.JSON_EDITABLE);
        String xlsxType = resolver.contentTypeFor(ExportFormat.XLSX_EDITABLE);

        // JSON should be application/json for easy parsing
        assertThat(jsonType).isEqualTo("application/json");
        
        // XLSX should be the standard Office Open XML format
        assertThat(xlsxType).startsWith("application/vnd.openxmlformats");
    }

    @Test
    @DisplayName("contentTypeFor: print formats return appropriate MIME types")
    void contentTypeFor_printFormats_returnPrintMimeTypes() {
        // HTML and PDF are print formats
        String htmlType = resolver.contentTypeFor(ExportFormat.HTML_PRINT);
        String pdfType = resolver.contentTypeFor(ExportFormat.PDF_PRINT);

        // HTML should include charset for proper rendering
        assertThat(htmlType).startsWith("text/html");
        
        // PDF should be standard application/pdf
        assertThat(pdfType).isEqualTo("application/pdf");
    }

    @Test
    @DisplayName("fileExtensionFor: round-trip formats use standard extensions")
    void fileExtensionFor_roundTripFormats_useStandardExtensions() {
        // When
        String jsonExt = resolver.fileExtensionFor(ExportFormat.JSON_EDITABLE);
        String xlsxExt = resolver.fileExtensionFor(ExportFormat.XLSX_EDITABLE);

        // Then
        assertThat(jsonExt).isEqualTo("json");
        assertThat(xlsxExt).isEqualTo("xlsx");
    }

    @Test
    @DisplayName("fileExtensionFor: print formats use standard extensions")
    void fileExtensionFor_printFormats_useStandardExtensions() {
        // When
        String htmlExt = resolver.fileExtensionFor(ExportFormat.HTML_PRINT);
        String pdfExt = resolver.fileExtensionFor(ExportFormat.PDF_PRINT);

        // Then
        assertThat(htmlExt).isEqualTo("html");
        assertThat(pdfExt).isEqualTo("pdf");
    }
}

