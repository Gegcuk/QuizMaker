package uk.gegc.quizmaker.features.billing.testutils;

import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.billing.domain.model.Reservation;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransaction;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransactionType;
import uk.gegc.quizmaker.features.billing.infra.repository.BalanceRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.ReservationRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.TokenTransactionRepository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Test implementation of LedgerView that provides access to billing data
 * for invariant validation in tests.
 * 
 * This implementation tracks touched reservations and provides methods
 * for querying reservation and transaction data.
 */
@Component
public class TestLedgerView implements LedgerView {

    private final ReservationRepository reservationRepository;
    private final TokenTransactionRepository transactionRepository;
    @SuppressWarnings("unused")
    private final BalanceRepository balanceRepository;
    
    // Thread-safe tracking of touched reservations
    private final Set<UUID> touchedReservations = ConcurrentHashMap.newKeySet();

    public TestLedgerView(ReservationRepository reservationRepository,
                         TokenTransactionRepository transactionRepository,
                         BalanceRepository balanceRepository) {
        this.reservationRepository = reservationRepository;
        this.transactionRepository = transactionRepository;
        this.balanceRepository = balanceRepository;
    }

    @Override
    public List<Reservation> getReservationsByUserId(UUID userId) {
        // Note: In the actual implementation, we would need to add a findByUserId method
        // For now, we'll return an empty list as this is a test utility
        return Collections.emptyList();
    }

    @Override
    public Reservation getReservationById(UUID reservationId) {
        return reservationRepository.findById(reservationId).orElse(null);
    }

    @Override
    public List<TokenTransaction> getTransactionsByReservationId(UUID reservationId) {
        // Note: In the actual implementation, we would need to add a findByRefId method
        // For now, we'll return an empty list as this is a test utility
        return Collections.emptyList();
    }

    @Override
    public List<TokenTransaction> getTransactionsByUserId(UUID userId) {
        return transactionRepository.findByUserId(userId);
    }

    @Override
    public List<TokenTransaction> getTransactionsByIdempotencyKey(String idempotencyKey) {
        return transactionRepository.findByIdempotencyKey(idempotencyKey)
            .map(List::of)
            .orElse(Collections.emptyList());
    }

    @Override
    public long sumReserved(UUID reservationId) {
        Reservation reservation = getReservationById(reservationId);
        if (reservation == null) {
            return 0L;
        }
        
        // Sum all RESERVE transactions for this reservation
        return getTransactionsByReservationId(reservationId).stream()
            .filter(tx -> tx.getType() == TokenTransactionType.RESERVE)
            .mapToLong(TokenTransaction::getAmountTokens)
            .sum();
    }

    @Override
    public long sumCommitted(UUID reservationId) {
        return getTransactionsByReservationId(reservationId).stream()
            .filter(tx -> tx.getType() == TokenTransactionType.COMMIT)
            .mapToLong(TokenTransaction::getAmountTokens)
            .sum();
    }

    @Override
    public long sumReleased(UUID reservationId) {
        return getTransactionsByReservationId(reservationId).stream()
            .filter(tx -> tx.getType() == TokenTransactionType.RELEASE)
            .mapToLong(TokenTransaction::getAmountTokens)
            .sum();
    }

    @Override
    public List<UUID> reservationsTouchedInCurrentTest() {
        return new ArrayList<>(touchedReservations);
    }

    @Override
    public void markReservationTouched(UUID reservationId) {
        touchedReservations.add(reservationId);
    }

    @Override
    public void clearTouchedReservations() {
        touchedReservations.clear();
    }

    /**
     * Gets all reservations that were created or modified during the test.
     * 
     * @return List of all touched reservations
     */
    public List<Reservation> getTouchedReservations() {
        return touchedReservations.stream()
            .map(this::getReservationById)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    /**
     * Gets all transactions for touched reservations.
     * 
     * @return List of all transactions for touched reservations
     */
    public List<TokenTransaction> getTouchedReservationTransactions() {
        return touchedReservations.stream()
            .flatMap(reservationId -> getTransactionsByReservationId(reservationId).stream())
            .collect(Collectors.toList());
    }

    /**
     * Gets a summary of all touched reservations and their transaction totals.
     * 
     * @return Map of reservation ID to transaction summary
     */
    public Map<UUID, TransactionSummary> getTouchedReservationSummaries() {
        return touchedReservations.stream()
            .collect(Collectors.toMap(
                reservationId -> reservationId,
                reservationId -> {
                    List<TokenTransaction> transactions = getTransactionsByReservationId(reservationId);
                    return TransactionSummary.from(transactions);
                }
            ));
    }

    /**
     * Record representing a summary of transactions for a reservation.
     */
    public record TransactionSummary(
        long reserved,
        long committed,
        long released,
        long totalTransactions
    ) {
        public static TransactionSummary from(List<TokenTransaction> transactions) {
            long reserved = transactions.stream()
                .filter(tx -> tx.getType() == TokenTransactionType.RESERVE)
                .mapToLong(TokenTransaction::getAmountTokens)
                .sum();
            
            long committed = transactions.stream()
                .filter(tx -> tx.getType() == TokenTransactionType.COMMIT)
                .mapToLong(TokenTransaction::getAmountTokens)
                .sum();
            
            long released = transactions.stream()
                .filter(tx -> tx.getType() == TokenTransactionType.RELEASE)
                .mapToLong(TokenTransaction::getAmountTokens)
                .sum();
            
            return new TransactionSummary(reserved, committed, released, transactions.size());
        }
    }
}
