package uk.gegc.quizmaker.features.billing.application.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.billing.application.BillingMetricsService;
import uk.gegc.quizmaker.features.billing.application.ReconciliationService;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransaction;
import uk.gegc.quizmaker.features.billing.infra.repository.BalanceRepository;
import uk.gegc.quizmaker.features.billing.infra.repository.TokenTransactionRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Implementation of reconciliation service.
 * Performs weekly reconciliation checks to ensure data integrity.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationServiceImpl implements ReconciliationService {

    private final BalanceRepository balanceRepository;
    private final TokenTransactionRepository transactionRepository;
    private final BillingMetricsService metricsService;

    @Override
    @Transactional(readOnly = true)
    public ReconciliationResult reconcileUser(UUID userId) {
        try {
            // Get current balance
            var balanceOpt = balanceRepository.findByUserId(userId);
            if (balanceOpt.isEmpty()) {
                return new ReconciliationResult(
                        userId, true, 0, 0, 0, "No balance found for user"
                );
            }
            
            var balance = balanceOpt.get();
            long actualBalance = balance.getAvailableTokens() + balance.getReservedTokens();
            
            // Calculate expected balance from transactions
            long calculatedBalance = calculateBalanceFromTransactions(userId);
            
            long driftAmount = calculatedBalance - actualBalance;
            boolean isBalanced = driftAmount == 0;
            
            String details = String.format(
                    "Calculated: %d, Actual: %d (available: %d, reserved: %d), Drift: %d",
                    calculatedBalance, actualBalance, 
                    balance.getAvailableTokens(), balance.getReservedTokens(), driftAmount
            );
            
            ReconciliationResult result = new ReconciliationResult(
                    userId, isBalanced, calculatedBalance, actualBalance, driftAmount, details
            );
            
            // Record metrics
            if (isBalanced) {
                metricsService.recordReconciliationSuccess(userId);
            } else {
                metricsService.recordReconciliationDrift(userId, driftAmount);
                log.warn("Reconciliation drift detected for user {}: {}", userId, details);
            }
            
            return result;
            
        } catch (Exception e) {
            log.error("Error during reconciliation for user {}: {}", userId, e.getMessage(), e);
            metricsService.recordReconciliationFailure(userId, e.getMessage());
            return new ReconciliationResult(
                    userId, false, 0, 0, 0, "Error during reconciliation: " + e.getMessage()
            );
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ReconciliationSummary reconcileAllUsers() {
        log.info("Starting reconciliation for all users");
        
        List<ReconciliationResult> allResults = new ArrayList<>();
        List<ReconciliationResult> driftResults = new ArrayList<>();
        
        // Get all users with balances
        var allBalances = balanceRepository.findAll();
        
        for (var balance : allBalances) {
            ReconciliationResult result = reconcileUser(balance.getUserId());
            allResults.add(result);
            
            if (result.hasDrift()) {
                driftResults.add(result);
            }
        }
        
        int totalUsers = allResults.size();
        int balancedUsers = totalUsers - driftResults.size();
        int usersWithDrift = driftResults.size();
        long totalDriftAmount = driftResults.stream()
                .mapToLong(ReconciliationResult::driftAmount)
                .sum();
        
        ReconciliationSummary summary = new ReconciliationSummary(
                totalUsers, balancedUsers, usersWithDrift, totalDriftAmount, driftResults
        );
        
        log.info("Reconciliation completed: {} users, {} balanced, {} with drift, total drift: {}",
                totalUsers, balancedUsers, usersWithDrift, totalDriftAmount);
        
        if (!summary.isSuccessful()) {
            log.warn("Reconciliation found drift in {} users with total drift of {} tokens",
                    usersWithDrift, totalDriftAmount);
        }
        
        return summary;
    }

    /**
     * Weekly reconciliation job.
     * Runs every Sunday at 2 AM.
     */
    @Scheduled(cron = "0 0 2 * * SUN")
    public void performWeeklyReconciliation() {
        log.info("Starting weekly reconciliation job");
        
        try {
            ReconciliationSummary summary = reconcileAllUsers();
            
            if (summary.isSuccessful()) {
                log.info("Weekly reconciliation completed successfully: {} users balanced",
                        summary.balancedUsers());
            } else {
                log.warn("Weekly reconciliation found issues: {} users with drift, total drift: {} tokens",
                        summary.usersWithDrift(), summary.totalDriftAmount());
                
                // In a real implementation, you'd send alerts here
                // alertService.sendReconciliationAlert(summary);
            }
            
        } catch (Exception e) {
            log.error("Error during weekly reconciliation: {}", e.getMessage(), e);
            // In a real implementation, you'd send error alerts here
            // alertService.sendReconciliationErrorAlert(e);
        }
    }

    private long calculateBalanceFromTransactions(UUID userId) {
        // Get all transactions for the user
        var transactions = transactionRepository.findByUserId(userId);
        
        long totalCredits = 0;
        long totalDebits = 0;
        
        for (TokenTransaction tx : transactions) {
            switch (tx.getType()) {
                case PURCHASE, ADJUSTMENT -> {
                    // Positive adjustments (credits)
                    if (tx.getAmountTokens() > 0) {
                        totalCredits += tx.getAmountTokens();
                    } else {
                        totalDebits += Math.abs(tx.getAmountTokens());
                    }
                }
                case COMMIT -> {
                    // Commits reduce available balance
                    totalDebits += tx.getAmountTokens();
                }
                case RESERVE -> {
                    // Reserves move from available to reserved (no net change)
                    // But we track this for detailed reconciliation
                }
                case RELEASE -> {
                    // Releases move from reserved back to available (no net change)
                    // But we track this for detailed reconciliation
                }
                case REFUND -> {
                    // Refunds reduce balance
                    totalDebits += tx.getAmountTokens();
                }
            }
        }
        
        return totalCredits - totalDebits;
    }
}
