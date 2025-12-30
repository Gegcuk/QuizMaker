-- Bug reports captured from user submissions and managed by administrators

CREATE TABLE IF NOT EXISTS bug_reports (
    bug_report_id BINARY(16) NOT NULL,
    message TEXT NOT NULL,
    reporter_name VARCHAR(255) NULL,
    reporter_email VARCHAR(255) NULL,
    page_url VARCHAR(1024) NULL,
    steps_to_reproduce TEXT NULL,
    client_version VARCHAR(255) NULL,
    client_ip VARCHAR(45) NULL,
    severity VARCHAR(50) NOT NULL DEFAULT 'UNSPECIFIED',
    status VARCHAR(50) NOT NULL DEFAULT 'OPEN',
    internal_note TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (bug_report_id)
) ENGINE=InnoDB;

CREATE INDEX idx_bug_reports_status ON bug_reports(status);
CREATE INDEX idx_bug_reports_severity ON bug_reports(severity);
CREATE INDEX idx_bug_reports_created_at ON bug_reports(created_at);
