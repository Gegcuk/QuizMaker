package uk.gegc.quizmaker.features.quiz.domain.model.export;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Execution(ExecutionMode.CONCURRENT)
@DisplayName("ExportFile Tests")
class ExportFileTest {

    @Test
    @DisplayName("constructor: valid parameters creates export file")
    void constructor_validParameters_createsExportFile() {
        // Given
        String filename = "test.json";
        String contentType = "application/json";
        byte[] content = "test content".getBytes(StandardCharsets.UTF_8);
        Supplier<InputStream> supplier = () -> new ByteArrayInputStream(content);
        long length = content.length;

        // When
        ExportFile file = new ExportFile(filename, contentType, supplier, length);

        // Then
        assertThat(file.filename()).isEqualTo(filename);
        assertThat(file.contentType()).isEqualTo(contentType);
        assertThat(file.contentSupplier()).isEqualTo(supplier);
        assertThat(file.contentLength()).isEqualTo(length);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "  ", "\t", "\n"})
    @DisplayName("constructor: null or blank filename throws IllegalArgumentException")
    void constructor_nullOrBlankFilename_throwsException(String invalidFilename) {
        // Given
        String contentType = "application/json";
        Supplier<InputStream> supplier = () -> new ByteArrayInputStream(new byte[0]);

        // When & Then
        assertThatThrownBy(() -> new ExportFile(invalidFilename, contentType, supplier, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Filename cannot be null or blank");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "  ", "\t", "\n"})
    @DisplayName("constructor: null or blank contentType throws IllegalArgumentException")
    void constructor_nullOrBlankContentType_throwsException(String invalidContentType) {
        // Given
        String filename = "test.json";
        Supplier<InputStream> supplier = () -> new ByteArrayInputStream(new byte[0]);

        // When & Then
        assertThatThrownBy(() -> new ExportFile(filename, invalidContentType, supplier, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Content type cannot be null or blank");
    }

    @Test
    @DisplayName("constructor: null content supplier throws IllegalArgumentException")
    void constructor_nullContentSupplier_throwsException() {
        // Given
        String filename = "test.json";
        String contentType = "application/json";

        // When & Then
        assertThatThrownBy(() -> new ExportFile(filename, contentType, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Content supplier cannot be null");
    }

    @Test
    @DisplayName("constructor: negative content length is coerced to -1")
    void constructor_negativeContentLength_coercedToMinusOne() {
        // Given
        String filename = "test.json";
        String contentType = "application/json";
        Supplier<InputStream> supplier = () -> new ByteArrayInputStream(new byte[0]);

        // When
        ExportFile file = new ExportFile(filename, contentType, supplier, -100);

        // Then
        assertThat(file.contentLength()).isEqualTo(-1);
    }

    @Test
    @DisplayName("constructor: zero content length is preserved")
    void constructor_zeroContentLength_preserved() {
        // Given
        String filename = "empty.json";
        String contentType = "application/json";
        Supplier<InputStream> supplier = () -> new ByteArrayInputStream(new byte[0]);

        // When
        ExportFile file = new ExportFile(filename, contentType, supplier, 0);

        // Then
        assertThat(file.contentLength()).isEqualTo(0);
    }

    @Test
    @DisplayName("constructor: positive content length is preserved")
    void constructor_positiveContentLength_preserved() {
        // Given
        String filename = "test.json";
        String contentType = "application/json";
        Supplier<InputStream> supplier = () -> new ByteArrayInputStream(new byte[100]);
        long length = 100;

        // When
        ExportFile file = new ExportFile(filename, contentType, supplier, length);

        // Then
        assertThat(file.contentLength()).isEqualTo(100);
    }

    @Test
    @DisplayName("constructor: very large content length is preserved")
    void constructor_veryLargeContentLength_preserved() {
        // Given
        String filename = "large.xlsx";
        String contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        Supplier<InputStream> supplier = () -> new ByteArrayInputStream(new byte[0]);
        long largeLength = 1_000_000_000L; // 1GB

        // When
        ExportFile file = new ExportFile(filename, contentType, supplier, largeLength);

        // Then
        assertThat(file.contentLength()).isEqualTo(1_000_000_000L);
    }

    @Test
    @DisplayName("contentSupplier: can be invoked multiple times")
    void contentSupplier_canBeInvokedMultipleTimes() {
        // Given
        byte[] content = "test".getBytes(StandardCharsets.UTF_8);
        Supplier<InputStream> supplier = () -> new ByteArrayInputStream(content);
        ExportFile file = new ExportFile("test.json", "application/json", supplier, content.length);

        // When & Then - can invoke multiple times
        try (InputStream is1 = file.contentSupplier().get()) {
            assertThat(is1).isNotNull();
            assertThat(is1.readAllBytes()).isEqualTo(content);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        try (InputStream is2 = file.contentSupplier().get()) {
            assertThat(is2).isNotNull();
            assertThat(is2.readAllBytes()).isEqualTo(content);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("constructor: handles filename with special characters")
    void constructor_filenameWithSpecialChars_accepted() {
        // Given
        String filename = "quizzes_me_2024-01-01_cat3_tag2.json";
        String contentType = "application/json";
        Supplier<InputStream> supplier = () -> new ByteArrayInputStream(new byte[0]);

        // When
        ExportFile file = new ExportFile(filename, contentType, supplier, 0);

        // Then
        assertThat(file.filename()).isEqualTo(filename);
    }

    @Test
    @DisplayName("constructor: handles content type with parameters")
    void constructor_contentTypeWithParameters_accepted() {
        // Given
        String filename = "test.html";
        String contentType = "text/html; charset=utf-8";
        Supplier<InputStream> supplier = () -> new ByteArrayInputStream(new byte[0]);

        // When
        ExportFile file = new ExportFile(filename, contentType, supplier, 0);

        // Then
        assertThat(file.contentType()).isEqualTo(contentType);
    }

    @Test
    @DisplayName("constructor: handles very long filename")
    void constructor_veryLongFilename_accepted() {
        // Given
        String filename = "a".repeat(255) + ".json";
        String contentType = "application/json";
        Supplier<InputStream> supplier = () -> new ByteArrayInputStream(new byte[0]);

        // When
        ExportFile file = new ExportFile(filename, contentType, supplier, 0);

        // Then
        assertThat(file.filename()).hasSize(260);
    }

    @Test
    @DisplayName("constructor: handles unicode characters in filename")
    void constructor_unicodeFilename_accepted() {
        // Given
        String filename = "тест_测试_テスト.json";
        String contentType = "application/json";
        Supplier<InputStream> supplier = () -> new ByteArrayInputStream(new byte[0]);

        // When
        ExportFile file = new ExportFile(filename, contentType, supplier, 0);

        // Then
        assertThat(file.filename()).isEqualTo(filename);
    }

    @Test
    @DisplayName("constructor: handles content type with multiple parameters")
    void constructor_contentTypeWithMultipleParams_accepted() {
        // Given
        String filename = "test.json";
        String contentType = "application/json; charset=utf-8; boundary=something";
        Supplier<InputStream> supplier = () -> new ByteArrayInputStream(new byte[0]);

        // When
        ExportFile file = new ExportFile(filename, contentType, supplier, 0);

        // Then
        assertThat(file.contentType()).isEqualTo(contentType);
    }

    @Test
    @DisplayName("constructor: supplier returns different instances each time")
    void constructor_supplier_returnsDifferentInstances() {
        // Given
        byte[] content = "test".getBytes(StandardCharsets.UTF_8);
        Supplier<InputStream> supplier = () -> new ByteArrayInputStream(content);
        ExportFile file = new ExportFile("test.json", "application/json", supplier, content.length);

        // When
        InputStream is1 = file.contentSupplier().get();
        InputStream is2 = file.contentSupplier().get();

        // Then - different instances
        assertThat(is1).isNotSameAs(is2);
    }

    @Test
    @DisplayName("constructor: handles unknown content length (-1)")
    void constructor_unknownLength_acceptsMinusOne() {
        // Given
        String filename = "streaming.json";
        String contentType = "application/json";
        Supplier<InputStream> supplier = () -> new ByteArrayInputStream(new byte[0]);

        // When
        ExportFile file = new ExportFile(filename, contentType, supplier, -1);

        // Then
        assertThat(file.contentLength()).isEqualTo(-1);
    }

    @Test
    @DisplayName("constructor: content length of Long.MAX_VALUE is preserved")
    void constructor_maxLongContentLength_preserved() {
        // Given
        String filename = "huge.pdf";
        String contentType = "application/pdf";
        Supplier<InputStream> supplier = () -> new ByteArrayInputStream(new byte[0]);

        // When
        ExportFile file = new ExportFile(filename, contentType, supplier, Long.MAX_VALUE);

        // Then
        assertThat(file.contentLength()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("constructor: handles all supported content types")
    void constructor_supportedContentTypes_allAccepted() {
        Supplier<InputStream> supplier = () -> new ByteArrayInputStream(new byte[0]);

        // JSON
        ExportFile json = new ExportFile("test.json", "application/json", supplier, 0);
        assertThat(json.contentType()).isEqualTo("application/json");

        // XLSX
        ExportFile xlsx = new ExportFile("test.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", supplier, 0);
        assertThat(xlsx.contentType()).contains("spreadsheet");

        // HTML
        ExportFile html = new ExportFile("test.html", "text/html; charset=utf-8", supplier, 0);
        assertThat(html.contentType()).startsWith("text/html");

        // PDF
        ExportFile pdf = new ExportFile("test.pdf", "application/pdf", supplier, 0);
        assertThat(pdf.contentType()).isEqualTo("application/pdf");
    }

    @Test
    @DisplayName("constructor: handles filename with multiple extensions")
    void constructor_filenameWithMultipleExtensions_accepted() {
        // Given
        String filename = "backup.2024-01-01.tar.gz";
        String contentType = "application/gzip";
        Supplier<InputStream> supplier = () -> new ByteArrayInputStream(new byte[0]);

        // When
        ExportFile file = new ExportFile(filename, contentType, supplier, 0);

        // Then
        assertThat(file.filename()).isEqualTo(filename);
    }

    @Test
    @DisplayName("constructor: handles filename without extension")
    void constructor_filenameWithoutExtension_accepted() {
        // Given
        String filename = "export_file";
        String contentType = "application/octet-stream";
        Supplier<InputStream> supplier = () -> new ByteArrayInputStream(new byte[0]);

        // When
        ExportFile file = new ExportFile(filename, contentType, supplier, 0);

        // Then
        assertThat(file.filename()).isEqualTo(filename);
    }

    @Test
    @DisplayName("contentSupplier: returns working input stream")
    void contentSupplier_returnsWorkingInputStream() throws Exception {
        // Given
        byte[] content = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        Supplier<InputStream> supplier = () -> new ByteArrayInputStream(content);
        ExportFile file = new ExportFile("test.txt", "text/plain", supplier, content.length);

        // When
        byte[] read;
        try (InputStream is = file.contentSupplier().get()) {
            read = is.readAllBytes();
        }

        // Then
        assertThat(read).isEqualTo(content);
        assertThat(new String(read, StandardCharsets.UTF_8)).isEqualTo("Hello, World!");
    }

    @Test
    @DisplayName("constructor: handles empty content")
    void constructor_emptyContent_accepted() {
        // Given
        byte[] emptyContent = new byte[0];
        Supplier<InputStream> supplier = () -> new ByteArrayInputStream(emptyContent);
        ExportFile file = new ExportFile("empty.json", "application/json", supplier, 0);

        // When
        byte[] read;
        try (InputStream is = file.contentSupplier().get()) {
            read = is.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Then
        assertThat(read).isEmpty();
        assertThat(file.contentLength()).isEqualTo(0);
    }

    @Test
    @DisplayName("constructor: validates filename before other parameters")
    void constructor_validatesFilenameFirst() {
        // When & Then
        assertThatThrownBy(() -> new ExportFile(null, null, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Filename");
    }

    @Test
    @DisplayName("constructor: validates contentType after filename")
    void constructor_validatesContentTypeSecond() {
        // When & Then
        assertThatThrownBy(() -> new ExportFile("test.json", null, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Content type");
    }

    @Test
    @DisplayName("constructor: validates contentSupplier after contentType")
    void constructor_validatesContentSupplierThird() {
        // When & Then
        assertThatThrownBy(() -> new ExportFile("test.json", "application/json", null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Content supplier");
    }

    @Test
    @DisplayName("constructor: handles path separators in filename")
    void constructor_filenameWithPathSeparators_accepted() {
        // Given - filename might have path-like structure (though not recommended)
        String filename = "exports/2024/quizzes_me_202401011200.json";
        String contentType = "application/json";
        Supplier<InputStream> supplier = () -> new ByteArrayInputStream(new byte[0]);

        // When
        ExportFile file = new ExportFile(filename, contentType, supplier, 0);

        // Then
        assertThat(file.filename()).isEqualTo(filename);
    }

    @Test
    @DisplayName("constructor: negative length -5 is coerced to -1")
    void constructor_negativeLengthMinusFive_coercedToMinusOne() {
        // Given
        Supplier<InputStream> supplier = () -> new ByteArrayInputStream(new byte[0]);

        // When
        ExportFile file = new ExportFile("test.json", "application/json", supplier, -5);

        // Then
        assertThat(file.contentLength()).isEqualTo(-1);
    }

    @Test
    @DisplayName("constructor: negative length Long.MIN_VALUE is coerced to -1")
    void constructor_negativeLengthMinValue_coercedToMinusOne() {
        // Given
        Supplier<InputStream> supplier = () -> new ByteArrayInputStream(new byte[0]);

        // When
        ExportFile file = new ExportFile("test.json", "application/json", supplier, Long.MIN_VALUE);

        // Then
        assertThat(file.contentLength()).isEqualTo(-1);
    }

    @Test
    @DisplayName("constructor: handles realistic export scenarios")
    void constructor_realisticExportScenarios_work() {
        // Scenario 1: JSON export with known size
        byte[] jsonContent = "[{\"id\":\"123\"}]".getBytes(StandardCharsets.UTF_8);
        ExportFile json = new ExportFile(
                "quizzes_public_202410141200.json",
                "application/json",
                () -> new ByteArrayInputStream(jsonContent),
                jsonContent.length
        );
        assertThat(json.filename()).endsWith(".json");
        assertThat(json.contentLength()).isPositive();

        // Scenario 2: Streaming HTML with unknown size
        ExportFile html = new ExportFile(
                "quizzes_me_202410141200_cat2_tag3.html",
                "text/html; charset=utf-8",
                () -> new ByteArrayInputStream("<html></html>".getBytes()),
                -1 // Unknown length
        );
        assertThat(html.contentLength()).isEqualTo(-1);

        // Scenario 3: Large XLSX file
        ExportFile xlsx = new ExportFile(
                "quizzes_all_202410141200_ids500.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                () -> new ByteArrayInputStream(new byte[1024 * 1024]), // 1MB
                1024 * 1024
        );
        assertThat(xlsx.contentLength()).isEqualTo(1024 * 1024);
    }

    @Test
    @DisplayName("contentSupplier: lazy evaluation - not invoked during construction")
    void contentSupplier_lazyEvaluation_notInvokedDuringConstruction() {
        // Given
        final boolean[] invoked = {false};
        Supplier<InputStream> supplier = () -> {
            invoked[0] = true;
            return new ByteArrayInputStream(new byte[0]);
        };

        // When
        ExportFile file = new ExportFile("test.json", "application/json", supplier, 0);

        // Then - supplier not invoked yet
        assertThat(invoked[0]).isFalse();

        // When - invoke supplier
        file.contentSupplier().get();

        // Then - now invoked
        assertThat(invoked[0]).isTrue();
    }

    @Test
    @DisplayName("constructor: accepts minimal valid inputs")
    void constructor_minimalValidInputs_succeeds() {
        // Given
        String filename = "x";
        String contentType = "a";
        Supplier<InputStream> supplier = () -> null; // Supplier can return null (though not recommended)

        // When
        ExportFile file = new ExportFile(filename, contentType, supplier, 0);

        // Then
        assertThat(file.filename()).isEqualTo("x");
        assertThat(file.contentType()).isEqualTo("a");
    }

    @Test
    @DisplayName("constructor: record provides all accessor methods")
    void constructor_recordAccessors_work() {
        // Given
        String filename = "test.json";
        String contentType = "application/json";
        Supplier<InputStream> supplier = () -> new ByteArrayInputStream(new byte[10]);
        long length = 10;

        // When
        ExportFile file = new ExportFile(filename, contentType, supplier, length);

        // Then - all accessors work
        assertThat(file.filename()).isEqualTo(filename);
        assertThat(file.contentType()).isEqualTo(contentType);
        assertThat(file.contentSupplier()).isNotNull();
        assertThat(file.contentLength()).isEqualTo(length);
    }
}

