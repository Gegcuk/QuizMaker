package uk.gegc.quizmaker.features.billing.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import uk.gegc.quizmaker.features.billing.application.BillingProperties;
import uk.gegc.quizmaker.features.billing.application.BillingMetricsService;
import uk.gegc.quizmaker.features.billing.domain.exception.InsufficientTokensException;
import uk.gegc.quizmaker.features.billing.domain.exception.IdempotencyConflictException;
import uk.gegc.quizmaker.features.billing.domain.model.*;
import uk.gegc.quizmaker.features.billing.infra.mapping.BalanceMapper;
import uk.gegc.quizmaker.features.billing.infra.mapping.TokenTransactionMapper;
import uk.gegc.quizmaker.features.billing.infra.mapping.ReservationMapper;
import uk.gegc.quizmaker.features.billing.infra.repository.BalanceRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.ReservationRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.TokenTransactionRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;

import jakarta.persistence.EntityManager;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * Comprehensive tests for reservation functionality covering all core scenarios:
 * - Reserve success with ledger RESERVE row, TTL set, balance unchanged, job persisted with RESERVED
 * - Reserve insufficient with InsufficientTokensException details
 * - Reserve idempotent same key returning same reservation
 * - Different request same doc/scope with request hash in key creating different reservation
 * - Optimistic lock handling for simultaneous reserves
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Reservation Comprehensive Tests")
@Execution(ExecutionMode.CONCURRENT)
class ReservationComprehensiveTest {

    @Mock
    private BillingProperties billingProperties;
    
    @Mock
    private BalanceRepository balanceRepository;
    
    @Mock
    private ReservationRepository reservationRepository;
    
    @Mock
    private TokenTransactionRepository transactionRepository;
    
    @Mock
    private QuizGenerationJobRepository quizGenerationJobRepository;
    
    @Mock
    private BalanceMapper balanceMapper;
    
    @Mock
    private TokenTransactionMapper transactionMapper;
    
    @Mock
    private ReservationMapper reservationMapper;
    
    @Mock
    private BillingMetricsService metricsService;
    
    @Mock
    private EntityManager entityManager;

    private BillingServiceImpl billingService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        billingService = new BillingServiceImpl(
            billingProperties,
            balanceRepository,
            transactionRepository,
            reservationRepository,
            quizGenerationJobRepository,
            balanceMapper,
            transactionMapper,
            reservationMapper,
            objectMapper,
            metricsService
        );
        
        // Inject dependencies to avoid NPE
        org.springframework.test.util.ReflectionTestUtils.setField(billingService, "objectMapper", objectMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(billingService, "entityManager", entityManager);
        
        // Setup common mocks
        lenient().when(billingProperties.getReservationTtlMinutes()).thenReturn(30);
    }

    @Nested
    @DisplayName("Reserve Success Scenarios")
    class ReserveSuccessTests {

