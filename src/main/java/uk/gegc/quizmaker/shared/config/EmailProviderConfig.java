package uk.gegc.quizmaker.shared.config;

import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import uk.gegc.quizmaker.shared.email.EmailService;
import uk.gegc.quizmaker.shared.email.impl.AwsSesEmailService;
import uk.gegc.quizmaker.shared.email.impl.NoopEmailService;

/**
 * Email provider configuration that selects the appropriate EmailService implementation
 * based on the app.email.provider property.
 * 
 * Supported providers:
 * - ses: AWS SES via HTTPS API (production recommended)
 * - smtp: Spring Mail SMTP-based (fallback, uses existing EmailServiceImpl)
 * - noop: No-op logging only (default for development)
 * 
 * Configuration property:
 * - app.email.provider=ses|smtp|noop (defaults to noop)
 * 
 * The @Primary bean is selected based on the provider value, ensuring the rest of the
 * application can simply inject EmailService without knowing the implementation.
 */
@Slf4j
@Configuration
public class EmailProviderConfig {

    /**
     * AWS SES email service bean (activated when app.email.provider=ses).
     * Marked as @Primary to override the SMTP-based EmailServiceImpl.
     * 
     * Configuration properties are injected via field-level @Value annotations in AwsSesEmailService.
     * Only the SesV2Client needs to be passed explicitly to the constructor.
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "app.email.provider", havingValue = "ses")
    public EmailService awsSesEmailService(SesV2Client sesV2Client,
                                          ObjectProvider<MeterRegistry> meterRegistryProvider) {
        log.info("Activating AWS SES email service as primary provider");
        return new AwsSesEmailService(sesV2Client, meterRegistryProvider.getIfAvailable());
    }

    /**
     * No-op email service bean (activated when app.email.provider=noop or not set).
     * Default for local development when credentials are not configured.
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "app.email.provider", havingValue = "noop", matchIfMissing = true)
    public EmailService noopEmailService() {
        log.info("Activating No-op email service (emails will be logged but not sent)");
        return new NoopEmailService();
    }

    /**
     * When app.email.provider=smtp, the existing EmailServiceImpl (SMTP-based)
     * is used as-is. It's already annotated with @Service, so it will be picked up
     * automatically when no @Primary bean is defined.
     * 
     * No explicit bean needed here - Spring will use the @Service annotated
     * EmailServiceImpl when provider=smtp.
     */
}
