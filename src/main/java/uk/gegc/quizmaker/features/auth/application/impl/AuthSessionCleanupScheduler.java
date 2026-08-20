package uk.gegc.quizmaker.features.auth.application.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.auth.application.AuthSessionService;
import uk.gegc.quizmaker.features.auth.application.OAuthExchangeService;

@Component
@RequiredArgsConstructor
@Slf4j
public class AuthSessionCleanupScheduler {

    private final AuthSessionService authSessionService;
    private final OAuthExchangeService oauthExchangeService;

    @Scheduled(cron = "${app.auth.session-cleanup-cron:0 15 * * * *}")
    public void purgeExpiredSessions() {
        int purgedSessions = authSessionService.purgeExpiredSessions();
        int purgedCodes = oauthExchangeService.purgeExpiredCodes();
        if (purgedSessions > 0 || purgedCodes > 0) {
            log.info(
                    "Purged {} expired authentication session(s) and {} expired OAuth exchange code(s)",
                    purgedSessions,
                    purgedCodes
            );
        }
    }
}
