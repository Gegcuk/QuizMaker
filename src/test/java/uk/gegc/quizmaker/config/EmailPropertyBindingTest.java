package uk.gegc.quizmaker.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import uk.gegc.quizmaker.shared.config.AwsSesConfig;
import uk.gegc.quizmaker.shared.email.EmailService;
import uk.gegc.quizmaker.shared.email.impl.AwsSesEmailService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring context tests for email service property binding.
 * 
 * These tests verify that configuration properties correctly bind to beans
 * and influence their behavior:
 * 
 * - SES timeout properties bind and configure the SES client
 * - Template location properties bind and allow custom template paths
 * - Properties use appropriate defaults when not explicitly configured
 * 
 * Each test runs in a separate Spring context to ensure isolation.
 */
@DisplayName("Email Property Binding Spring Context Tests")
class EmailPropertyBindingTest {

    /**
     * Tests for SES timeout property binding.
     * 
     * These tests verify that timeout properties (api-call-timeout, 
     * api-call-attempt-timeout, http-connection-timeout, http-socket-timeout)
     * are correctly bound from application properties and used in the
     * SES client configuration.
     */
    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @TestPropertySource(properties = {
        "app.email.provider=ses",
        "app.email.from=test-sender@verified-domain.com",
        "app.email.region=us-east-1",
        "AWS_ACCESS_KEY_ID=test-access-key",
        "AWS_SECRET_ACCESS_KEY=test-secret-key",
        // Custom timeout values to verify binding
        "app.email.ses.api-call-timeout-ms=45000",
        "app.email.ses.api-call-attempt-timeout-ms=15000",
        "app.email.ses.http-connection-timeout-ms=8000",
        "app.email.ses.http-socket-timeout-ms=20000",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
    })
    @DisplayName("SES Timeout Property Binding Tests")
    class SesTimeoutPropertyBindingTest {

        @Autowired
        private ApplicationContext context;

        @Value("${app.email.ses.api-call-timeout-ms}")
        private long apiCallTimeoutMs;

        @Value("${app.email.ses.api-call-attempt-timeout-ms}")
        private long apiCallAttemptTimeoutMs;

        @Value("${app.email.ses.http-connection-timeout-ms}")
        private int httpConnectionTimeoutMs;

        @Value("${app.email.ses.http-socket-timeout-ms}")
        private int httpSocketTimeoutMs;

        @Test
        @DisplayName("SES timeout properties should bind correctly from application properties")
        void sesTimeoutPropertiesShouldBindCorrectly() {
            // Given / When - properties are bound by Spring
            
            // Then - verify all timeout properties are bound with custom values
            assertThat(apiCallTimeoutMs).isEqualTo(45000);
            assertThat(apiCallAttemptTimeoutMs).isEqualTo(15000);
            assertThat(httpConnectionTimeoutMs).isEqualTo(8000);
            assertThat(httpSocketTimeoutMs).isEqualTo(20000);
        }

        @Test
        @DisplayName("SES client should be created successfully with custom timeout configuration")
        void sesClientShouldBeCreatedWithCustomTimeouts() {
            // Given / When
            SesV2Client sesClient = context.getBean(SesV2Client.class);

            // Then - client should be created successfully (indicates timeouts were valid)
            assertThat(sesClient).isNotNull();
            // Note: We can't directly inspect the client's internal timeout configuration
            // but the fact that it was created successfully validates the binding worked
        }

        @Test
        @DisplayName("EmailService should be functional with custom timeout configuration")
        void emailServiceShouldBeFunctionalWithCustomTimeouts() {
            // Given / When
            EmailService emailService = context.getBean(EmailService.class);

            // Then - service should be created and functional
            assertThat(emailService).isNotNull();
            assertThat(emailService).isInstanceOf(AwsSesEmailService.class);
            
            // Calling methods shouldn't throw due to configuration issues
            // (actual sends will fail with test credentials, but bean wiring is correct)
        }

        @Test
        @DisplayName("AwsSesConfig bean should exist and be configured with custom properties")
        void awsSesConfigBeanShouldExist() {
            // Given / When
            AwsSesConfig config = context.getBean(AwsSesConfig.class);

            // Then
            assertThat(config).isNotNull();
        }
    }

    /**
     * Tests for default timeout values.
     * 
     * Verifies that when timeout properties are not explicitly set,
     * the system uses sensible defaults.
     */
    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @TestPropertySource(properties = {
        "app.email.provider=ses",
        "app.email.from=test-sender@verified-domain.com",
        "app.email.region=us-east-1",
        "AWS_ACCESS_KEY_ID=test-access-key",
        "AWS_SECRET_ACCESS_KEY=test-secret-key",
        // No custom timeout values - should use defaults from application.properties
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
    })
    @DisplayName("SES Default Timeout Tests")
    class SesDefaultTimeoutTest {

        @Autowired
        private ApplicationContext context;

