package uk.gegc.quizmaker.features.auth.application.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.auth.application.AuthSessionService;

@Component
@RequiredArgsConstructor
@Slf4j
public class AuthSessionCleanupScheduler {

    private final AuthSessionService authSessionService;

    @Scheduled(cron = "${app.auth.session-cleanup-cron:0 15 * * * *}")
    public void purgeExpiredSessions() {
        int purged = authSessionService.purgeExpiredSessions();
        if (purged > 0) {
            log.info("Purged {} expired authentication session(s)", purged);
        }
    }
}
