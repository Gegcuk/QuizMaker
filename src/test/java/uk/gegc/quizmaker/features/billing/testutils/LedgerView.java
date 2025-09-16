package uk.gegc.quizmaker.features.billing.testutils;

import uk.gegc.quizmaker.features.billing.domain.model.Reservation;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransaction;

import java.util.List;
import java.util.UUID;

/**
 * Interface for querying reservation and transaction data for invariant validation.
 * 
 * This interface provides a clean abstraction for accessing ledger data
 * without depending on specific repository implementations in tests.
 */
public interface LedgerView {

    /**
     * Gets all reservations for a specific user.
     * 
     * @param userId The user ID
     * @return List of reservations for the user
     */
    List<Reservation> getReservationsByUserId(UUID userId);

    /**
     * Gets a specific reservation by ID.
     * 
     * @param reservationId The reservation ID
     * @return The reservation, or null if not found
     */
    Reservation getReservationById(UUID reservationId);

    /**
     * Gets all transactions for a specific reservation.
     * 
     * @param reservationId The reservation ID
     * @return List of transactions for the reservation
     */
    List<TokenTransaction> getTransactionsByReservationId(UUID reservationId);

    /**
     * Gets all transactions for a specific user.
     * 
     * @param userId The user ID
     * @return List of transactions for the user
     */
    List<TokenTransaction> getTransactionsByUserId(UUID userId);

    /**
     * Gets all transactions with a specific idempotency key.
     * 
     * @param idempotencyKey The idempotency key
     * @return List of transactions with the key
     */
    List<TokenTransaction> getTransactionsByIdempotencyKey(String idempotencyKey);

    /**
     * Sums all reserved tokens for a specific reservation.
     * 
     * @param reservationId The reservation ID
     * @return Total reserved tokens
     */
    long sumReserved(UUID reservationId);

    /**
     * Sums all committed tokens for a specific reservation.
     * 
     * @param reservationId The reservation ID
     * @return Total committed tokens
     */
    long sumCommitted(UUID reservationId);

    /**
     * Sums all released tokens for a specific reservation.
     * 
     * @param reservationId The reservation ID
     * @return Total released tokens
     */
    long sumReleased(UUID reservationId);

    /**
     * Gets all reservation IDs that were touched during the current test.
     * This is used by the JUnit extension for automatic invariant checking.
     * 
     * @return List of reservation IDs touched in current test
     */
    List<UUID> reservationsTouchedInCurrentTest();

    /**
     * Marks a reservation as touched in the current test.
     * This is used by the JUnit extension for automatic invariant checking.
     * 
     * @param reservationId The reservation ID that was touched
     */
    void markReservationTouched(UUID reservationId);

    /**
     * Clears the touched reservations list.
     * This should be called at the start of each test.
     */
    void clearTouchedReservations();
}
