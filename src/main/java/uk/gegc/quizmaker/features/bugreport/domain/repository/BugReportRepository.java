package uk.gegc.quizmaker.features.bugreport.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uk.gegc.quizmaker.features.bugreport.domain.model.BugReport;
import uk.gegc.quizmaker.features.bugreport.domain.model.BugReportSeverity;
import uk.gegc.quizmaker.features.bugreport.domain.model.BugReportStatus;

import java.util.UUID;

public interface BugReportRepository extends JpaRepository<BugReport, UUID> {

    @Query("""
            SELECT report
            FROM BugReport report
            WHERE (:status IS NULL OR report.status = :status)
              AND (:severity IS NULL OR report.severity = :severity)
            """)
    Page<BugReport> findAllByFilters(
            @Param("status") BugReportStatus status,
            @Param("severity") BugReportSeverity severity,
            Pageable pageable
    );
}
