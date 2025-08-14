package uk.gegc.quizmaker.features.quiz.domain.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import uk.gegc.quizmaker.model.user.User;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "share_links")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ShareLink {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quiz_id", nullable = false)
    private Quiz quiz;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash; // SHA256(pepper || token)

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 30)
    private ShareLinkScope scope; // QUIZ_VIEW, QUIZ_ATTEMPT_START

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "one_time", nullable = false)
    private boolean oneTime;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}


