package uk.gegc.quizmaker.features.quiz.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import uk.gegc.quizmaker.features.quiz.api.dto.export.QuizExportFilter;
import uk.gegc.quizmaker.features.quiz.application.export.ExportRenderer;
import uk.gegc.quizmaker.features.quiz.domain.model.ExportFormat;
import uk.gegc.quizmaker.features.quiz.domain.model.PrintOptions;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.export.ExportFile;
import uk.gegc.quizmaker.features.quiz.domain.model.export.ExportPayload;
import uk.gegc.quizmaker.features.quiz.domain.repository.export.QuizExportRepository;
import uk.gegc.quizmaker.features.quiz.infra.mapping.QuizExportAssembler;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("QuizExportServiceImpl Tests")
class QuizExportServiceImplTest {

    @Mock
    private QuizExportRepository exportRepository;

    @Mock
    private QuizExportAssembler assembler;

    @Mock
    private ExportRenderer mockRenderer;

    @Mock
    private AppPermissionEvaluator permissionEvaluator;

    private Clock clock;
    private QuizExportServiceImpl service;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2025-10-17T12:00:00Z"), ZoneId.of("UTC"));
        service = new QuizExportServiceImpl(
                exportRepository,
                assembler,
                List.of(mockRenderer),
                permissionEvaluator,
                clock
        );
    }

    @Test
    @DisplayName("export: generates unique exportId and versionCode for each export")
    void export_generatesUniqueMetadata() {
        // Given
        when(exportRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class))).thenReturn(new ArrayList<>());
        when(assembler.toExportDtos(anyList(), any(Random.class))).thenReturn(new ArrayList<>());
        when(mockRenderer.supports(ExportFormat.PDF_PRINT)).thenReturn(true);
        when(mockRenderer.render(any())).thenReturn(createMockExportFile());

        QuizExportFilter filter = new QuizExportFilter(null, null, null, null, "public", null, null);

        // When
        ExportFile result1 = service.export(filter, ExportFormat.PDF_PRINT, PrintOptions.defaults(), null);
        ExportFile result2 = service.export(filter, ExportFormat.PDF_PRINT, PrintOptions.defaults(), null);

        // Then
        ArgumentCaptor<ExportPayload> payloadCaptor = ArgumentCaptor.forClass(ExportPayload.class);
        verify(mockRenderer, times(2)).render(payloadCaptor.capture());

        List<ExportPayload> payloads = payloadCaptor.getAllValues();
        assertThat(payloads).hasSize(2);

        // Each export should have unique metadata
        assertThat(payloads.get(0).exportId()).isNotEqualTo(payloads.get(1).exportId());
        assertThat(payloads.get(0).versionCode()).isNotEqualTo(payloads.get(1).versionCode());
        assertThat(payloads.get(0).shuffleSeed()).isNotEqualTo(payloads.get(1).shuffleSeed());
    }

    @Test
    @DisplayName("export: versionCode is 6 characters and alphanumeric")
    void export_versionCodeFormat() {
        // Given
        when(exportRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class))).thenReturn(new ArrayList<>());
        when(assembler.toExportDtos(anyList(), any(Random.class))).thenReturn(new ArrayList<>());
        when(mockRenderer.supports(ExportFormat.HTML_PRINT)).thenReturn(true);
        when(mockRenderer.render(any())).thenReturn(createMockExportFile());

        QuizExportFilter filter = new QuizExportFilter(null, null, null, null, "public", null, null);

        // When
        service.export(filter, ExportFormat.HTML_PRINT, PrintOptions.defaults(), null);

        // Then
        ArgumentCaptor<ExportPayload> payloadCaptor = ArgumentCaptor.forClass(ExportPayload.class);
        verify(mockRenderer).render(payloadCaptor.capture());

        ExportPayload payload = payloadCaptor.getValue();
        assertThat(payload.versionCode()).hasSize(6);
        assertThat(payload.versionCode()).matches("[0-9A-Z]{6}");
    }

    @Test
    @DisplayName("export: passes seeded Random to assembler for deterministic shuffling")
    void export_passesSeededRandomToAssembler() {
        // Given
        when(exportRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class))).thenReturn(new ArrayList<>());
        when(assembler.toExportDtos(anyList(), any(Random.class))).thenReturn(new ArrayList<>());
        when(mockRenderer.supports(ExportFormat.PDF_PRINT)).thenReturn(true);
        when(mockRenderer.render(any())).thenReturn(createMockExportFile());

        QuizExportFilter filter = new QuizExportFilter(null, null, null, null, "public", null, null);

        // When
        service.export(filter, ExportFormat.PDF_PRINT, PrintOptions.defaults(), null);

        // Then
        ArgumentCaptor<Random> rngCaptor = ArgumentCaptor.forClass(Random.class);
        verify(assembler).toExportDtos(anyList(), rngCaptor.capture());

        // Random should have been passed (not null)
        assertThat(rngCaptor.getValue()).isNotNull();
    }

    @Test
    @DisplayName("export: shuffleSeed is derived from exportId")
    void export_shuffleSeedDerivedFromExportId() {
        // Given
        when(exportRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class))).thenReturn(new ArrayList<>());
        when(assembler.toExportDtos(anyList(), any(Random.class))).thenReturn(new ArrayList<>());
        when(mockRenderer.supports(ExportFormat.PDF_PRINT)).thenReturn(true);
        when(mockRenderer.render(any())).thenReturn(createMockExportFile());

        QuizExportFilter filter = new QuizExportFilter(null, null, null, null, "public", null, null);

        // When
        service.export(filter, ExportFormat.PDF_PRINT, PrintOptions.defaults(), null);

        // Then
        ArgumentCaptor<ExportPayload> payloadCaptor = ArgumentCaptor.forClass(ExportPayload.class);
        verify(mockRenderer).render(payloadCaptor.capture());

        ExportPayload payload = payloadCaptor.getValue();
        
        // Verify shuffleSeed is derived from exportId using XOR of most/least significant bits
        long expectedSeed = payload.exportId().getMostSignificantBits() ^ payload.exportId().getLeastSignificantBits();
        assertThat(payload.shuffleSeed()).isEqualTo(expectedSeed);
    }

    @Test
    @DisplayName("export: exportId, versionCode, and shuffleSeed are all present")
    void export_allMetadataFieldsPresent() {
        // Given
        when(exportRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class))).thenReturn(new ArrayList<>());
        when(assembler.toExportDtos(anyList(), any(Random.class))).thenReturn(new ArrayList<>());
        when(mockRenderer.supports(ExportFormat.PDF_PRINT)).thenReturn(true);
        when(mockRenderer.render(any())).thenReturn(createMockExportFile());

        QuizExportFilter filter = new QuizExportFilter(null, null, null, null, "public", null, null);

        // When
        service.export(filter, ExportFormat.PDF_PRINT, PrintOptions.defaults(), null);

        // Then
        ArgumentCaptor<ExportPayload> payloadCaptor = ArgumentCaptor.forClass(ExportPayload.class);
        verify(mockRenderer).render(payloadCaptor.capture());

        ExportPayload payload = payloadCaptor.getValue();
        assertThat(payload.exportId()).isNotNull();
        assertThat(payload.versionCode()).isNotNull().isNotBlank();
        assertThat(payload.shuffleSeed()).isNotNull();
    }

    private ExportFile createMockExportFile() {
        return new ExportFile(
                "test.pdf",
                "application/pdf",
                () -> new ByteArrayInputStream(new byte[0]),
                0
        );
    }
}

