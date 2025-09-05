package uk.gegc.quizmaker.features.billing.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "balances")
@Getter
@Setter
public class Balance {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "available_tokens", nullable = false)
    private long availableTokens;

    @Column(name = "reserved_tokens", nullable = false)
    private long reservedTokens;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}

