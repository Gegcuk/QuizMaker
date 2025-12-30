package uk.gegc.quizmaker.features.bugreport.domain.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bug_reports")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BugReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "bug_report_id")
    private UUID id;

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "reporter_name", length = 255)
    private String reporterName;

    @Column(name = "reporter_email", length = 255)
    private String reporterEmail;

    @Column(name = "page_url", length = 1024)
    private String pageUrl;

    @Column(name = "steps_to_reproduce", columnDefinition = "TEXT")
    private String stepsToReproduce;

    @Column(name = "client_version", length = 255)
    private String clientVersion;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 50)
    private BugReportSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private BugReportStatus status;

    @Column(name = "internal_note", columnDefinition = "TEXT")
    private String internalNote;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void applyDefaults() {
        if (severity == null) {
            severity = BugReportSeverity.UNSPECIFIED;
        }
        if (status == null) {
            status = BugReportStatus.OPEN;
        }
    }
}