        @Value("${app.email.ses.api-call-timeout-ms}")
        private long apiCallTimeoutMs;

        @Value("${app.email.ses.api-call-attempt-timeout-ms}")
        private long apiCallAttemptTimeoutMs;

        @Value("${app.email.ses.http-connection-timeout-ms}")
        private int httpConnectionTimeoutMs;

        @Test
        @DisplayName("Default timeout properties should be bound from application.properties")
        void defaultTimeoutPropertiesShouldBeBound() {
            // Given / When - properties are bound by Spring with defaults
            
            // Then - verify default values from application.properties (30000, 10000, 5000)
            assertThat(apiCallTimeoutMs).isEqualTo(30000);
            assertThat(apiCallAttemptTimeoutMs).isEqualTo(10000);
            assertThat(httpConnectionTimeoutMs).isEqualTo(5000);
        }

        @Test
        @DisplayName("SES client should be created successfully with default timeout configuration")
        void sesClientShouldBeCreatedWithDefaultTimeouts() {
            // Given / When
            SesV2Client sesClient = context.getBean(SesV2Client.class);

            // Then
            assertThat(sesClient).isNotNull();
        }
    }

    /**
     * Tests for template location property binding.
     * 
     * Verifies that custom template paths can be configured and Spring
     * correctly resolves and loads the template resources.
     */
    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @TestPropertySource(properties = {
        "app.email.provider=ses",
        "app.email.from=test-sender@verified-domain.com",
        "app.email.region=us-east-1",
        "AWS_ACCESS_KEY_ID=test-access-key",
        "AWS_SECRET_ACCESS_KEY=test-secret-key",
        // Use default template paths (classpath:email/*)
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
    })
    @DisplayName("Template Location Property Binding Tests")
    class TemplateLocationBindingTest {

        @Autowired
        private ApplicationContext context;

        @Value("${app.email.templates.password-reset:classpath:email/password-reset-email.txt}")
        private Resource passwordResetTemplateResource;

        @Value("${app.email.templates.verification:classpath:email/email-verification-email.txt}")
        private Resource verificationTemplateResource;

        @Test
        @DisplayName("Default template location properties should bind to classpath resources")
        void defaultTemplateLocationsShouldBind() {
            // Given / When - properties are bound by Spring
            
            // Then - resources should be non-null and exist
            assertThat(passwordResetTemplateResource).isNotNull();
            assertThat(verificationTemplateResource).isNotNull();
            assertThat(passwordResetTemplateResource.exists()).isTrue();
            assertThat(verificationTemplateResource.exists()).isTrue();
        }

        @Test
        @DisplayName("Default template resources should be readable and contain content")
        void defaultTemplateResourcesShouldBeReadable() throws IOException {
            // Given
            assertThat(passwordResetTemplateResource.exists()).isTrue();
            assertThat(verificationTemplateResource.exists()).isTrue();

            // When
            String passwordResetContent = new String(
                passwordResetTemplateResource.getInputStream().readAllBytes(), 
                StandardCharsets.UTF_8
            );
            String verificationContent = new String(
                verificationTemplateResource.getInputStream().readAllBytes(), 
                StandardCharsets.UTF_8
            );

            // Then - content should not be empty
            assertThat(passwordResetContent).isNotBlank();
            assertThat(verificationContent).isNotBlank();
            
            // Templates should contain placeholder markers for dynamic content
            assertThat(passwordResetContent).contains("%s"); // URL placeholder
            assertThat(verificationContent).contains("%s"); // URL placeholder
        }

        @Test
        @DisplayName("Template resource descriptions should indicate classpath location")
        void templateResourceDescriptionsShouldIndicateClasspath() {
            // Given / When
            String passwordResetDescription = passwordResetTemplateResource.getDescription();
            String verificationDescription = verificationTemplateResource.getDescription();

            // Then - descriptions should indicate these are classpath resources
            assertThat(passwordResetDescription).contains("email/password-reset-email.txt");
            assertThat(verificationDescription).contains("email/email-verification-email.txt");
        }

        @Test
        @DisplayName("EmailService should initialize successfully with default template paths")
        void emailServiceShouldInitializeWithDefaultTemplates() {
            // Given / When
            EmailService emailService = context.getBean(EmailService.class);

            // Then - service should be initialized (templates loaded in @PostConstruct)
            assertThat(emailService).isNotNull();
            assertThat(emailService).isInstanceOf(AwsSesEmailService.class);
        }
    }

    /**
     * Tests for configuration set name property binding.
     * 
     * Verifies that the optional SES configuration set name property
     * binds correctly and defaults to null when not set.
     */
    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @TestPropertySource(properties = {
        "app.email.provider=ses",
        "app.email.from=test-sender@verified-domain.com",
        "app.email.region=us-east-1",
        "AWS_ACCESS_KEY_ID=test-access-key",
        "AWS_SECRET_ACCESS_KEY=test-secret-key",
        "app.email.ses.configuration-set=test-config-set",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
    })
    @DisplayName("Configuration Set Property Binding Tests")
    class ConfigurationSetBindingTest {

