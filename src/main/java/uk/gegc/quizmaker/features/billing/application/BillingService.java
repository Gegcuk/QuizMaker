package uk.gegc.quizmaker.features.billing.application;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import uk.gegc.quizmaker.features.billing.api.dto.BalanceDto;
import uk.gegc.quizmaker.features.billing.api.dto.CommitResultDto;
import uk.gegc.quizmaker.features.billing.api.dto.ReleaseResultDto;
import uk.gegc.quizmaker.features.billing.api.dto.ReservationDto;
import uk.gegc.quizmaker.features.billing.api.dto.TransactionDto;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransactionSource;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransactionType;

import java.time.LocalDateTime;
import java.util.UUID;

public interface BillingService {

    BalanceDto getBalance(UUID userId);

    Page<TransactionDto> listTransactions(
            UUID userId,
            Pageable pageable,
            TokenTransactionType type,
            TokenTransactionSource source,
            LocalDateTime dateFrom,
            LocalDateTime dateTo
    );

    ReservationDto reserve(UUID userId, long estimatedBillingTokens, String ref, String idempotencyKey);

    CommitResultDto commit(UUID reservationId, long actualBillingTokens, String ref, String idempotencyKey);

    ReleaseResultDto release(UUID reservationId, String reason, String ref, String idempotencyKey);
}
