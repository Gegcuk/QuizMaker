package uk.gegc.quizmaker.features.bugreport.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
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
import uk.gegc.quizmaker.shared.exception.ValidationException;

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
}
