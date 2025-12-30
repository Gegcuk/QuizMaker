package uk.gegc.quizmaker.features.bugreport.application.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.bugreport.api.dto.BugReportDto;
import uk.gegc.quizmaker.features.bugreport.api.dto.CreateBugReportRequest;
import uk.gegc.quizmaker.features.bugreport.api.dto.UpdateBugReportRequest;
import uk.gegc.quizmaker.features.bugreport.application.BugReportService;
import uk.gegc.quizmaker.features.bugreport.config.BugReportProperties;
import uk.gegc.quizmaker.features.bugreport.domain.model.BugReport;
import uk.gegc.quizmaker.features.bugreport.domain.model.BugReportSeverity;
import uk.gegc.quizmaker.features.bugreport.domain.model.BugReportStatus;
import uk.gegc.quizmaker.features.bugreport.domain.repository.BugReportRepository;
import uk.gegc.quizmaker.features.bugreport.infra.mapping.BugReportMapper;
import uk.gegc.quizmaker.shared.email.EmailService;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.exception.ValidationException;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class BugReportServiceImpl implements BugReportService {

    private final BugReportRepository bugReportRepository;
    private final BugReportMapper bugReportMapper;
    private final EmailService emailService;
    private final BugReportProperties bugReportProperties;

    @Override
    public BugReportDto createReport(CreateBugReportRequest request, String clientIp) {
        BugReport report = bugReportMapper.toEntity(request, clientIp);
        validateMessage(report.getMessage());

        BugReport saved = bugReportRepository.save(report);
        sendNotification(saved);

        return bugReportMapper.toDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BugReportDto> listReports(BugReportStatus status, BugReportSeverity severity, Pageable pageable) {
        return bugReportRepository.findAllByFilters(status, severity, pageable)
                .map(bugReportMapper::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public BugReportDto getReport(UUID id) {
        BugReport report = bugReportRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Bug report " + id + " not found"));
        return bugReportMapper.toDto(report);
    }

    @Override
    public BugReportDto updateReport(UUID id, UpdateBugReportRequest request) {
        BugReport report = bugReportRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Bug report " + id + " not found"));

        if (request.message() != null) {
            validateMessage(request.message());
        }

        bugReportMapper.applyUpdates(report, request);
        return bugReportMapper.toDto(bugReportRepository.save(report));
    }

    @Override
    public void deleteReport(UUID id) {
        if (!bugReportRepository.existsById(id)) {
            throw new ResourceNotFoundException("Bug report " + id + " not found");
        }
        bugReportRepository.deleteById(id);
    }

    @Override
    public void deleteReports(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        bugReportRepository.deleteAllById(ids);
    }

    private void validateMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new ValidationException("Message cannot be blank");
        }
    }

    private void sendNotification(BugReport report) {
        String recipient = bugReportProperties.getRecipient();
        if (recipient == null || recipient.isBlank()) {
            log.warn("Bug report notification skipped because recipient is not configured");
            return;
        }

        String subject = bugReportProperties.getSubject();
        try {
            emailService.sendPlainTextEmail(recipient, subject, buildEmailBody(report));
        } catch (Exception ex) {
            log.error("Failed to send bug report notification for {}: {}", report.getId(), ex.getMessage(), ex);
        }
    }

    private String buildEmailBody(BugReport report) {
        StringBuilder body = new StringBuilder();
        body.append("New bug report received.\n\n");
        body.append("Message:\n").append(report.getMessage()).append("\n\n");

        if (report.getReporterName() != null) {
            body.append("Reporter: ").append(report.getReporterName()).append("\n");
        }
        if (report.getReporterEmail() != null) {
            body.append("Reporter Email: ").append(report.getReporterEmail()).append("\n");
        }
        if (report.getPageUrl() != null) {
            body.append("Page URL: ").append(report.getPageUrl()).append("\n");
        }
        if (report.getStepsToReproduce() != null) {
            body.append("\nSteps to Reproduce:\n").append(report.getStepsToReproduce()).append("\n");
        }
        if (report.getClientVersion() != null) {
            body.append("Client Version: ").append(report.getClientVersion()).append("\n");
        }
        if (report.getClientIp() != null) {
            body.append("Client IP: ").append(report.getClientIp()).append("\n");
        }

        body.append("Severity: ").append(report.getSeverity()).append("\n");
        body.append("Status: ").append(report.getStatus()).append("\n");

        if (report.getCreatedAt() != null) {
            body.append("Created At: ").append(DateTimeFormatter.ISO_INSTANT.format(report.getCreatedAt())).append("\n");
        }

        return body.toString();
    }
}
