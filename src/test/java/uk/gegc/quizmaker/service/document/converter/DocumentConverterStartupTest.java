package uk.gegc.quizmaker.service.document.converter;

import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gegc.quizmaker.features.document.application.DocumentConverter;
import uk.gegc.quizmaker.features.document.application.DocumentConverterFactory;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingLimits;
import uk.gegc.quizmaker.features.document.infra.converter.EpubDocumentConverter;
import uk.gegc.quizmaker.features.document.infra.converter.PdfDocumentConverter;
import uk.gegc.quizmaker.features.document.infra.converter.TextDocumentConverter;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Document converter minimal Spring wiring")
@Execution(ExecutionMode.CONCURRENT)
class DocumentConverterStartupTest {

    private static final List<String> ISOLATED_TEST_CLASSES = List.of(
            "uk.gegc.quizmaker.service.document.converter.DocumentConverterFactoryTest",
            "uk.gegc.quizmaker.service.document.converter.DocumentConverterStartupTest",
            "uk.gegc.quizmaker.service.document.converter.impl.EpubDocumentConverterTest"
    );

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ConverterConfiguration.class);

    @Test
    @DisplayName("Registers exactly the PDF, text, and EPUB converter strategies")
    void contextRegistersAllConverters() {
        contextRunner.run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context.getBeansOfType(DocumentConverter.class).values())
                    .extracting(DocumentConverter::getConverterType)
                    .containsExactlyInAnyOrder(
                            "PDF_DOCUMENT_CONVERTER",
                            "TEXT_DOCUMENT_CONVERTER",
                            "EPUB_DOCUMENT_CONVERTER"
                    );

            DocumentConverterFactory converterFactory = context.getBean(DocumentConverterFactory.class);
            assertThat(converterFactory.getAllConverters()).hasSize(3);
        });
    }

    @Test
    @DisplayName("Starts converter wiring without datasource, JPA, or Flyway infrastructure")
    void contextDoesNotCreateDatabaseInfrastructure() {
        contextRunner.run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
            assertThat(context.getBeansOfType(EntityManagerFactory.class)).isEmpty();
            assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
        });
    }

    @Test
    @DisplayName("Aggregates every expected type and extension through minimal Spring wiring")
    void converterFactorySupportsAllExpectedFormats() {
        contextRunner.run(context -> {
            DocumentConverterFactory converterFactory = context.getBean(DocumentConverterFactory.class);

            assertThat(converterFactory.getSupportedContentTypes()).containsExactlyInAnyOrder(
                    "application/pdf",
                    "text/plain",
                    "text/txt",
                    "application/epub+zip",
                    "application/epub",
                    "application/x-epub"
            );
            assertThat(converterFactory.getSupportedExtensions()).containsExactlyInAnyOrder(
                    ".pdf",
                    ".txt",
                    ".text",
                    ".epub"
            );
        });
    }

    @Test
    @DisplayName("Keeps pure converter tests out of Spring Boot and the database-serial lane")
    void converterTestsRemainIsolatedFromFullApplicationContextAndDatabaseLane() throws Exception {
        for (String className : ISOLATED_TEST_CLASSES) {
            Class<?> testClass = Class.forName(className);
            List<String> annotations = Arrays.stream(testClass.getAnnotations())
                    .map(annotation -> annotation.annotationType().getName())
                    .toList();
            List<String> tags = Arrays.stream(testClass.getAnnotationsByType(Tag.class))
                    .map(Tag::value)
                    .toList();

            assertThat(annotations)
                    .as("annotations on %s", className)
                    .doesNotContain("org.springframework.boot.test.context.SpringBootTest");
            assertThat(tags)
                    .as("JUnit tags on %s", className)
                    .doesNotContain("db-serial");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ConverterConfiguration {

        @Bean
        DocumentProcessingLimits documentProcessingLimits() {
            return DocumentProcessingLimits.defaults();
        }

        @Bean
        DocumentConverter pdfDocumentConverter(DocumentProcessingLimits limits) {
            return new PdfDocumentConverter(limits);
        }

        @Bean
        DocumentConverter textDocumentConverter(DocumentProcessingLimits limits) {
            return new TextDocumentConverter(limits);
        }

        @Bean
        DocumentConverter epubDocumentConverter(DocumentProcessingLimits limits) {
            return new EpubDocumentConverter(limits);
        }

        @Bean
        DocumentConverterFactory documentConverterFactory(List<DocumentConverter> converters) {
            return new DocumentConverterFactory(converters);
        }
    }
}
