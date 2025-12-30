package uk.gegc.quizmaker.features.bugreport.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.bugreport.api.dto.BugReportDto;
import uk.gegc.quizmaker.features.bugreport.api.dto.CreateBugReportRequest;
import uk.gegc.quizmaker.features.bugreport.config.BugReportProperties;
import uk.gegc.quizmaker.features.bugreport.domain.model.BugReport;
import uk.gegc.quizmaker.features.bugreport.domain.model.BugReportSeverity;
import uk.gegc.quizmaker.features.bugreport.domain.model.BugReportStatus;
import uk.gegc.quizmaker.features.bugreport.domain.repository.BugReportRepository;
import uk.gegc.quizmaker.features.bugreport.infra.mapping.BugReportMapper;
import uk.gegc.quizmaker.shared.email.EmailService;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.exception.ValidationException;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BugReportServiceImplTest {

    @Mock
    private BugReportRepository bugReportRepository;

    @Mock
    private EmailService emailService;

    @Mock
    private BugReportProperties bugReportProperties;

    private BugReportServiceImpl bugReportService;

    @BeforeEach
    void setUp() {
        bugReportService = new BugReportServiceImpl(
                bugReportRepository,
                new BugReportMapper(),
                emailService,
                bugReportProperties
        );
    }

    @Test
    @DisplayName("createReport persists bug report and sends notification")
    void createReport_persistsAndNotifies() {
        UUID id = UUID.randomUUID();
        when(bugReportProperties.getRecipient()).thenReturn("gegcuk@gmail.com");
        when(bugReportProperties.getSubject()).thenReturn("New Bug Report");
        when(bugReportRepository.save(any(BugReport.class))).thenAnswer(invocation -> {
            BugReport report = invocation.getArgument(0);
            report.setId(id);
            return report;
        });

        CreateBugReportRequest request = new CreateBugReportRequest(
                "Editor crashed",
                "Jane",
                "jane@example.com",
                "https://app.local",
                null,
                null,
                BugReportSeverity.HIGH
        );

        BugReportDto result = bugReportService.createReport(request, "127.0.0.1");

        assertThat(result.id()).isEqualTo(id);
        assertThat(result.severity()).isEqualTo(BugReportSeverity.HIGH);
        assertThat(result.status()).isEqualTo(BugReportStatus.OPEN);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendPlainTextEmail(eq("gegcuk@gmail.com"), eq("New Bug Report"), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).contains("Editor crashed").contains("Severity: HIGH");
    }

    @Test
    @DisplayName("createReport with blank message throws ValidationException")
    void createReport_blankMessage_throws() {
        CreateBugReportRequest request = new CreateBugReportRequest(
                "   ",
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertThrows(ValidationException.class, () -> bugReportService.createReport(request, "127.0.0.1"));
        verifyNoInteractions(bugReportRepository);
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("updateReport with unknown id throws ResourceNotFoundException")
    void updateReport_unknownId_throws() {
        when(bugReportRepository.findById(any())).thenReturn(java.util.Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> bugReportService.updateReport(UUID.randomUUID(), new uk.gegc.quizmaker.features.bugreport.api.dto.UpdateBugReportRequest(
                "msg", null, null, null, null, null, null, null, null
        )));
    }

    @Test
    @DisplayName("deleteReport with unknown id throws ResourceNotFoundException")
    void deleteReport_unknownId_throws() {
        when(bugReportRepository.existsById(any())).thenReturn(false);

        assertThrows(ResourceNotFoundException.class, () -> bugReportService.deleteReport(UUID.randomUUID()));
    }

    @Test
    @DisplayName("createReport does not send email when recipient not configured")
    void createReport_skipsNotificationWithoutRecipient() {
        when(bugReportProperties.getRecipient()).thenReturn("");
        when(bugReportRepository.save(any(BugReport.class))).thenAnswer(invocation -> {
            BugReport report = invocation.getArgument(0);
            report.setId(UUID.randomUUID());
            return report;
        });

        bugReportService.createReport(new CreateBugReportRequest(
                "Crash", null, null, null, null, null, null
        ), "1.1.1.1");

        verify(emailService, never()).sendPlainTextEmail(any(), any(), any());
    }

    @Test
    @DisplayName("createReport trims message before saving")
    void createReport_trimsMessage() {
        when(bugReportProperties.getRecipient()).thenReturn("gegcuk@gmail.com");
        when(bugReportProperties.getSubject()).thenReturn("New Bug Report");
        ArgumentCaptor<BugReport> captor = ArgumentCaptor.forClass(BugReport.class);
        when(bugReportRepository.save(any(BugReport.class))).thenAnswer(invocation -> {
            BugReport report = invocation.getArgument(0);
            report.setId(UUID.randomUUID());
            return report;
        });

        bugReportService.createReport(new CreateBugReportRequest(
                "  spaced message  ",
                null,
                null,
                null,
                null,
                null,
                null
        ), "2.2.2.2");

        verify(bugReportRepository).save(captor.capture());
        assertThat(captor.getValue().getMessage()).isEqualTo("spaced message");
    }

    @Test
    @DisplayName("updateReport applies fields and saves")
    void updateReport_updatesFields() {
        UUID id = UUID.randomUUID();
        BugReport existing = new BugReport();
        existing.setId(id);
        existing.setMessage("Old");
        existing.setSeverity(BugReportSeverity.UNSPECIFIED);
        existing.setStatus(BugReportStatus.OPEN);
        when(bugReportRepository.findById(id)).thenReturn(java.util.Optional.of(existing));
        when(bugReportRepository.save(any(BugReport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        uk.gegc.quizmaker.features.bugreport.api.dto.UpdateBugReportRequest request =
                new uk.gegc.quizmaker.features.bugreport.api.dto.UpdateBugReportRequest(
                        "Updated",
                        "Reporter",
                        "email@example.com",
                        "http://page",
                        "steps",
                        "client",
                        BugReportSeverity.CRITICAL,
                        BugReportStatus.IN_PROGRESS,
                        "note"
                );

        BugReportDto dto = bugReportService.updateReport(id, request);

        assertThat(dto.message()).isEqualTo("Updated");
        assertThat(dto.severity()).isEqualTo(BugReportSeverity.CRITICAL);
        assertThat(dto.status()).isEqualTo(BugReportStatus.IN_PROGRESS);
        verify(bugReportRepository).save(any(BugReport.class));
    }

    @Test
    @DisplayName("listReports delegates to repository with filters")
    void listReports_filters() {
        BugReport report = new BugReport();
        report.setId(UUID.randomUUID());
        report.setMessage("Msg");
        report.setSeverity(BugReportSeverity.LOW);
        report.setStatus(BugReportStatus.OPEN);
        Page<BugReport> page = new PageImpl<>(List.of(report), PageRequest.of(0, 10), 1);
        when(bugReportRepository.findAllByFilters(BugReportStatus.OPEN, BugReportSeverity.LOW, PageRequest.of(0, 10)))
                .thenReturn(page);

        Page<BugReportDto> result = bugReportService.listReports(BugReportStatus.OPEN, BugReportSeverity.LOW, PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).severity()).isEqualTo(BugReportSeverity.LOW);
    }
}
