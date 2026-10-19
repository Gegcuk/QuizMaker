package uk.gegc.quizmaker.features.auth.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.*;

class AuthRateLimitPropertiesTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(Config.class)
            .withPropertyValues("app.auth.rate-limit.key-secret=synthetic-only-configuration-key-457");

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthRateLimitProperties.class)
    static class Config { }

    @Test
    void bindsOverrideWhileKeepingAllOtherProfileADefaultsAndWindowModes() {
        context.withPropertyValues("app.auth.rate-limit.limits.login-account.requests=14",
                "app.auth.rate-limit.limits.login-account.window-seconds=90").run(result -> {
            assertThat(result).hasNotFailed();
            var properties = result.getBean(AuthRateLimitProperties.class);
            assertThat(properties.getLimits()).hasSize(17);
            assertThat(properties.getLimits().get("login-account").getRequests()).isEqualTo(14);
            assertThat(properties.getLimits().get("login-account").getWindowSeconds()).isEqualTo(90);
            assertThat(properties.getLimits().get("login-account").isRolling()).isTrue();
            assertThat(properties.getLimits().get("verify-email").isRolling()).isFalse();
            assertThat(properties.getLimits().get("registration-network-burst").getRequests()).isEqualTo(30);
        });
    }

    @Test
    void rejectsZeroLimitUnknownDimensionAndAlgorithmChangesAtStartup() {
        for (String property : new String[]{"app.auth.rate-limit.limits.login-account.requests=0",
                "app.auth.rate-limit.limits.unknown.requests=1", "app.auth.rate-limit.limits.login-account.rolling=false",
                "app.auth.rate-limit.limits.refresh-network.window-seconds=0", "app.auth.rate-limit.cleanup-batch-size=0"}) {
            context.withPropertyValues(property).run(result -> assertThat(result).hasFailed());
        }
    }
}
