package uk.gegc.quizmaker.features.auth.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Clock;

/** Fails startup before OAuth traffic is accepted when the rollout policy is unsafe. */
@Component
public class OAuth2ExchangeConfigurationValidator implements InitializingBean {

    private final OAuth2ExchangeProperties properties;
    private final Clock utcClock;

    public OAuth2ExchangeConfigurationValidator(
            OAuth2ExchangeProperties properties,
            @Qualifier("utcClock") Clock utcClock
    ) {
        this.properties = properties;
        this.utcClock = utcClock;
    }

    @Override
    public void afterPropertiesSet() {
        properties.validateConfiguration(utcClock.instant());
    }
}
