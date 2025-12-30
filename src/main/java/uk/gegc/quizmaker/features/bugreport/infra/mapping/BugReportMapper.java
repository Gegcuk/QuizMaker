package uk.gegc.quizmaker.features.bugreport.infra.mapping;

import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.bugreport.api.dto.BugReportDto;
import uk.gegc.quizmaker.features.bugreport.api.dto.CreateBugReportRequest;
import uk.gegc.quizmaker.features.bugreport.api.dto.UpdateBugReportRequest;
import uk.gegc.quizmaker.features.bugreport.domain.model.BugReport;
import uk.gegc.quizmaker.features.bugreport.domain.model.BugReportSeverity;
import uk.gegc.quizmaker.features.bugreport.domain.model.BugReportStatus;

@Component
public class BugReportMapper {

    public BugReport toEntity(CreateBugReportRequest request, String clientIp) {
        BugReport report = new BugReport();
        report.setMessage(request.message() != null ? request.message().trim() : null);
        report.setReporterName(request.reporterName());
        report.setReporterEmail(request.reporterEmail());
        report.setPageUrl(request.pageUrl());
        report.setStepsToReproduce(request.stepsToReproduce());
        report.setClientVersion(request.clientVersion());
        report.setClientIp(clientIp);
        report.setSeverity(request.severity() != null ? request.severity() : BugReportSeverity.UNSPECIFIED);
        report.setStatus(BugReportStatus.OPEN);
        return report;
    }

    public void applyUpdates(BugReport report, UpdateBugReportRequest request) {
        if (request.message() != null) {
            report.setMessage(request.message().trim());
        }
        if (request.reporterName() != null) {
            report.setReporterName(request.reporterName());
        }
        if (request.reporterEmail() != null) {
            report.setReporterEmail(request.reporterEmail());
        }
        if (request.pageUrl() != null) {
            report.setPageUrl(request.pageUrl());
        }
        if (request.stepsToReproduce() != null) {
            report.setStepsToReproduce(request.stepsToReproduce());
        }
        if (request.clientVersion() != null) {
            report.setClientVersion(request.clientVersion());
        }
        if (request.severity() != null) {
            report.setSeverity(request.severity());
        }
        if (request.status() != null) {
            report.setStatus(request.status());
        }
        if (request.internalNote() != null) {
            report.setInternalNote(request.internalNote());
        }
    }

    public BugReportDto toDto(BugReport report) {
        return new BugReportDto(
                report.getId(),
                report.getMessage(),
                report.getReporterName(),
                report.getReporterEmail(),
                report.getPageUrl(),
                report.getStepsToReproduce(),
                report.getClientVersion(),
                report.getClientIp(),
                report.getSeverity(),
                report.getStatus(),
                report.getInternalNote(),
                report.getCreatedAt(),
                report.getUpdatedAt()
        );
    }
}
