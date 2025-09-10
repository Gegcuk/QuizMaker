package uk.gegc.quizmaker.features.billing.application.impl;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import uk.gegc.quizmaker.features.billing.api.dto.BalanceDto;
import uk.gegc.quizmaker.features.billing.api.dto.ReservationDto;
import uk.gegc.quizmaker.features.billing.api.dto.CommitResultDto;
import uk.gegc.quizmaker.features.billing.api.dto.ReleaseResultDto;
import uk.gegc.quizmaker.features.billing.api.dto.TransactionDto;
import uk.gegc.quizmaker.features.billing.application.BillingProperties;
import uk.gegc.quizmaker.features.billing.application.BillingService;
import uk.gegc.quizmaker.features.billing.domain.exception.InsufficientTokensException;
import uk.gegc.quizmaker.features.billing.domain.exception.ReservationNotActiveException;
import uk.gegc.quizmaker.features.billing.domain.exception.CommitExceedsReservedException;
import uk.gegc.quizmaker.features.billing.domain.exception.IdempotencyConflictException;
import uk.gegc.quizmaker.features.billing.domain.model.*;
import uk.gegc.quizmaker.features.billing.infra.mapping.BalanceMapper;
import uk.gegc.quizmaker.features.billing.infra.mapping.ReservationMapper;
import uk.gegc.quizmaker.features.billing.infra.mapping.TokenTransactionMapper;
import uk.gegc.quizmaker.features.billing.infra.repository.BalanceRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.ReservationRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.TokenTransactionRepository;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@Validated
@RequiredArgsConstructor
public class BillingServiceImpl implements BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingServiceImpl.class);

    private final BillingProperties billingProperties;
    private final BalanceRepository balanceRepository;
    private final TokenTransactionRepository transactionRepository;
    private final ReservationRepository reservationRepository;

    private final BalanceMapper balanceMapper;
    @Qualifier("tokenTransactionMapperImpl")
    private final TokenTransactionMapper transactionMapper;
    private final ReservationMapper reservationMapper;

    @PersistenceContext
    private EntityManager entityManager;

    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    @PreAuthorize("hasAuthority('billing:read')")
    public BalanceDto getBalance(UUID userId) {
        var balance = balanceRepository.findByUserId(userId).orElseGet(() -> {
            var b = new Balance();
            b.setUserId(userId);
            b.setAvailableTokens(0L);
            b.setReservedTokens(0L);
            return balanceRepository.save(b);
        });
        return balanceMapper.toDto(balance);
    }

    @Override
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('billing:read')")
    public Page<TransactionDto> listTransactions(UUID userId,
                                                 Pageable pageable,
                                                 TokenTransactionType type,
                                                 TokenTransactionSource source,
                                                 LocalDateTime dateFrom,
                                                 LocalDateTime dateTo) {
        return transactionRepository
                .findByFilters(userId, type, source, dateFrom, dateTo, pageable)
                .map(transactionMapper::toDto);
    }

    @Override
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public ReservationDto reserve(UUID userId, long estimatedBillingTokens, String ref, String idempotencyKey) {
        if (estimatedBillingTokens <= 0) {
            throw new IllegalArgumentException("estimatedBillingTokens must be > 0");
        }

        // Idempotency: if key exists and maps to a previous RESERVE, return the same reservation
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                var tx = existing.get();
                if (tx.getType() != TokenTransactionType.RESERVE) {
                    throw new IdempotencyConflictException("Idempotency key already used for a different operation");
                }
                if (tx.getRefId() == null) {
                    throw new IllegalStateException("Existing RESERVE transaction missing refId");
                }
                try {
                    UUID resId = UUID.fromString(tx.getRefId());
                    var res = reservationRepository.findById(resId)
                            .orElseThrow(() -> new IllegalStateException("Reservation referenced by idempotent key not found"));
                    // Optional: enforce payload consistency (amount must match)
                    if (res.getEstimatedTokens() != estimatedBillingTokens) {
                        throw new IdempotencyConflictException("Idempotency key reused with different reservation amount");
                    }
                    return reservationMapper.toDto(res);
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException("Invalid reservation UUID in REF for prior idempotent RESERVE");
                }
            }
        }

        int attempts = 0;
        while (true) {
            attempts++;
            try {
                // Load or create balance row
                Balance balance = balanceRepository.findByUserId(userId).orElseGet(() -> {
                    Balance b = new Balance();
                    b.setUserId(userId);
                    b.setAvailableTokens(0L);
                    b.setReservedTokens(0L);
                    return balanceRepository.save(b);
                });

                long available = balance.getAvailableTokens();
                long reserved = balance.getReservedTokens();

                if (available < estimatedBillingTokens) {
                    throw new InsufficientTokensException("Not enough tokens to reserve: required=" + estimatedBillingTokens + ", available=" + available);
                }

                // Move available -> reserved
                balance.setAvailableTokens(available - estimatedBillingTokens);
                balance.setReservedTokens(reserved + estimatedBillingTokens);
                balanceRepository.save(balance);

                // Create reservation ACTIVE with TTL
                Reservation res = new Reservation();
                res.setUserId(userId);
                res.setState(ReservationState.ACTIVE);
                res.setEstimatedTokens(estimatedBillingTokens);
                res.setCommittedTokens(0L);
                res.setMetaJson(null);
                res.setExpiresAt(LocalDateTime.now().plusMinutes(billingProperties.getReservationTtlMinutes()));
                res.setJobId(null);
                res = reservationRepository.save(res);

                // Append RESERVE transaction entry (amount 0, snapshots of balances)
                TokenTransaction tx = new TokenTransaction();
                tx.setUserId(userId);
                tx.setType(TokenTransactionType.RESERVE);
                tx.setSource(TokenTransactionSource.QUIZ_GENERATION);
                tx.setAmountTokens(0L);
                tx.setRefId(res.getId().toString());
                tx.setIdempotencyKey(idempotencyKey);
                tx.setMetaJson(buildMetaJson(ref, null, null));
                tx.setBalanceAfterAvailable(balance.getAvailableTokens());
                tx.setBalanceAfterReserved(balance.getReservedTokens());
                transactionRepository.save(tx);
                // Flush to surface any optimistic locking issues within the retry loop
                entityManager.flush();
                return reservationMapper.toDto(res);
            } catch (OptimisticLockingFailureException ex) {
                if (attempts >= 2) {
                    log.warn("reserve() optimistic lock failed after {} attempts for user {}", attempts, userId);
                    throw ex;
                }
                try {
                    Thread.sleep(20L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                // brief retry: loop and reattempt
            } catch (DataIntegrityViolationException ex) {
                // Handle race when two requests insert the same idempotencyKey concurrently
                if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                    var existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
                    if (existing.isPresent() && existing.get().getType() == TokenTransactionType.RESERVE) {
                        try {
                            UUID resId = UUID.fromString(existing.get().getRefId());
                            var res = reservationRepository.findById(resId)
                                    .orElseThrow(() -> new IllegalStateException("Reservation referenced by idempotent key not found after race"));
                            return reservationMapper.toDto(res);
                        } catch (Exception ignored) {
                            // fall through
                        }
                    }
                }
                throw ex;
            }
        }
    }

    @Override
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public CommitResultDto commit(UUID reservationId, long actualBillingTokens, String ref, String idempotencyKey) {
        if (actualBillingTokens <= 0) {
            throw new IllegalArgumentException("actualBillingTokens must be > 0");
        }

        // Idempotency: if idempotencyKey exists and linked to this reservation + COMMIT, return current result
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                var tx = existing.get();
                if (tx.getType() != TokenTransactionType.COMMIT || !reservationId.toString().equals(tx.getRefId())) {
                    throw new IdempotencyConflictException("Idempotency key already used for a different operation or resource");
                }
                // Return current reservation state
                var existingRes = reservationRepository.findById(reservationId)
                        .orElseThrow(() -> new IllegalStateException("Reservation not found after idempotent commit"));
                long released = Math.max(0, existingRes.getEstimatedTokens() - existingRes.getCommittedTokens());
                return new CommitResultDto(reservationId, existingRes.getCommittedTokens(), released);
            }
        }

        int attempts = 0;
        while (true) {
            attempts++;
            try {
                Reservation res = reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE)
                        .orElseThrow(() -> new ReservationNotActiveException("Reservation is not ACTIVE or not found"));

                if (actualBillingTokens > res.getEstimatedTokens()) {
                    throw new CommitExceedsReservedException("Actual usage exceeds reserved tokens");
                }

                Balance balance = balanceRepository.findByUserId(res.getUserId())
                        .orElseThrow(() -> new IllegalStateException("Balance not found for user"));

                long remainder = res.getEstimatedTokens() - actualBillingTokens;

                // First: decrease reserved by committed amount
                balance.setReservedTokens(balance.getReservedTokens() - actualBillingTokens);
                balanceRepository.save(balance);

                // Update reservation to COMMITTED
                res.setCommittedTokens(actualBillingTokens);
                res.setState(ReservationState.COMMITTED);
                reservationRepository.save(res);

                // Emit COMMIT transaction with amountTokens = committed; snapshots after commit
                TokenTransaction commitTx = new TokenTransaction();
                commitTx.setUserId(res.getUserId());
                commitTx.setType(TokenTransactionType.COMMIT);
                commitTx.setSource(TokenTransactionSource.QUIZ_GENERATION);
                commitTx.setAmountTokens(actualBillingTokens);
                commitTx.setRefId(reservationId.toString());
                commitTx.setIdempotencyKey(idempotencyKey);
                commitTx.setMetaJson(buildMetaJson(ref, null, null));
                commitTx.setBalanceAfterAvailable(balance.getAvailableTokens());
                commitTx.setBalanceAfterReserved(balance.getReservedTokens());
                transactionRepository.save(commitTx);

                // Then: if remainder exists, move reserved -> available for the difference and record RELEASE tx with amount 0
                if (remainder > 0) {
                    balance.setReservedTokens(balance.getReservedTokens() - remainder);
                    balance.setAvailableTokens(balance.getAvailableTokens() + remainder);
                    balanceRepository.save(balance);

                    TokenTransaction releaseTx = new TokenTransaction();
                    releaseTx.setUserId(res.getUserId());
                    releaseTx.setType(TokenTransactionType.RELEASE);
                    releaseTx.setSource(TokenTransactionSource.QUIZ_GENERATION);
                    releaseTx.setAmountTokens(0L);
                    releaseTx.setRefId(reservationId.toString());
                    releaseTx.setIdempotencyKey(null);
                    releaseTx.setMetaJson(buildMetaJson(null, "commit-remainder", remainder));
                    releaseTx.setBalanceAfterAvailable(balance.getAvailableTokens());
                    releaseTx.setBalanceAfterReserved(balance.getReservedTokens());
                    transactionRepository.save(releaseTx);
                }
                // Flush to detect optimistic lock issues early within the loop
                entityManager.flush();
                return new CommitResultDto(reservationId, actualBillingTokens, remainder);
            } catch (OptimisticLockingFailureException ex) {
                if (attempts >= 2) {
                    log.warn("commit() optimistic lock failed after {} attempts for reservation {}", attempts, reservationId);
                    throw ex;
                }
                try { Thread.sleep(20L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
    }

    @Override
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public ReleaseResultDto release(UUID reservationId, String reason, String ref, String idempotencyKey) {
        // Idempotency handling
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                var tx = existing.get();
                if (tx.getType() != TokenTransactionType.RELEASE || !reservationId.toString().equals(tx.getRefId())) {
                    throw new IdempotencyConflictException("Idempotency key already used for a different operation or resource");
                }
                // return current released amount based on reservation state
                var existingRes = reservationRepository.findById(reservationId)
                        .orElseThrow(() -> new IllegalStateException("Reservation not found after idempotent release"));
                long released = existingRes.getState() == ReservationState.ACTIVE ? 0 : existingRes.getEstimatedTokens() - existingRes.getCommittedTokens();
                if (existingRes.getState() == ReservationState.RELEASED) {
                    released = existingRes.getEstimatedTokens();
                }
                return new ReleaseResultDto(reservationId, released);
            }
        }

        int attempts = 0;
        while (true) {
            attempts++;
            try {
                Reservation res = reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE)
                        .orElseThrow(() -> new ReservationNotActiveException("Reservation is not ACTIVE or not found"));

                Balance balance = balanceRepository.findByUserId(res.getUserId())
                        .orElseThrow(() -> new IllegalStateException("Balance not found for user"));

                long releaseAmount = res.getEstimatedTokens();

                // Move reserved -> available fully
                balance.setReservedTokens(balance.getReservedTokens() - releaseAmount);
                balance.setAvailableTokens(balance.getAvailableTokens() + releaseAmount);
                balanceRepository.save(balance);

                // Update reservation state
                res.setState(ReservationState.RELEASED);
                reservationRepository.save(res);

                // Emit RELEASE transaction with amount 0 and idempotency
                TokenTransaction tx = new TokenTransaction();
                tx.setUserId(res.getUserId());
                tx.setType(TokenTransactionType.RELEASE);
                tx.setSource(TokenTransactionSource.QUIZ_GENERATION);
                tx.setAmountTokens(0L);
                tx.setRefId(reservationId.toString());
                tx.setIdempotencyKey(idempotencyKey);
                tx.setMetaJson(buildMetaJson(ref, reason, null));
                tx.setBalanceAfterAvailable(balance.getAvailableTokens());
                tx.setBalanceAfterReserved(balance.getReservedTokens());
                transactionRepository.save(tx);
                // Flush to detect optimistic lock issues early within the loop
                entityManager.flush();
                return new ReleaseResultDto(reservationId, releaseAmount);
            } catch (OptimisticLockingFailureException ex) {
                if (attempts >= 2) {
                    log.warn("release() optimistic lock failed after {} attempts for reservation {}", attempts, reservationId);
                    throw ex;
                }
                try { Thread.sleep(20L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
    }

    @Override
    @Transactional
    public void creditPurchase(UUID userId, long tokens, String idempotencyKey, String ref, String metaJson) {
        if (tokens <= 0) {
            throw new IllegalArgumentException("tokens must be > 0");
        }

        // Idempotency: if a PURCHASE with this key already exists, noop
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                var tx = existing.get();
                if (tx.getType() != TokenTransactionType.PURCHASE) {
                    throw new IdempotencyConflictException("Idempotency key already used for a different operation");
                }
                return; // already credited
            }
        }

        int attempts = 0;
        while (true) {
            attempts++;
            try {
                Balance balance = balanceRepository.findByUserId(userId).orElseGet(() -> {
                    Balance b = new Balance();
                    b.setUserId(userId);
                    b.setAvailableTokens(0L);
                    b.setReservedTokens(0L);
                    return balanceRepository.save(b);
                });

                // Credit tokens to available
                balance.setAvailableTokens(balance.getAvailableTokens() + tokens);
                balanceRepository.save(balance);

                // Append PURCHASE transaction with snapshots
                TokenTransaction tx = new TokenTransaction();
                tx.setUserId(userId);
                tx.setType(TokenTransactionType.PURCHASE);
                tx.setSource(TokenTransactionSource.STRIPE);
                tx.setAmountTokens(tokens);
                tx.setRefId(ref);
                tx.setIdempotencyKey(idempotencyKey);
                tx.setMetaJson(metaJson);
                tx.setBalanceAfterAvailable(balance.getAvailableTokens());
                tx.setBalanceAfterReserved(balance.getReservedTokens());
                transactionRepository.save(tx);

                entityManager.flush();
                return;
            } catch (OptimisticLockingFailureException ex) {
                if (attempts >= 2) {
                    log.warn("creditPurchase() optimistic lock failed after {} attempts for user {}", attempts, userId);
                    throw ex;
                }
                try { Thread.sleep(20L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            } catch (DataIntegrityViolationException ex) {
                // handle race on idempotency key
                if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                    var existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
                    if (existing.isPresent() && existing.get().getType() == TokenTransactionType.PURCHASE) {
                        return; // already credited by concurrent request
                    }
                }
                throw ex;
            }
        }
    }

    private String buildMetaJson(String ref, String reason, Long released) {
        try {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            if (ref != null && !ref.isBlank()) m.put("ref", ref);
            if (reason != null && !reason.isBlank()) m.put("reason", reason);
            if (released != null) m.put("released", released);
            if (m.isEmpty()) return null;
            return objectMapper.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
