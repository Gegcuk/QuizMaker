package uk.gegc.quizmaker.features.billing.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "token_transactions")
@Getter
@Setter
public class TokenTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private TokenTransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 32)
    private TokenTransactionSource source;

    @Column(name = "amount_tokens", nullable = false)
    private long amountTokens;

    @Column(name = "ref_id", length = 255)
    private String refId;

    @Column(name = "idempotency_key", length = 255)
    private String idempotencyKey;

    @Column(name = "meta_json", columnDefinition = "JSON")
    private String metaJson;

    @Column(name = "balance_after_available")
    private Long balanceAfterAvailable;

    @Column(name = "balance_after_reserved")
    private Long balanceAfterReserved;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
