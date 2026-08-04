package uk.gegc.quizmaker.features.billing.application;

import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Role;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Arrays;

/**
 * Starts only Spring's billing-property binding infrastructure for deployment preflight checks.
 */
public final class BillingConfigurationPreflight {

    static final String ARGUMENT = "--config-preflight";

    private BillingConfigurationPreflight() {
    }

    public static boolean isRequested(String[] args) {
        return Arrays.stream(args).anyMatch(ARGUMENT::equals);
    }

    public static void run(String[] args) {
        String[] springArguments = Arrays.stream(args)
                .filter(argument -> !ARGUMENT.equals(argument))
                .toArray(String[]::new);

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(PreflightConfiguration.class)
                .profiles("prod", "config-preflight")
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .run(springArguments)) {
            context.getBean(BillingProperties.class);
            verifyConfiguredRatio(context.getEnvironment());
        }

        System.out.println("Production billing configuration preflight passed.");
    }

    static void verifyConfiguredRatio(Environment environment) {
        String configuredRatio = environment.getProperty("billing.token-to-llm-ratio");
        if (configuredRatio == null) {
            return;
        }

        if (!StringUtils.hasText(configuredRatio) || !configuredRatio.equals(configuredRatio.trim())) {
            throw new IllegalStateException(
                    "billing.token-to-llm-ratio must be a non-blank, unpadded positive integer");
        }
    }

    @Profile("config-preflight")
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BillingProperties.class)
    static class PreflightConfiguration {

        @Bean(name = EnableConfigurationProperties.VALIDATOR_BEAN_NAME)
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        static LocalValidatorFactoryBean configurationPropertiesValidator() {
            return new LocalValidatorFactoryBean();
        }
    }
}