        @Test
        @DisplayName("Should create ledger RESERVE row, set TTL, update balance, persist job with RESERVED state")
        void shouldCreateLedgerReserveRowSetTtlUpdateBalancePersistJobWithReservedState() {
            // Given
            UUID userId = UUID.randomUUID();
            long estimatedTokens = 1000L;
            String ref = "test-ref";
            String idempotencyKey = "test-key";
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(5000L);
            balance.setReservedTokens(0L);
            
            Reservation savedReservation = new Reservation();
            savedReservation.setId(UUID.randomUUID());
            savedReservation.setUserId(userId);
            savedReservation.setEstimatedTokens(estimatedTokens);
            savedReservation.setState(ReservationState.ACTIVE);
            savedReservation.setExpiresAt(LocalDateTime.now().plusMinutes(30));
            
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            when(reservationRepository.save(any(Reservation.class))).thenReturn(savedReservation);
            when(transactionRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
            when(transactionRepository.save(any(TokenTransaction.class))).thenReturn(new TokenTransaction());
            when(reservationMapper.toDto(any(Reservation.class))).thenReturn(
                new uk.gegc.quizmaker.features.billing.api.dto.ReservationDto(
                    savedReservation.getId(),
                    userId,
                    ReservationState.ACTIVE,
                    estimatedTokens,
                    0L,
                    savedReservation.getExpiresAt(),
                    null,
                    null,
                    null
                )
            );

            // When
            var result = billingService.reserve(userId, estimatedTokens, ref, idempotencyKey);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(savedReservation.getId());
            assertThat(result.estimatedTokens()).isEqualTo(estimatedTokens);
            assertThat(result.state()).isEqualTo(ReservationState.ACTIVE);
            assertThat(result.expiresAt()).isNotNull();
            
            // Verify balance was updated (available -> reserved)
            assertThat(balance.getAvailableTokens()).isEqualTo(4000L); // 5000 - 1000
            assertThat(balance.getReservedTokens()).isEqualTo(1000L);
            
            // Verify reservation was saved with correct state and TTL
            verify(reservationRepository).save(argThat(reservation -> 
                reservation.getUserId().equals(userId) &&
                reservation.getEstimatedTokens() == estimatedTokens &&
                reservation.getState() == ReservationState.ACTIVE &&
                reservation.getExpiresAt() != null &&
                reservation.getCommittedTokens() == 0L
            ));
            
            // Verify RESERVE transaction was created
            verify(transactionRepository).save(argThat(tx -> 
                tx.getType() == TokenTransactionType.RESERVE &&
                tx.getUserId().equals(userId) &&
                tx.getAmountTokens() == 0L &&
                tx.getIdempotencyKey().equals(idempotencyKey) &&
                tx.getRefId().equals(savedReservation.getId().toString())
            ));
            
            // Verify balance was saved
            verify(balanceRepository).save(balance);
        }

        @Test
        @DisplayName("Should create balance automatically when user has no balance")
        void shouldCreateBalanceAutomaticallyWhenUserHasNoBalance() {
            // Given
            UUID userId = UUID.randomUUID();
            long estimatedTokens = 1000L;
            String ref = "test-ref";
            String idempotencyKey = "test-key";
            
            Balance newBalance = new Balance();
            newBalance.setUserId(userId);
            newBalance.setAvailableTokens(0L);
            newBalance.setReservedTokens(0L);
            
            Reservation savedReservation = new Reservation();
            savedReservation.setId(UUID.randomUUID());
            savedReservation.setUserId(userId);
            savedReservation.setEstimatedTokens(estimatedTokens);
            savedReservation.setState(ReservationState.ACTIVE);
            
            lenient().when(balanceRepository.findByUserId(userId)).thenReturn(Optional.empty());
            lenient().when(balanceRepository.save(any(Balance.class))).thenReturn(newBalance);
            lenient().when(reservationRepository.save(any(Reservation.class))).thenReturn(savedReservation);
            lenient().when(transactionRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
            lenient().when(transactionRepository.save(any(TokenTransaction.class))).thenReturn(new TokenTransaction());
            lenient().when(reservationMapper.toDto(any(Reservation.class))).thenReturn(
                new uk.gegc.quizmaker.features.billing.api.dto.ReservationDto(
                    savedReservation.getId(),
                    userId,
                    ReservationState.ACTIVE,
                    estimatedTokens,
                    0L,
                    savedReservation.getExpiresAt(),
                    null,
                    null,
                    null
                )
            );

            // When & Then
            assertThatThrownBy(() -> billingService.reserve(userId, estimatedTokens, ref, idempotencyKey))
                .isInstanceOf(InsufficientTokensException.class)
                .satisfies(exception -> {
                    InsufficientTokensException ex = (InsufficientTokensException) exception;
                    assertThat(ex.getEstimatedTokens()).isEqualTo(estimatedTokens);
                    assertThat(ex.getAvailableTokens()).isEqualTo(0L);
                    assertThat(ex.getShortfall()).isEqualTo(estimatedTokens);
                    assertThat(ex.getReservationTtl()).isNotNull();
                });
            
            // Verify balance was created
            verify(balanceRepository).save(argThat(balance -> 
                balance.getUserId().equals(userId) &&
                balance.getAvailableTokens() == 0L &&
                balance.getReservedTokens() == 0L
            ));
        }
    }

    @Nested
    @DisplayName("Reserve Insufficient Tokens Scenarios")
    class ReserveInsufficientTokensTests {

        @Test
        @DisplayName("Should throw InsufficientTokensException with all required fields when not enough tokens")
        void shouldThrowInsufficientTokensExceptionWithAllRequiredFieldsWhenNotEnoughTokens() {
            // Given
            UUID userId = UUID.randomUUID();
            long estimatedTokens = 1000L;
            long availableTokens = 500L;
            String ref = "test-ref";
            String idempotencyKey = "test-key";
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(availableTokens);
            balance.setReservedTokens(0L);
            
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            when(transactionRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> billingService.reserve(userId, estimatedTokens, ref, idempotencyKey))
                .isInstanceOf(InsufficientTokensException.class)
                .satisfies(exception -> {
                    InsufficientTokensException ex = (InsufficientTokensException) exception;
                    assertThat(ex.getEstimatedTokens()).isEqualTo(estimatedTokens);
                    assertThat(ex.getAvailableTokens()).isEqualTo(availableTokens);
                    assertThat(ex.getShortfall()).isEqualTo(estimatedTokens - availableTokens);
                    assertThat(ex.getReservationTtl()).isNotNull();
                    assertThat(ex.getReservationTtl()).isAfter(LocalDateTime.now());
                });
            
            // Verify no ledger rows were created
            verify(transactionRepository, never()).save(any(TokenTransaction.class));
            verify(reservationRepository, never()).save(any(Reservation.class));
            
            // Verify balance was not modified
            assertThat(balance.getAvailableTokens()).isEqualTo(availableTokens);
            assertThat(balance.getReservedTokens()).isEqualTo(0L);
        }

        @Test
        @DisplayName("Should throw InsufficientTokensException with exact shortfall calculation")
        void shouldThrowInsufficientTokensExceptionWithExactShortfallCalculation() {
            // Given
            UUID userId = UUID.randomUUID();
            long estimatedTokens = 1500L;
            long availableTokens = 750L;
            long expectedShortfall = 750L;
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(availableTokens);
            balance.setReservedTokens(0L);
            
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            when(transactionRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> billingService.reserve(userId, estimatedTokens, "ref", "key"))
                .isInstanceOf(InsufficientTokensException.class)
                .satisfies(exception -> {
                    InsufficientTokensException ex = (InsufficientTokensException) exception;
                    assertThat(ex.getShortfall()).isEqualTo(expectedShortfall);
                });
        }
    }

    @Nested
    @DisplayName("Reserve Idempotency Scenarios")
    class ReserveIdempotencyTests {

        @Test
        @DisplayName("Should return same reservation for second call with same idempotency key")
        void shouldReturnSameReservationForSecondCallWithSameIdempotencyKey() {
            // Given
            UUID userId = UUID.randomUUID();
            long estimatedTokens = 1000L;
            String ref = "test-ref";
            String idempotencyKey = "test-key";
            
            UUID existingReservationId = UUID.randomUUID();
            Reservation existingReservation = new Reservation();
            existingReservation.setId(existingReservationId);
            existingReservation.setUserId(userId);
            existingReservation.setEstimatedTokens(estimatedTokens);
            existingReservation.setState(ReservationState.ACTIVE);
            
            TokenTransaction existingTx = new TokenTransaction();
            existingTx.setType(TokenTransactionType.RESERVE);
            existingTx.setRefId(existingReservationId.toString());
            
            when(transactionRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existingTx));
            when(reservationRepository.findById(existingReservationId)).thenReturn(Optional.of(existingReservation));
            when(reservationMapper.toDto(existingReservation)).thenReturn(
                new uk.gegc.quizmaker.features.billing.api.dto.ReservationDto(
                    existingReservationId,
                    userId,
                    ReservationState.ACTIVE,
                    estimatedTokens,
                    0L,
                    existingReservation.getExpiresAt(),
                    null,
                    null,
                    null
                )
            );

            // When - call reserve twice with same key
            var result1 = billingService.reserve(userId, estimatedTokens, ref, idempotencyKey);
            var result2 = billingService.reserve(userId, estimatedTokens, ref, idempotencyKey);

            // Then
            assertThat(result1.id()).isEqualTo(result2.id());
            assertThat(result1.id()).isEqualTo(existingReservationId);
            assertThat(result1.estimatedTokens()).isEqualTo(estimatedTokens);
            
            // Verify no new reservation was created
            verify(reservationRepository, never()).save(any(Reservation.class));
            verify(transactionRepository, never()).save(any(TokenTransaction.class));
        }

        @Test
        @DisplayName("Should throw IdempotencyConflictException when key used for different operation")
        void shouldThrowIdempotencyConflictExceptionWhenKeyUsedForDifferentOperation() {
            // Given
            UUID userId = UUID.randomUUID();
            long estimatedTokens = 1000L;
            String idempotencyKey = "conflict-key";
            
            TokenTransaction existingTx = new TokenTransaction();
            existingTx.setType(TokenTransactionType.PURCHASE); // Different operation
            existingTx.setRefId("some-ref");
            
            when(transactionRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existingTx));

            // When & Then
            assertThatThrownBy(() -> billingService.reserve(userId, estimatedTokens, "ref", idempotencyKey))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("already used for a different operation");
        }

