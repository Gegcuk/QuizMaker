package uk.gegc.quizmaker.features.billing.testutils;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Record representing a snapshot of an account's balance state at a point in time.
 * 
 * This is used for tracking balance changes and validating the balance math invariant (I2).
 */
public record AccountSnapshot(
    UUID userId,
    long available,
    long reserved,
    long totalCreditsAdded,
    long totalAdjustments,
    long totalCommitted,
    LocalDateTime timestamp
) {
    
    /**
     * Creates a snapshot with zero values for tracking changes.
     * 
     * @param userId The user ID
     * @param available Current available tokens
     * @param reserved Current reserved tokens
     * @return Account snapshot with zero change values
     */
    public static AccountSnapshot initial(UUID userId, long available, long reserved) {
        return new AccountSnapshot(
            userId,
            available,
            reserved,
            0L,
            0L,
            0L,
            LocalDateTime.now()
        );
    }
    
    /**
     * Creates a snapshot representing the final state after operations.
     * 
     * @param userId The user ID
     * @param available Final available tokens
     * @param reserved Final reserved tokens
     * @param totalCreditsAdded Total credits added during operations
     * @param totalAdjustments Total adjustments made during operations
     * @param totalCommitted Total tokens committed during operations
     * @return Account snapshot with change values
     */
    public static AccountSnapshot after(UUID userId, long available, long reserved,
                                     long totalCreditsAdded, long totalAdjustments, long totalCommitted) {
        return new AccountSnapshot(
            userId,
            available,
            reserved,
            totalCreditsAdded,
            totalAdjustments,
            totalCommitted,
            LocalDateTime.now()
        );
    }
    
    /**
     * Gets the total balance (available + reserved).
     * 
     * @return Total balance
     */
    public long totalBalance() {
        return available + reserved;
    }
    
    /**
     * Gets the net change in available tokens.
     * 
     * @param initial The initial snapshot
     * @return Net change in available tokens
     */
    public long availableChange(AccountSnapshot initial) {
        return this.available - initial.available;
    }
    
    /**
     * Gets the net change in reserved tokens.
     * 
     * @param initial The initial snapshot
     * @return Net change in reserved tokens
     */
    public long reservedChange(AccountSnapshot initial) {
        return this.reserved - initial.reserved;
    }
    
    /**
     * Gets the net change in total balance.
     * 
     * @param initial The initial snapshot
     * @return Net change in total balance
     */
    public long totalBalanceChange(AccountSnapshot initial) {
        return this.totalBalance() - initial.totalBalance();
    }
}
