package uk.gegc.quizmaker.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.auth.domain.repository.EmailVerificationTokenRepository;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmailVerificationTokenCleanupScheduler {
    
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Scheduled(cron = "0 */10 * * * *") // Run every 10 minutes
    @Transactional
    public void purgeExpiredTokens() {
        try {
            LocalDateTime now = LocalDateTime.now();
            emailVerificationTokenRepository.deleteExpiredTokens(now);
            log.debug("Expired email verification tokens cleanup completed");
        } catch (Exception e) {
            log.error("Failed to cleanup expired email verification tokens", e);
        }
    }
}