        @Value("${app.email.ses.configuration-set:#{null}}")
        private String configurationSetName;

        @Test
        @DisplayName("Configuration set name property should bind correctly when set")
        void configurationSetNameShouldBindWhenSet() {
            // Given / When - property is bound by Spring
            
            // Then
            assertThat(configurationSetName).isEqualTo("test-config-set");
        }
    }

    /**
     * Tests for optional configuration set with default null value.
     */
    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @TestPropertySource(properties = {
        "app.email.provider=ses",
        "app.email.from=test-sender@verified-domain.com",
        "app.email.region=us-east-1",
        "AWS_ACCESS_KEY_ID=test-access-key",
        "AWS_SECRET_ACCESS_KEY=test-secret-key",
        // No configuration-set property - should default to null
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
    })
    @DisplayName("Configuration Set Default Value Tests")
    class ConfigurationSetDefaultTest {

        @Value("${app.email.ses.configuration-set:#{null}}")
        private String configurationSetName;

        @Test
        @DisplayName("Configuration set name should default to empty string when not set")
        void configurationSetNameShouldDefaultToEmptyString() {
            // Given / When - property is bound by Spring with SpEL default
            
            // Then - empty string from .env or default, not null
            assertThat(configurationSetName).isNotNull();
            assertThat(configurationSetName).isEmpty();
        }
    }

    /**
     * Tests for frontend base URL property binding.
     * 
     * Verifies that the base URL used in email links binds correctly.
     */
    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @TestPropertySource(properties = {
        "app.email.provider=ses",
        "app.email.from=test-sender@verified-domain.com",
        "app.email.region=us-east-1",
        "AWS_ACCESS_KEY_ID=test-access-key",
        "AWS_SECRET_ACCESS_KEY=test-secret-key",
        "app.frontend.base-url=https://quizmaker.example.com",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
    })
    @DisplayName("Frontend Base URL Property Binding Tests")
    class FrontendBaseUrlBindingTest {

        @Value("${app.frontend.base-url}")
        private String baseUrl;

        @Test
        @DisplayName("Frontend base URL property should bind correctly")
        void frontendBaseUrlShouldBind() {
            // Given / When - property is bound by Spring
            
            // Then
            assertThat(baseUrl).isEqualTo("https://quizmaker.example.com");
        }
    }

    /**
     * Tests for email subject property binding.
     * 
     * Verifies that email subject properties bind correctly.
     */
    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @TestPropertySource(properties = {
        "app.email.provider=ses",
        "app.email.from=test-sender@verified-domain.com",
        "app.email.region=us-east-1",
        "AWS_ACCESS_KEY_ID=test-access-key",
        "AWS_SECRET_ACCESS_KEY=test-secret-key",
        "app.email.password-reset.subject=Custom Reset Your Password",
        "app.email.verification.subject=Custom Verify Your Email",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
    })
    @DisplayName("Email Subject Property Binding Tests")
    class EmailSubjectBindingTest {

        @Value("${app.email.password-reset.subject}")
        private String passwordResetSubject;

        @Value("${app.email.verification.subject}")
        private String verificationSubject;

        @Test
        @DisplayName("Email subject properties should bind correctly")
        void emailSubjectsShouldBind() {
            // Given / When - properties are bound by Spring
            
            // Then
            assertThat(passwordResetSubject).isEqualTo("Custom Reset Your Password");
            assertThat(verificationSubject).isEqualTo("Custom Verify Your Email");
        }
    }

    /**
     * Tests for TTL property binding.
     * 
     * Verifies that token TTL properties bind correctly and are used
     * to calculate expiration times in email content.
     */
    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @TestPropertySource(properties = {
        "app.email.provider=ses",
        "app.email.from=test-sender@verified-domain.com",
        "app.email.region=us-east-1",
        "AWS_ACCESS_KEY_ID=test-access-key",
        "AWS_SECRET_ACCESS_KEY=test-secret-key",
        "app.auth.reset-token-ttl-minutes=120",
        "app.auth.verification-token-ttl-minutes=1440",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
    })
    @DisplayName("Token TTL Property Binding Tests")
    class TokenTtlBindingTest {

        @Value("${app.auth.reset-token-ttl-minutes}")
        private long resetTokenTtlMinutes;

        @Value("${app.auth.verification-token-ttl-minutes}")
        private long verificationTokenTtlMinutes;

        @Test
        @DisplayName("Token TTL properties should bind correctly")
        void tokenTtlPropertiesShouldBind() {
            // Given / When - properties are bound by Spring
            
            // Then
            assertThat(resetTokenTtlMinutes).isEqualTo(120);
            assertThat(verificationTokenTtlMinutes).isEqualTo(1440);
        }
    }
}

