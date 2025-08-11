package uk.gegc.quizmaker.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.repository.auth.PasswordResetTokenRepository;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class TokenCleanupScheduler {

    private final PasswordResetTokenRepository passwordResetTokenRepository;

    @Scheduled(cron = "0 */10 * * * *") // Every 10 minutes
    @Transactional
    public void purgeExpiredTokens() {
        try {
            LocalDateTime now = LocalDateTime.now();
            passwordResetTokenRepository.deleteExpiredTokens(now);
            log.debug("Scheduled cleanup of expired password reset tokens completed");
        } catch (Exception e) {
            log.error("Failed to clean up expired password reset tokens", e);
        }
    }
}
