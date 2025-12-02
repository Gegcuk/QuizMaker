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
import org.springframework.scheduling.annotation.Scheduled;
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
import uk.gegc.quizmaker.features.billing.application.BillingStructuredLogger;
import uk.gegc.quizmaker.features.billing.application.BillingMetricsService;
import uk.gegc.quizmaker.features.billing.domain.exception.InsufficientTokensException;
import uk.gegc.quizmaker.features.billing.domain.exception.InsufficientAvailableTokensException;
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
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.quiz.domain.model.BillingState;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private final QuizGenerationJobRepository quizGenerationJobRepository;

    private final BalanceMapper balanceMapper;
    @Qualifier("tokenTransactionMapperImpl")
    private final TokenTransactionMapper transactionMapper;
    private final ReservationMapper reservationMapper;

    @PersistenceContext
    private EntityManager entityManager;

    private final ObjectMapper objectMapper;
    private final BillingMetricsService metricsService;

    @Override
    @Transactional
    @PreAuthorize("hasAuthority('BILLING_READ')")
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
    @PreAuthorize("hasAuthority('BILLING_READ')")
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
    @PreAuthorize("hasAuthority('BILLING_WRITE')")
    public ReservationDto reserve(UUID userId, long estimatedBillingTokens, String ref, String idempotencyKey) {
        return performReserve(userId, estimatedBillingTokens, ref, idempotencyKey);
    }

    @Transactional
    public ReservationDto reserveInternal(UUID userId, long estimatedBillingTokens, String ref, String idempotencyKey) {
        return performReserve(userId, estimatedBillingTokens, ref, idempotencyKey);
    }

    private ReservationDto performReserve(UUID userId, long estimatedBillingTokens, String ref, String idempotencyKey) {
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
                    // Emit reservation created metrics for idempotent case
                    metricsService.incrementReservationCreated(userId, estimatedBillingTokens);
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
                    long shortfall = estimatedBillingTokens - available;
                    LocalDateTime reservationTtl = LocalDateTime.now().plusMinutes(billingProperties.getReservationTtlMinutes());
                    throw new InsufficientTokensException(
                        "Not enough tokens to reserve: required=" + estimatedBillingTokens + ", available=" + available,
                        estimatedBillingTokens,
                        available,
                        shortfall,
                        reservationTtl
                    );
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

                // Emit reservation created metrics
                metricsService.incrementReservationCreated(userId, estimatedBillingTokens);

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
                            // Emit reservation created metrics for race condition case
                            metricsService.incrementReservationCreated(userId, estimatedBillingTokens);
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
    @PreAuthorize("hasAuthority('BILLING_WRITE')")
    public CommitResultDto commit(UUID reservationId, long actualBillingTokens, String ref, String idempotencyKey) {
        return performCommit(reservationId, actualBillingTokens, ref, idempotencyKey);
    }

    @Transactional
    public CommitResultDto commitInternal(UUID reservationId, long actualBillingTokens, String ref, String idempotencyKey) {
        return performCommit(reservationId, actualBillingTokens, ref, idempotencyKey);
    }

    private CommitResultDto performCommit(UUID reservationId, long actualBillingTokens, String ref, String idempotencyKey) {
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

                // Emit structured logging and metrics for commit
                BillingStructuredLogger.logLedgerWrite(log, "info", 
                        "Committed {} tokens from reservation {} for user {}", 
                        res.getUserId(), "COMMIT", "QUIZ_GENERATION", actualBillingTokens, idempotencyKey, 
                        balance.getAvailableTokens(), balance.getReservedTokens(), reservationId.toString());

                metricsService.incrementTokensCommitted(res.getUserId(), actualBillingTokens, "QUIZ_GENERATION");
                metricsService.incrementReservationCommitted(res.getUserId(), actualBillingTokens);
                metricsService.recordBalanceAvailable(res.getUserId(), balance.getAvailableTokens());
                metricsService.recordBalanceReserved(res.getUserId(), balance.getReservedTokens());

                // Then: if remainder exists, move reserved -> available for the difference and record RELEASE tx with remainder amount
                if (remainder > 0) {
                    balance.setReservedTokens(balance.getReservedTokens() - remainder);
                    balance.setAvailableTokens(balance.getAvailableTokens() + remainder);
                    balanceRepository.save(balance);

                    TokenTransaction releaseTx = new TokenTransaction();
                    releaseTx.setUserId(res.getUserId());
                    releaseTx.setType(TokenTransactionType.RELEASE);
                    releaseTx.setSource(TokenTransactionSource.QUIZ_GENERATION);
                    releaseTx.setAmountTokens(remainder);
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
    @PreAuthorize("hasAuthority('BILLING_WRITE')")
    public ReleaseResultDto release(UUID reservationId, String reason, String ref, String idempotencyKey) {
        return performRelease(reservationId, reason, ref, idempotencyKey);
    }

    @Transactional
    public ReleaseResultDto releaseInternal(UUID reservationId, String reason, String ref, String idempotencyKey) {
        return performRelease(reservationId, reason, ref, idempotencyKey);
    }

    private ReleaseResultDto performRelease(UUID reservationId, String reason, String ref, String idempotencyKey) {
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

                // Emit RELEASE transaction with actual release amount and idempotency
                TokenTransaction tx = new TokenTransaction();
                tx.setUserId(res.getUserId());
                tx.setType(TokenTransactionType.RELEASE);
                tx.setSource(TokenTransactionSource.QUIZ_GENERATION);
                tx.setAmountTokens(releaseAmount); // Use actual release amount instead of 0
                tx.setRefId(reservationId.toString());
                tx.setIdempotencyKey(idempotencyKey);
                tx.setMetaJson(buildMetaJson(ref, reason, null));
                tx.setBalanceAfterAvailable(balance.getAvailableTokens());
                tx.setBalanceAfterReserved(balance.getReservedTokens());
                transactionRepository.save(tx);

                // Emit structured logging and metrics
                BillingStructuredLogger.logLedgerWrite(log, "info", 
                        "Released {} tokens from reservation {} for user {}", 
                        res.getUserId(), "RELEASE", "QUIZ_GENERATION", releaseAmount, idempotencyKey, 
                        balance.getAvailableTokens(), balance.getReservedTokens(), reservationId.toString());

                metricsService.incrementTokensReleased(res.getUserId(), releaseAmount, "QUIZ_GENERATION");
                metricsService.incrementReservationReleased(res.getUserId(), releaseAmount);
                metricsService.recordBalanceAvailable(res.getUserId(), balance.getAvailableTokens());
                metricsService.recordBalanceReserved(res.getUserId(), balance.getReservedTokens());

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

                // Emit structured logging and metrics
                BillingStructuredLogger.logLedgerWrite(log, "info", 
                        "Credited {} tokens to user {} via purchase", 
                        userId, "PURCHASE", "STRIPE", tokens, idempotencyKey, 
                        balance.getAvailableTokens(), balance.getReservedTokens(), ref);
                
                metricsService.incrementTokensPurchased(userId, tokens, "STRIPE");
                metricsService.incrementTokensCredited(userId, tokens, "STRIPE");
                metricsService.recordBalanceAvailable(userId, balance.getAvailableTokens());
                metricsService.recordBalanceReserved(userId, balance.getReservedTokens());

                entityManager.flush();
                return;
            } catch (OptimisticLockingFailureException ex) {
                if (attempts >= 2) {
                    log.warn("creditPurchase() optimistic lock failed after {} attempts for user {}", attempts, userId);
                    throw ex;
                }
                try { Thread.sleep(20L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            } catch (DataIntegrityViolationException ex) {
                // Handle race when two requests insert the same idempotencyKey concurrently
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

    @Override
    @Transactional
    public void creditAdjustment(UUID userId, long tokens, String idempotencyKey, String ref, String metaJson) {
        if (tokens <= 0) {
            throw new IllegalArgumentException("tokens must be > 0");
        }

        // Idempotency: if an ADJUSTMENT with this key already exists, noop
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                var tx = existing.get();
                if (tx.getType() != TokenTransactionType.ADJUSTMENT) {
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

                // Append ADJUSTMENT transaction with snapshots
                TokenTransaction tx = new TokenTransaction();
                tx.setUserId(userId);
                tx.setType(TokenTransactionType.ADJUSTMENT);
                tx.setSource(TokenTransactionSource.ADMIN);
                tx.setAmountTokens(tokens);
                tx.setRefId(ref);
                tx.setIdempotencyKey(idempotencyKey);
                tx.setMetaJson(metaJson);
                tx.setBalanceAfterAvailable(balance.getAvailableTokens());
                tx.setBalanceAfterReserved(balance.getReservedTokens());
                transactionRepository.save(tx);

                // Emit structured logging and metrics
                BillingStructuredLogger.logLedgerWrite(log, "info", 
                        "Credited {} tokens to user {} via adjustment", 
                        userId, "ADJUSTMENT", "ADMIN", tokens, idempotencyKey, 
                        balance.getAvailableTokens(), balance.getReservedTokens(), ref);
                
                metricsService.incrementTokensAdjusted(userId, tokens, "ADMIN");
                metricsService.incrementTokensCredited(userId, tokens, "ADMIN");
                metricsService.recordBalanceAvailable(userId, balance.getAvailableTokens());
                metricsService.recordBalanceReserved(userId, balance.getReservedTokens());

                entityManager.flush();
                return;
            } catch (OptimisticLockingFailureException ex) {
                if (attempts >= 2) {
                    log.warn("creditAdjustment() optimistic lock failed after {} attempts for user {}", attempts, userId);
                    throw ex;
                }
                try { Thread.sleep(20L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            } catch (DataIntegrityViolationException ex) {
                // Handle race when two requests insert the same idempotencyKey concurrently
                if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                    var existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
                    if (existing.isPresent() && existing.get().getType() == TokenTransactionType.ADJUSTMENT) {
                        return; // already credited by concurrent request
                    }
                }
                throw ex;
            }
        }
    }

    @Override
    @Scheduled(fixedDelayString = "#{@billingProperties.reservationSweeperMs}")
    @Transactional
    public void expireReservations() {
        var cutoff = LocalDateTime.now();
        var expired = reservationRepository.findByStateAndExpiresAtBefore(ReservationState.ACTIVE, cutoff);
        
        // Always record backlog metric, even when empty
        metricsService.recordSweeperBacklog(expired.size());
        
        if (expired.isEmpty()) {
            return; // No expired reservations to process
        }
        
        log.info("Processing {} expired reservations", expired.size());
        
        for (var res : expired) {
            try {
                // Move reserved → available
                Balance balance = balanceRepository.findByUserId(res.getUserId())
                        .orElseThrow(() -> new IllegalStateException("Balance not found for user " + res.getUserId()));
                
                long releaseAmount = res.getEstimatedTokens();
                
                // Move reserved → available
                balance.setReservedTokens(balance.getReservedTokens() - releaseAmount);
                balance.setAvailableTokens(balance.getAvailableTokens() + releaseAmount);
                balanceRepository.save(balance);
                
                // Update reservation state
                res.setState(ReservationState.RELEASED);
                reservationRepository.save(res);
                
                // Write RELEASE transaction with actual release amount
                TokenTransaction tx = new TokenTransaction();
                tx.setUserId(res.getUserId());
                tx.setType(TokenTransactionType.RELEASE);
                tx.setSource(TokenTransactionSource.QUIZ_GENERATION);
                tx.setAmountTokens(releaseAmount); // Use actual release amount instead of 0
                tx.setRefId(res.getId().toString());
                tx.setIdempotencyKey(null); // No idempotency for expired reservations
                tx.setMetaJson(buildMetaJson(null, "expired", releaseAmount));
                tx.setBalanceAfterAvailable(balance.getAvailableTokens());
                tx.setBalanceAfterReserved(balance.getReservedTokens());
                transactionRepository.save(tx);

                // Update job billing state if this reservation is linked to a quiz generation job
                try {
                    quizGenerationJobRepository.findByBillingReservationId(res.getId())
                            .ifPresent(job -> {
                                if (job.getBillingState() == BillingState.RESERVED) {
                                    job.setBillingState(BillingState.RELEASED);
                                    job.setLastBillingError("{\"reason\":\"Reservation expired\",\"expiredAt\":\"" + res.getExpiresAt() + "\"}");
                                    // Store release idempotency key for audit trail (sweeper uses null key since it's automatic)
                                    String releaseIdempotencyKey = "quiz:" + job.getId() + ":release";
                                    job.addBillingIdempotencyKey("release", releaseIdempotencyKey);
                                    quizGenerationJobRepository.save(job);
                                }
                            });
                } catch (Exception jobUpdateError) {
                    log.warn("Failed to update job billing state for expired reservation {}: {}", res.getId(), jobUpdateError.getMessage());
                }

                // Emit structured logging and metrics
                BillingStructuredLogger.logLedgerWrite(log, "info", 
                        "Expired reservation {} for user {}, released {} tokens", 
                        res.getUserId(), "RELEASE", "QUIZ_GENERATION", releaseAmount, null, 
                        balance.getAvailableTokens(), balance.getReservedTokens(), res.getId().toString());
                
                metricsService.incrementTokensReleased(res.getUserId(), releaseAmount, "QUIZ_GENERATION");
                metricsService.incrementReservationReleased(res.getUserId(), releaseAmount);
                metricsService.recordBalanceAvailable(res.getUserId(), balance.getAvailableTokens());
                metricsService.recordBalanceReserved(res.getUserId(), balance.getReservedTokens());

            } catch (Exception e) {
                log.error("Failed to expire reservation {} for user {}: {}", 
                        res.getId(), res.getUserId(), e.getMessage(), e);
                // Continue processing other reservations even if one fails
            }
        }
        
        log.info("Completed processing {} expired reservations", expired.size());
    }

    @Override
    @Transactional
    public void deductTokens(UUID userId, long tokens, String idempotencyKey, String ref, String metaJson) {
        if (tokens <= 0) {
            throw new IllegalArgumentException("tokens must be > 0");
        }

        // Idempotency: if a REFUND with this key already exists, noop
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                var tx = existing.get();
                if (tx.getType() != TokenTransactionType.REFUND) {
                    throw new IdempotencyConflictException("Idempotency key already used for a different operation");
                }
                return; // already deducted
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

                long available = balance.getAvailableTokens();
                
                // Check if user has enough tokens to deduct
                if (available < tokens) {
                    if (!billingProperties.isAllowNegativeBalance()) {
                        // Negative balances not allowed - throw exception
                        long shortfall = tokens - available;
                        throw new InsufficientAvailableTokensException(
                            String.format("Insufficient available tokens for refund/adjustment. Requested: %d, Available: %d, Shortfall: %d", 
                                tokens, available, shortfall),
                            tokens, available, shortfall);
                    } else {
                        // Allow negative balance but log a warning
                        log.warn("Deducting {} tokens from user {} with only {} available (will result in negative balance)", 
                                tokens, userId, available);
                    }
                }

                // Deduct tokens from available
                balance.setAvailableTokens(available - tokens);
                balanceRepository.save(balance);

                // Append REFUND transaction with snapshots
                TokenTransaction tx = new TokenTransaction();
                tx.setUserId(userId);
                tx.setType(TokenTransactionType.REFUND);
                tx.setSource(TokenTransactionSource.STRIPE);
                tx.setAmountTokens(-tokens); // Negative amount for deduction
                tx.setRefId(ref);
                tx.setIdempotencyKey(idempotencyKey);
                tx.setMetaJson(metaJson);
                tx.setBalanceAfterAvailable(balance.getAvailableTokens());
                tx.setBalanceAfterReserved(balance.getReservedTokens());
                transactionRepository.save(tx);

                // Emit structured logging and metrics
                BillingStructuredLogger.logLedgerWrite(log, "info", 
                        "Deducted {} tokens from user {} via refund", 
                        userId, "REFUND", "STRIPE", tokens, idempotencyKey, 
                        balance.getAvailableTokens(), balance.getReservedTokens(), ref);
                
                metricsService.incrementTokensAdjusted(userId, tokens, "STRIPE");
                metricsService.recordBalanceAvailable(userId, balance.getAvailableTokens());
                metricsService.recordBalanceReserved(userId, balance.getReservedTokens());
                
                // Record negative balance alert if applicable
                if (balance.getAvailableTokens() < 0) {
                    metricsService.recordNegativeBalanceAlert(userId, Math.abs(balance.getAvailableTokens()));
                }

                entityManager.flush();
                return;
            } catch (OptimisticLockingFailureException ex) {
                if (attempts >= 2) {
                    log.warn("deductTokens() optimistic lock failed after {} attempts for user {}", attempts, userId);
                    throw ex;
                }
                try { Thread.sleep(20L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            } catch (DataIntegrityViolationException ex) {
                // handle race on idempotency key
                if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                    var existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
                    if (existing.isPresent() && existing.get().getType() == TokenTransactionType.REFUND) {
                        return; // already deducted by concurrent request
                    }
                }
                throw ex;
            }
        }
    }

    private String buildMetaJson(String ref, String reason, Long released) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
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
