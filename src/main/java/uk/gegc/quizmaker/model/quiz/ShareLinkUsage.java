package uk.gegc.quizmaker.model.quiz;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "share_link_usage")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ShareLinkUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "share_link_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ShareLink shareLink;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "ip_hash", length = 64)
    private String ipHash; // SHA-256 of IP (privacy)

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}


