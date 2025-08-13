package uk.gegc.quizmaker.model.quiz;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity for tracking share link usage analytics with privacy protection.
 * Stores hashed IP addresses and usage metrics for share link events.
 */
@Entity
@Table(name = "share_link_analytics")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShareLinkAnalytics {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "share_link_id", nullable = false)
    private ShareLink shareLink;

    @Column(name = "event_type", nullable = false)
    @Enumerated(EnumType.STRING)
    private ShareLinkEventType eventType;

    @Column(name = "ip_hash", length = 64, nullable = false)
    private String ipHash; // SHA256 hash of IP with pepper and date bucket

    @Column(name = "user_agent", length = 256)
    private String userAgent; // Truncated to 256 chars

    @Column(name = "date_bucket", nullable = false)
    private String dateBucket; // YYYY-MM-DD format for privacy and aggregation

    @Column(name = "country_code", length = 2)
    private String countryCode; // Optional: ISO 3166-1 alpha-2

    @Column(name = "referrer", length = 512)
    private String referrer; // Truncated referrer URL

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