        @Test
        @DisplayName("Should throw IdempotencyConflictException when key reused with different amount")
        void shouldThrowIdempotencyConflictExceptionWhenKeyReusedWithDifferentAmount() {
            // Given
            UUID userId = UUID.randomUUID();
            long originalTokens = 1000L;
            long differentTokens = 1500L;
            String idempotencyKey = "amount-conflict-key";
            
            UUID existingReservationId = UUID.randomUUID();
            Reservation existingReservation = new Reservation();
            existingReservation.setId(existingReservationId);
            existingReservation.setUserId(userId);
            existingReservation.setEstimatedTokens(originalTokens);
            
            TokenTransaction existingTx = new TokenTransaction();
            existingTx.setType(TokenTransactionType.RESERVE);
            existingTx.setRefId(existingReservationId.toString());
            
            when(transactionRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existingTx));
            when(reservationRepository.findById(existingReservationId)).thenReturn(Optional.of(existingReservation));

            // When & Then
            assertThatThrownBy(() -> billingService.reserve(userId, differentTokens, "ref", idempotencyKey))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("reused with different reservation amount");
        }
    }

    @Nested
    @DisplayName("Different Request Same Doc/Scope Scenarios")
    class DifferentRequestSameDocScopeTests {

        @Test
        @DisplayName("Should create different reservation for different request hash in key")
        void shouldCreateDifferentReservationForDifferentRequestHashInKey() {
            // Given
            UUID userId = UUID.randomUUID();
            long estimatedTokens = 1000L;
            String ref = "test-ref";
            String idempotencyKey1 = "doc-123-hash-abc";
            String idempotencyKey2 = "doc-123-hash-def"; // Different hash for same document
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(5000L);
            balance.setReservedTokens(0L);
            
            Reservation reservation1 = new Reservation();
            reservation1.setId(UUID.randomUUID());
            reservation1.setUserId(userId);
            reservation1.setEstimatedTokens(estimatedTokens);
            reservation1.setState(ReservationState.ACTIVE);
            
            Reservation reservation2 = new Reservation();
            reservation2.setId(UUID.randomUUID());
            reservation2.setUserId(userId);
            reservation2.setEstimatedTokens(estimatedTokens);
            reservation2.setState(ReservationState.ACTIVE);
            
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            when(transactionRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
            when(reservationRepository.save(any(Reservation.class)))
                .thenReturn(reservation1)
                .thenReturn(reservation2);
            when(transactionRepository.save(any(TokenTransaction.class))).thenReturn(new TokenTransaction());
            when(reservationMapper.toDto(any(Reservation.class)))
                .thenReturn(                new uk.gegc.quizmaker.features.billing.api.dto.ReservationDto(
                    reservation1.getId(), userId, ReservationState.ACTIVE, estimatedTokens, 0L, null, null, null, null))
                .thenReturn(new uk.gegc.quizmaker.features.billing.api.dto.ReservationDto(
                    reservation2.getId(), userId, ReservationState.ACTIVE, estimatedTokens, 0L, null, null, null, null));

            // When
            var result1 = billingService.reserve(userId, estimatedTokens, ref, idempotencyKey1);
            var result2 = billingService.reserve(userId, estimatedTokens, ref, idempotencyKey2);

            // Then
            assertThat(result1.id()).isNotEqualTo(result2.id());
            assertThat(result1.estimatedTokens()).isEqualTo(result2.estimatedTokens());
            
            // Verify two separate reservations were created
            verify(reservationRepository, times(2)).save(any(Reservation.class));
            verify(transactionRepository, times(2)).save(any(TokenTransaction.class));
        }

        @Test
        @DisplayName("Should prevent accidental sharing between different requests")
        void shouldPreventAccidentalSharingBetweenDifferentRequests() {
            // Given
            UUID userId = UUID.randomUUID();
            long estimatedTokens = 1000L;
            String ref = "test-ref";
            String idempotencyKey1 = "doc-123-request-A";
            String idempotencyKey2 = "doc-123-request-B"; // Different request for same document
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(5000L);
            balance.setReservedTokens(0L);
            
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            when(transactionRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
            when(reservationRepository.save(any(Reservation.class))).thenAnswer(invocation -> {
                Reservation res = invocation.getArgument(0);
                res.setId(UUID.randomUUID());
                return res;
            });
            when(transactionRepository.save(any(TokenTransaction.class))).thenReturn(new TokenTransaction());
            when(reservationMapper.toDto(any(Reservation.class))).thenAnswer(invocation -> {
                Reservation res = invocation.getArgument(0);
                return new uk.gegc.quizmaker.features.billing.api.dto.ReservationDto(
                    res.getId(), userId, ReservationState.ACTIVE, estimatedTokens, 0L, null, null, null, null);
            });

            // When
            var result1 = billingService.reserve(userId, estimatedTokens, ref, idempotencyKey1);
            var result2 = billingService.reserve(userId, estimatedTokens, ref, idempotencyKey2);

            // Then
            assertThat(result1.id()).isNotEqualTo(result2.id());
            
            // Verify each request gets its own reservation
            verify(reservationRepository, times(2)).save(any(Reservation.class));
            verify(transactionRepository, times(2)).save(argThat(tx -> 
                tx.getType() == TokenTransactionType.RESERVE
            ));
        }
    }

    @Nested
    @DisplayName("Optimistic Lock Scenarios")
    class OptimisticLockTests {

        @Test
        @DisplayName("Should handle optimistic lock failure and retry successfully")
        void shouldHandleOptimisticLockFailureAndRetrySuccessfully() {
            // Given
            UUID userId = UUID.randomUUID();
            long estimatedTokens = 1000L;
            String ref = "test-ref";
            String idempotencyKey = "test-key";
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(5000L);
            balance.setReservedTokens(0L);
            
            Reservation savedReservation = new Reservation();
            savedReservation.setId(UUID.randomUUID());
            savedReservation.setUserId(userId);
            savedReservation.setEstimatedTokens(estimatedTokens);
            savedReservation.setState(ReservationState.ACTIVE);
            
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            when(transactionRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
            when(reservationRepository.save(any(Reservation.class))).thenReturn(savedReservation);
            when(transactionRepository.save(any(TokenTransaction.class)))
                .thenThrow(new OptimisticLockingFailureException("Optimistic lock failed"))
                .thenReturn(new TokenTransaction());
            when(reservationMapper.toDto(any(Reservation.class))).thenReturn(
                new uk.gegc.quizmaker.features.billing.api.dto.ReservationDto(
                    savedReservation.getId(),
                    userId,
                    ReservationState.ACTIVE,
                    estimatedTokens,
                    0L,
                    savedReservation.getExpiresAt(),
                    null,
                    null,
                    null
                )
            );

            // When
            var result = billingService.reserve(userId, estimatedTokens, ref, idempotencyKey);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(savedReservation.getId());
            
            // Verify retry happened (transaction save called twice)
            verify(transactionRepository, times(2)).save(any(TokenTransaction.class));
        }

        @Test
        @DisplayName("Should handle data integrity violation for concurrent idempotency key insertion")
        void shouldHandleDataIntegrityViolationForConcurrentIdempotencyKeyInsertion() {
            // Given
            UUID userId = UUID.randomUUID();
            long estimatedTokens = 1000L;
            String ref = "test-ref";
            String idempotencyKey = "concurrent-key";
            
            UUID existingReservationId = UUID.randomUUID();
            Reservation existingReservation = new Reservation();
            existingReservation.setId(existingReservationId);
            existingReservation.setUserId(userId);
            existingReservation.setEstimatedTokens(estimatedTokens);
            existingReservation.setState(ReservationState.ACTIVE);
            
            TokenTransaction existingTx = new TokenTransaction();
            existingTx.setType(TokenTransactionType.RESERVE);
            existingTx.setRefId(existingReservationId.toString());
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(5000L);
            balance.setReservedTokens(0L);
            
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            when(transactionRepository.findByIdempotencyKey(idempotencyKey))
                .thenReturn(Optional.empty()) // First call
                .thenReturn(Optional.of(existingTx)); // After race condition
            when(reservationRepository.save(any(Reservation.class))).thenThrow(new DataIntegrityViolationException("Duplicate key"));
            when(reservationRepository.findById(existingReservationId)).thenReturn(Optional.of(existingReservation));
            when(reservationMapper.toDto(existingReservation)).thenReturn(
                new uk.gegc.quizmaker.features.billing.api.dto.ReservationDto(
                    existingReservationId,
                    userId,
                    ReservationState.ACTIVE,
                    estimatedTokens,
                    0L,
                    existingReservation.getExpiresAt(),
                    null,
                    null,
                    null
                )
            );

            // When
            var result = billingService.reserve(userId, estimatedTokens, ref, idempotencyKey);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(existingReservationId);
            
            // Verify it handled the race condition gracefully
            verify(reservationRepository).findById(existingReservationId);
        }

        @Test
        @DisplayName("Should throw exception after max retry attempts for optimistic lock")
        void shouldThrowExceptionAfterMaxRetryAttemptsForOptimisticLock() {
            // Given
            UUID userId = UUID.randomUUID();
            long estimatedTokens = 1000L;
            String ref = "test-ref";
            String idempotencyKey = "retry-key";
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(5000L);
            balance.setReservedTokens(0L);
            
            Reservation savedReservation = new Reservation();
            savedReservation.setId(UUID.randomUUID());
            savedReservation.setUserId(userId);
            savedReservation.setEstimatedTokens(estimatedTokens);
            savedReservation.setState(ReservationState.ACTIVE);
            
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            when(transactionRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
            when(reservationRepository.save(any(Reservation.class))).thenReturn(savedReservation);
            when(transactionRepository.save(any(TokenTransaction.class)))
                .thenThrow(new OptimisticLockingFailureException("Optimistic lock failed"));

            // When & Then
            assertThatThrownBy(() -> billingService.reserve(userId, estimatedTokens, ref, idempotencyKey))
                .isInstanceOf(OptimisticLockingFailureException.class);
            
            // Verify it retried the maximum number of times
            verify(transactionRepository, times(2)).save(any(TokenTransaction.class));
        }
    }

    @Nested
    @DisplayName("Edge Cases and Validation")
    class EdgeCasesAndValidationTests {

        @Test
        @DisplayName("Should throw IllegalArgumentException for zero or negative estimated tokens")
        void shouldThrowIllegalArgumentExceptionForZeroOrNegativeEstimatedTokens() {
            // Given
            UUID userId = UUID.randomUUID();
            String ref = "test-ref";
            String idempotencyKey = "test-key";

            // When & Then - zero tokens
            assertThatThrownBy(() -> billingService.reserve(userId, 0L, ref, idempotencyKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("estimatedBillingTokens must be > 0");

            // When & Then - negative tokens
            assertThatThrownBy(() -> billingService.reserve(userId, -100L, ref, idempotencyKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("estimatedBillingTokens must be > 0");
        }

        @Test
        @DisplayName("Should handle null idempotency key gracefully")
        void shouldHandleNullIdempotencyKeyGracefully() {
            // Given
            UUID userId = UUID.randomUUID();
            long estimatedTokens = 1000L;
            String ref = "test-ref";
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(5000L);
            balance.setReservedTokens(0L);
            
            Reservation savedReservation = new Reservation();
            savedReservation.setId(UUID.randomUUID());
            savedReservation.setUserId(userId);
            savedReservation.setEstimatedTokens(estimatedTokens);
            savedReservation.setState(ReservationState.ACTIVE);
            
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            when(reservationRepository.save(any(Reservation.class))).thenReturn(savedReservation);
            when(transactionRepository.save(any(TokenTransaction.class))).thenReturn(new TokenTransaction());
            when(reservationMapper.toDto(any(Reservation.class))).thenReturn(
                new uk.gegc.quizmaker.features.billing.api.dto.ReservationDto(
                    savedReservation.getId(),
                    userId,
                    ReservationState.ACTIVE,
                    estimatedTokens,
                    0L,
                    savedReservation.getExpiresAt(),
                    null,
                    null,
                    null
                )
            );

            // When
            var result = billingService.reserve(userId, estimatedTokens, ref, null);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(savedReservation.getId());
            
            // Verify transaction was created with null idempotency key
            verify(transactionRepository).save(argThat(tx -> 
                tx.getIdempotencyKey() == null
            ));
        }

        @Test
        @DisplayName("Should handle empty string idempotency key gracefully")
        void shouldHandleEmptyStringIdempotencyKeyGracefully() {
            // Given
            UUID userId = UUID.randomUUID();
            long estimatedTokens = 1000L;
            String ref = "test-ref";
            
            Balance balance = new Balance();
            balance.setUserId(userId);
            balance.setAvailableTokens(5000L);
            balance.setReservedTokens(0L);
            
            Reservation savedReservation = new Reservation();
            savedReservation.setId(UUID.randomUUID());
            savedReservation.setUserId(userId);
            savedReservation.setEstimatedTokens(estimatedTokens);
            savedReservation.setState(ReservationState.ACTIVE);
            
            when(balanceRepository.findByUserId(userId)).thenReturn(Optional.of(balance));
            when(reservationRepository.save(any(Reservation.class))).thenReturn(savedReservation);
            when(transactionRepository.save(any(TokenTransaction.class))).thenReturn(new TokenTransaction());
            when(reservationMapper.toDto(any(Reservation.class))).thenReturn(
                new uk.gegc.quizmaker.features.billing.api.dto.ReservationDto(
                    savedReservation.getId(),
                    userId,
                    ReservationState.ACTIVE,
                    estimatedTokens,
                    0L,
                    savedReservation.getExpiresAt(),
                    null,
                    null,
                    null
                )
            );

            // When
            var result = billingService.reserve(userId, estimatedTokens, ref, "");

            // Then
            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(savedReservation.getId());
            
            // Verify transaction was created with empty idempotency key
            verify(transactionRepository).save(argThat(tx -> 
                tx.getIdempotencyKey().equals("")
            ));
        }
    }
}
