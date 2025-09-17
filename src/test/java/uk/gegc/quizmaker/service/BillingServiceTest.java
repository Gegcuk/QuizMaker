package uk.gegc.quizmaker.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import uk.gegc.quizmaker.features.billing.api.dto.BalanceDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import uk.gegc.quizmaker.features.billing.api.dto.ReservationDto;
import uk.gegc.quizmaker.features.billing.api.dto.TransactionDto;
import uk.gegc.quizmaker.features.billing.api.dto.CommitResultDto;
import uk.gegc.quizmaker.features.billing.api.dto.ReleaseResultDto;
import uk.gegc.quizmaker.features.billing.application.BillingProperties;
import uk.gegc.quizmaker.features.billing.application.BillingMetricsService;
import uk.gegc.quizmaker.features.billing.application.impl.BillingServiceImpl;
import uk.gegc.quizmaker.features.billing.domain.exception.InsufficientTokensException;
import uk.gegc.quizmaker.features.billing.domain.exception.InsufficientAvailableTokensException;
import uk.gegc.quizmaker.features.billing.domain.model.*;
import uk.gegc.quizmaker.features.billing.infra.mapping.BalanceMapper;
import uk.gegc.quizmaker.features.billing.infra.mapping.ReservationMapper;
import uk.gegc.quizmaker.features.billing.infra.mapping.TokenTransactionMapper;
import uk.gegc.quizmaker.features.billing.infra.repository.BalanceRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.ReservationRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.TokenTransactionRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BillingServiceTest {

    private BalanceRepository balanceRepository;
    private TokenTransactionRepository transactionRepository;
    private ReservationRepository reservationRepository;
    private BillingProperties billingProperties;
    private BillingMetricsService metricsService;

    // use real mappers via MapStruct generated impls is complex here; mock mapping
    private BalanceMapper balanceMapper;
    private TokenTransactionMapper txMapper;
    private ReservationMapper reservationMapper;

    private BillingServiceImpl service;

    @BeforeEach
    void setup() {
        balanceRepository = mock(BalanceRepository.class);
        transactionRepository = mock(TokenTransactionRepository.class);
        reservationRepository = mock(ReservationRepository.class);
        billingProperties = new BillingProperties();
        balanceMapper = mock(BalanceMapper.class);
        txMapper = mock(TokenTransactionMapper.class);
        reservationMapper = mock(ReservationMapper.class);
        metricsService = mock(BillingMetricsService.class);

        service = new BillingServiceImpl(
                billingProperties,
                balanceRepository,
                transactionRepository,
                reservationRepository,
                mock(QuizGenerationJobRepository.class),
                balanceMapper,
                txMapper,
                reservationMapper,
                new ObjectMapper(),
                metricsService
        );
        // Inject a mock EntityManager to avoid NPE on flush() in service
        var mockEm = mock(jakarta.persistence.EntityManager.class);
        ReflectionTestUtils.setField(service, "entityManager", mockEm);
    }

    @Test
    void getBalance_createsZeroedIfMissing() {
        UUID userId = UUID.randomUUID();
        when(balanceRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(balanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BalanceDto dto = new BalanceDto(userId, 0, 0, null);
        when(balanceMapper.toDto(any())).thenReturn(dto);

        BalanceDto out = service.getBalance(userId);
        assertEquals(userId, out.userId());
        assertEquals(0, out.availableTokens());
        assertEquals(0, out.reservedTokens());

        verify(balanceRepository, times(1)).save(any(Balance.class));
        verify(balanceMapper).toDto(any(Balance.class));
    }

    @Test
    void reserve_throwsWhenInsufficientTokens() {
        UUID userId = UUID.randomUUID();
        Balance b = new Balance();
        b.setUserId(userId);
        b.setAvailableTokens(10);
        b.setReservedTokens(0);
        when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(b));

        assertThrows(InsufficientTokensException.class, () ->
                service.reserve(userId, 20, "ref", "idemp-x")
        );

        verify(reservationRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void commit_activeReservation_successMovesBalancesAndRecordsCommit() {
        UUID userId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        // Balance before: available=0, reserved=100
        Balance balance = new Balance();
        balance.setUserId(userId);
        balance.setAvailableTokens(0);
        balance.setReservedTokens(100);

        Reservation res = new Reservation();
        res.setId(reservationId);
        res.setUserId(userId);
        res.setEstimatedTokens(100);
        res.setCommittedTokens(0);
        res.setState(ReservationState.ACTIVE);

        when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE)).thenReturn(Optional.of(res));
        when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));

        CommitResultDto out = service.commit(reservationId, 70, "job-1", "idemp-1");

        assertEquals(reservationId, out.reservationId());
        assertEquals(70, out.committedTokens());
        assertEquals(30, out.releasedTokens());

        // After commit + release remainder: reserved 100 - 70 - 30 = 0; available 0 + 30 = 30
        assertEquals(30, balance.getAvailableTokens());
        assertEquals(0, balance.getReservedTokens());

        ArgumentCaptor<TokenTransaction> cap = ArgumentCaptor.forClass(TokenTransaction.class);
        verify(transactionRepository, atLeast(1)).save(cap.capture());
        var saved = cap.getAllValues();
        assertTrue(saved.stream().anyMatch(t -> t.getType() == TokenTransactionType.COMMIT));
        assertTrue(saved.stream().anyMatch(t -> t.getType() == TokenTransactionType.RELEASE));
    }

    @Test
    void release_activeReservation_movesAllReservedToAvailable() {
        UUID userId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        Balance balance = new Balance();
        balance.setUserId(userId);
        balance.setAvailableTokens(0);
        balance.setReservedTokens(50);

        Reservation res = new Reservation();
        res.setId(reservationId);
        res.setUserId(userId);
        res.setEstimatedTokens(50);
        res.setCommittedTokens(0);
        res.setState(ReservationState.ACTIVE);

        when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE)).thenReturn(Optional.of(res));
        when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));

        ReleaseResultDto out = service.release(reservationId, "cancelled", "job-1", "idemp-2");

        assertEquals(reservationId, out.reservationId());
        assertEquals(50, out.releasedTokens());
        assertEquals(50, balance.getAvailableTokens());
        assertEquals(0, balance.getReservedTokens());
        verify(transactionRepository, atLeastOnce()).save(any(TokenTransaction.class));
    }

    @Test
    void reserve_successCreatesReservationAndTx_withIdempotency() {
        UUID userId = UUID.randomUUID();
        Balance b = new Balance();
        b.setUserId(userId);
        b.setAvailableTokens(100);
        b.setReservedTokens(0);
        when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(b));

        UUID resId = UUID.randomUUID();
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            r.setId(resId);
            return r;
        });

        ReservationDto expected = new ReservationDto(resId, userId, ReservationState.ACTIVE, 40, 0, null, null, null, null);
        when(reservationMapper.toDto(any(Reservation.class))).thenReturn(expected);

        ArgumentCaptor<TokenTransaction> txCap = ArgumentCaptor.forClass(TokenTransaction.class);

        ReservationDto out = service.reserve(userId, 40, "job-ref", "idem-123");

        assertEquals(expected, out);
        assertEquals(60, b.getAvailableTokens());
        assertEquals(40, b.getReservedTokens());

        verify(transactionRepository).save(txCap.capture());
        TokenTransaction tx = txCap.getValue();
        assertEquals(TokenTransactionType.RESERVE, tx.getType());
        assertEquals("idem-123", tx.getIdempotencyKey());
        assertEquals(resId.toString(), tx.getRefId());
    }

    @Test
    void reserve_idempotentReturnsExisting_whenSameKey() {
        UUID userId = UUID.randomUUID();
        UUID resId = UUID.randomUUID();
        TokenTransaction existingTx = new TokenTransaction();
        existingTx.setType(TokenTransactionType.RESERVE);
        existingTx.setRefId(resId.toString());
        when(transactionRepository.findByIdempotencyKey("idem-k"))
                .thenReturn(Optional.of(existingTx));

        Reservation res = new Reservation();
        res.setId(resId);
        res.setUserId(userId);
        res.setEstimatedTokens(25);
        res.setState(ReservationState.ACTIVE);
        when(reservationRepository.findById(resId)).thenReturn(Optional.of(res));

        ReservationDto dto = new ReservationDto(resId, userId, ReservationState.ACTIVE, 25, 0, null, null, null, null);
        when(reservationMapper.toDto(res)).thenReturn(dto);

        ReservationDto out = service.reserve(userId, 25, "job-ref", "idem-k");
        assertEquals(dto, out);
        verify(balanceRepository, never()).save(any());
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void reserve_idempotentConflict_whenDifferentAmount() {
        UUID userId = UUID.randomUUID();
        UUID resId = UUID.randomUUID();
        TokenTransaction existingTx = new TokenTransaction();
        existingTx.setType(TokenTransactionType.RESERVE);
        existingTx.setRefId(resId.toString());
        when(transactionRepository.findByIdempotencyKey("idem-k2"))
                .thenReturn(Optional.of(existingTx));

        Reservation res = new Reservation();
        res.setId(resId);
        res.setUserId(userId);
        res.setEstimatedTokens(30);
        res.setState(ReservationState.ACTIVE);
        when(reservationRepository.findById(resId)).thenReturn(Optional.of(res));

        assertThrows(uk.gegc.quizmaker.features.billing.domain.exception.IdempotencyConflictException.class,
                () -> service.reserve(userId, 40, "job-ref", "idem-k2"));
    }

    @Test
    @DisplayName("reserve: emits reservation created metrics when reservation is successfully created")
    void reserve_emitsReservationCreatedMetrics() {
        // Given
        UUID userId = UUID.randomUUID();
        long estimatedBillingTokens = 50L;
        Balance balance = new Balance();
        balance.setUserId(userId);
        balance.setAvailableTokens(100L);
        balance.setReservedTokens(0L);

        when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
        when(transactionRepository.findByIdempotencyKey("idemp-metrics")).thenReturn(Optional.empty());

        UUID resId = UUID.randomUUID();
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            r.setId(resId);
            return r;
        });

        ReservationDto expected = new ReservationDto(resId, userId, ReservationState.ACTIVE, estimatedBillingTokens, 0, null, null, null, null);
        when(reservationMapper.toDto(any(Reservation.class))).thenReturn(expected);

        // When
        ReservationDto result = service.reserve(userId, estimatedBillingTokens, "job-ref", "idemp-metrics");

        // Then
        assertEquals(expected, result);
        verify(metricsService).incrementReservationCreated(userId, estimatedBillingTokens);
    }

    @Test
    @DisplayName("reserve: emits reservation created metrics for idempotent case")
    void reserve_emitsReservationCreatedMetricsForIdempotentCase() {
        // Given
        UUID userId = UUID.randomUUID();
        long estimatedBillingTokens = 30L;
        UUID resId = UUID.randomUUID();
        
        TokenTransaction existingTx = new TokenTransaction();
        existingTx.setType(TokenTransactionType.RESERVE);
        existingTx.setRefId(resId.toString());
        when(transactionRepository.findByIdempotencyKey("idemp-existing")).thenReturn(Optional.of(existingTx));

        Reservation res = new Reservation();
        res.setId(resId);
        res.setUserId(userId);
        res.setEstimatedTokens(estimatedBillingTokens);
        res.setState(ReservationState.ACTIVE);
        when(reservationRepository.findById(resId)).thenReturn(Optional.of(res));

        ReservationDto dto = new ReservationDto(resId, userId, ReservationState.ACTIVE, estimatedBillingTokens, 0, null, null, null, null);
        when(reservationMapper.toDto(res)).thenReturn(dto);

        // When
        ReservationDto result = service.reserve(userId, estimatedBillingTokens, "job-ref", "idemp-existing");

        // Then
        assertEquals(dto, result);
        verify(metricsService).incrementReservationCreated(userId, estimatedBillingTokens);
    }

    @Test
    void listTransactions_mapsToDtos() {
        UUID userId = UUID.randomUUID();
        TokenTransaction tx = new TokenTransaction();
        tx.setUserId(userId);
        Page<TokenTransaction> page = new PageImpl<>(List.of(tx));
        when(transactionRepository.findByFilters(eq(userId), isNull(), isNull(), isNull(), isNull(), any()))
                .thenReturn(page);
        TransactionDto mapped = new TransactionDto(null, userId, null, null, 0, null, null, null, null, null, null);
        when(txMapper.toDto(tx)).thenReturn(mapped);

        Page<?> out = service.listTransactions(userId, PageRequest.of(0, 10), null, null, null, null);
        assertEquals(1, out.getTotalElements());
        assertEquals(userId, ((TransactionDto) out.getContent().get(0)).userId());
    }

    @Test
    void commit_idempotentReturn_whenSameKey() {
        UUID resId = UUID.randomUUID();
        TokenTransaction existing = new TokenTransaction();
        existing.setType(TokenTransactionType.COMMIT);
        existing.setRefId(resId.toString());
        when(transactionRepository.findByIdempotencyKey("idemp-c1")).thenReturn(Optional.of(existing));

        Reservation res = new Reservation();
        res.setId(resId);
        res.setEstimatedTokens(100);
        res.setCommittedTokens(70);
        when(reservationRepository.findById(resId)).thenReturn(Optional.of(res));

        CommitResultDto out = service.commit(resId, 10, "ref", "idemp-c1");
        assertEquals(70, out.committedTokens());
        assertEquals(30, out.releasedTokens());
    }

    @Test
    void commit_overageThrows() {
        UUID userId = UUID.randomUUID();
        UUID resId = UUID.randomUUID();
        Reservation res = new Reservation();
        res.setId(resId);
        res.setUserId(userId);
        res.setEstimatedTokens(20);
        res.setState(ReservationState.ACTIVE);
        when(reservationRepository.findByIdAndState(resId, ReservationState.ACTIVE)).thenReturn(Optional.of(res));

        assertThrows(uk.gegc.quizmaker.features.billing.domain.exception.CommitExceedsReservedException.class,
                () -> service.commit(resId, 30, "ref", "idemp-c2"));
    }

    @Test
    void commit_notActiveThrows() {
        UUID resId = UUID.randomUUID();
        when(reservationRepository.findByIdAndState(resId, ReservationState.ACTIVE)).thenReturn(Optional.empty());
        assertThrows(uk.gegc.quizmaker.features.billing.domain.exception.ReservationNotActiveException.class,
                () -> service.commit(resId, 10, "ref", "idemp-c3"));
    }

    @Test
    void commit_idempotencyConflict_whenKeyUsedForDifferentReservation() {
        UUID resId = UUID.randomUUID();
        TokenTransaction existing = new TokenTransaction();
        existing.setType(TokenTransactionType.COMMIT);
        existing.setRefId(UUID.randomUUID().toString()); // different reservation
        when(transactionRepository.findByIdempotencyKey("idemp-c4")).thenReturn(Optional.of(existing));

        assertThrows(uk.gegc.quizmaker.features.billing.domain.exception.IdempotencyConflictException.class,
                () -> service.commit(resId, 10, "ref", "idemp-c4"));
    }

    @Test
    void release_idempotentReturn_whenSameKey() {
        UUID resId = UUID.randomUUID();
        TokenTransaction existing = new TokenTransaction();
        existing.setType(TokenTransactionType.RELEASE);
        existing.setRefId(resId.toString());
        when(transactionRepository.findByIdempotencyKey("idemp-r1")).thenReturn(Optional.of(existing));

        Reservation res = new Reservation();
        res.setId(resId);
        res.setEstimatedTokens(50);
        res.setCommittedTokens(0);
        res.setState(ReservationState.RELEASED);
        when(reservationRepository.findById(resId)).thenReturn(Optional.of(res));

        ReleaseResultDto out = service.release(resId, "reason", "ref", "idemp-r1");
        assertEquals(50, out.releasedTokens());
    }

    @Test
    void release_notActiveThrows() {
        UUID resId = UUID.randomUUID();
        when(reservationRepository.findByIdAndState(resId, ReservationState.ACTIVE)).thenReturn(Optional.empty());
        assertThrows(uk.gegc.quizmaker.features.billing.domain.exception.ReservationNotActiveException.class,
                () -> service.release(resId, "reason", "ref", "idemp-r2"));
    }

    @Test
    void release_idempotencyConflict_whenKeyUsedForDifferentReservation() {
        UUID resId = UUID.randomUUID();
        TokenTransaction existing = new TokenTransaction();
        existing.setType(TokenTransactionType.RELEASE);
        existing.setRefId(UUID.randomUUID().toString());
        when(transactionRepository.findByIdempotencyKey("idemp-r3")).thenReturn(Optional.of(existing));

        assertThrows(uk.gegc.quizmaker.features.billing.domain.exception.IdempotencyConflictException.class,
                () -> service.release(resId, "reason", "ref", "idemp-r3"));
    }

    @Test
    @DisplayName("deductTokens: when allowNegativeBalance is false and insufficient tokens then throw InsufficientAvailableTokensException")
    void deductTokens_whenAllowNegativeBalanceFalseAndInsufficientTokens_thenThrowException() {
        // Given
        UUID userId = UUID.randomUUID();
        long requestedTokens = 1000L;
        long availableTokens = 500L;
        String idempotencyKey = "test-key";
        String ref = "test-ref";
        String metaJson = "{}";

        billingProperties.setAllowNegativeBalance(false);

        Balance balance = new Balance();
        balance.setUserId(userId);
        balance.setAvailableTokens(availableTokens);
        balance.setReservedTokens(0L);

        when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
        when(transactionRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());

        // When & Then
        InsufficientAvailableTokensException exception = assertThrows(
            InsufficientAvailableTokensException.class,
            () -> service.deductTokens(userId, requestedTokens, idempotencyKey, ref, metaJson)
        );

        assertEquals(requestedTokens, exception.getRequestedTokens());
        assertEquals(availableTokens, exception.getAvailableTokens());
        assertEquals(500L, exception.getShortfall());
        assertTrue(exception.getMessage().contains("Insufficient available tokens for refund/adjustment"));
        assertTrue(exception.getMessage().contains("Requested: 1000"));
        assertTrue(exception.getMessage().contains("Available: 500"));
        assertTrue(exception.getMessage().contains("Shortfall: 500"));

        // Verify balance was not modified
        verify(balanceRepository, never()).save(any(Balance.class));
        verify(transactionRepository, never()).save(any(TokenTransaction.class));
    }

    @Test
    @DisplayName("deductTokens: when allowNegativeBalance is true and insufficient tokens then allow negative balance")
    void deductTokens_whenAllowNegativeBalanceTrueAndInsufficientTokens_thenAllowNegativeBalance() {
        // Given
        UUID userId = UUID.randomUUID();
        long requestedTokens = 1000L;
        long availableTokens = 500L;
        String idempotencyKey = "test-key";
        String ref = "test-ref";
        String metaJson = "{}";

        billingProperties.setAllowNegativeBalance(true);

        Balance balance = new Balance();
        balance.setUserId(userId);
        balance.setAvailableTokens(availableTokens);
        balance.setReservedTokens(0L);

        when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
        when(transactionRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());

        // When
        service.deductTokens(userId, requestedTokens, idempotencyKey, ref, metaJson);

        // Then
        ArgumentCaptor<Balance> balanceCaptor = ArgumentCaptor.forClass(Balance.class);
        verify(balanceRepository).save(balanceCaptor.capture());
        
        Balance savedBalance = balanceCaptor.getValue();
        assertEquals(-500L, savedBalance.getAvailableTokens()); // 500 - 1000 = -500

        ArgumentCaptor<TokenTransaction> transactionCaptor = ArgumentCaptor.forClass(TokenTransaction.class);
        verify(transactionRepository).save(transactionCaptor.capture());
        
        TokenTransaction savedTransaction = transactionCaptor.getValue();
        assertEquals(TokenTransactionType.REFUND, savedTransaction.getType());
        assertEquals(-requestedTokens, savedTransaction.getAmountTokens());
        assertEquals(-500L, savedTransaction.getBalanceAfterAvailable());
    }

    @Test
    @DisplayName("deductTokens: when allowNegativeBalance is false and sufficient tokens then deduct normally")
    void deductTokens_whenAllowNegativeBalanceFalseAndSufficientTokens_thenDeductNormally() {
        // Given
        UUID userId = UUID.randomUUID();
        long requestedTokens = 500L;
        long availableTokens = 1000L;
        String idempotencyKey = "test-key";
        String ref = "test-ref";
        String metaJson = "{}";

        billingProperties.setAllowNegativeBalance(false);

        Balance balance = new Balance();
        balance.setUserId(userId);
        balance.setAvailableTokens(availableTokens);
        balance.setReservedTokens(0L);

        when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
        when(transactionRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());

        // When
        service.deductTokens(userId, requestedTokens, idempotencyKey, ref, metaJson);

        // Then
        ArgumentCaptor<Balance> balanceCaptor = ArgumentCaptor.forClass(Balance.class);
        verify(balanceRepository).save(balanceCaptor.capture());
        
        Balance savedBalance = balanceCaptor.getValue();
        assertEquals(500L, savedBalance.getAvailableTokens()); // 1000 - 500 = 500

        ArgumentCaptor<TokenTransaction> transactionCaptor = ArgumentCaptor.forClass(TokenTransaction.class);
        verify(transactionRepository).save(transactionCaptor.capture());
        
        TokenTransaction savedTransaction = transactionCaptor.getValue();
        assertEquals(TokenTransactionType.REFUND, savedTransaction.getType());
        assertEquals(-requestedTokens, savedTransaction.getAmountTokens());
        assertEquals(500L, savedTransaction.getBalanceAfterAvailable());
    }

    @Test
    @DisplayName("commit: when remainder exists then RELEASE transaction records correct remainder amount")
    void commit_whenRemainderExists_thenReleaseTransactionRecordsCorrectRemainderAmount() {
        // Given
        UUID userId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        long estimatedTokens = 100L;
        long actualBillingTokens = 70L;
        long expectedRemainder = 30L; // 100 - 70 = 30

        Balance balance = new Balance();
        balance.setUserId(userId);
        balance.setAvailableTokens(0L);
        balance.setReservedTokens(estimatedTokens);

        Reservation res = new Reservation();
        res.setId(reservationId);
        res.setUserId(userId);
        res.setEstimatedTokens(estimatedTokens);
        res.setCommittedTokens(0L);
        res.setState(ReservationState.ACTIVE);

        when(reservationRepository.findByIdAndState(reservationId, ReservationState.ACTIVE)).thenReturn(Optional.of(res));
        when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
        when(transactionRepository.findByIdempotencyKey("idemp-commit")).thenReturn(Optional.empty());

        // When
        CommitResultDto result = service.commit(reservationId, actualBillingTokens, "job-1", "idemp-commit");

        // Then
        assertEquals(reservationId, result.reservationId());
        assertEquals(actualBillingTokens, result.committedTokens());
        assertEquals(expectedRemainder, result.releasedTokens());

        // Verify two transactions were saved: COMMIT and RELEASE
        ArgumentCaptor<TokenTransaction> transactionCaptor = ArgumentCaptor.forClass(TokenTransaction.class);
        verify(transactionRepository, times(2)).save(transactionCaptor.capture());
        
        List<TokenTransaction> savedTransactions = transactionCaptor.getAllValues();
        
        // First transaction should be COMMIT
        TokenTransaction commitTx = savedTransactions.get(0);
        assertEquals(TokenTransactionType.COMMIT, commitTx.getType());
        assertEquals(actualBillingTokens, commitTx.getAmountTokens());
        
        // Second transaction should be RELEASE with remainder amount
        TokenTransaction releaseTx = savedTransactions.get(1);
        assertEquals(TokenTransactionType.RELEASE, releaseTx.getType());
        assertEquals(expectedRemainder, releaseTx.getAmountTokens()); // This was the bug - was 0L, now remainder
        assertEquals(reservationId.toString(), releaseTx.getRefId());
        assertEquals(TokenTransactionSource.QUIZ_GENERATION, releaseTx.getSource());
    }
}
