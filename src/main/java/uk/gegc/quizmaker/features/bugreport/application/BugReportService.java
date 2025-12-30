package uk.gegc.quizmaker.features.bugreport.application;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import uk.gegc.quizmaker.features.bugreport.api.dto.BugReportDto;
import uk.gegc.quizmaker.features.bugreport.api.dto.CreateBugReportRequest;
import uk.gegc.quizmaker.features.bugreport.api.dto.UpdateBugReportRequest;
import uk.gegc.quizmaker.features.bugreport.domain.model.BugReportSeverity;
import uk.gegc.quizmaker.features.bugreport.domain.model.BugReportStatus;

import java.util.List;
import java.util.UUID;

public interface BugReportService {

    BugReportDto createReport(CreateBugReportRequest request, String clientIp);

    Page<BugReportDto> listReports(BugReportStatus status, BugReportSeverity severity, Pageable pageable);

    BugReportDto getReport(UUID id);

    BugReportDto updateReport(UUID id, UpdateBugReportRequest request);

    void deleteReport(UUID id);

    void deleteReports(List<UUID> ids);
}
