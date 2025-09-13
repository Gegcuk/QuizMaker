package uk.gegc.quizmaker.features.billing.infra.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransaction;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransactionSource;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransactionType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface TokenTransactionRepository extends JpaRepository<TokenTransaction, UUID> {

    @Query("""
        select t from TokenTransaction t
        where t.userId = :userId
          and (:type is null or t.type = :type)
          and (:source is null or t.source = :source)
          and (:dateFrom is null or t.createdAt >= :dateFrom)
          and (:dateTo is null or t.createdAt <= :dateTo)
        order by t.createdAt desc
    """)
    Page<TokenTransaction> findByFilters(
            @Param("userId") UUID userId,
            @Param("type") TokenTransactionType type,
            @Param("source") TokenTransactionSource source,
            @Param("dateFrom") LocalDateTime dateFrom,
            @Param("dateTo") LocalDateTime dateTo,
            Pageable pageable
    );

    java.util.Optional<TokenTransaction> findByIdempotencyKey(String idempotencyKey);
    
    List<TokenTransaction> findByUserIdAndTypeAndRefIdContaining(UUID userId, TokenTransactionType type, String refId);
    
    List<TokenTransaction> findByUserIdAndTypeAndCreatedAtAfter(UUID userId, TokenTransactionType type, LocalDateTime createdAt);
    
    List<TokenTransaction> findByUserId(UUID userId);
}
