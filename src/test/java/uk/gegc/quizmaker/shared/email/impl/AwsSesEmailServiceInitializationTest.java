package uk.gegc.quizmaker.shared.email.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import software.amazon.awssdk.services.sesv2.SesV2Client;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for initialization functionality in AwsSesEmailService.
 * 
 * Tests cover:
 * - @PostConstruct logs WARN when fromEmail is missing or placeholder (noreply@example.com)
 * - @PostConstruct loads both templates exactly once
 * - Template loading behavior during initialization
 * - Logging behavior during initialization
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AwsSesEmailService Initialization Tests")
class AwsSesEmailServiceInitializationTest {

    @Mock
    private SesV2Client sesClient;
    @Mock
    private Resource passwordResetTemplateResource;
    @Mock
    private Resource verificationTemplateResource;
    
    private SimpleMeterRegistry meterRegistry;
    private AwsSesEmailService emailService;
    private ListAppender<ILoggingEvent> listAppender;

    private static final String PASSWORD_RESET_TEMPLATE = "Password reset: %s | Valid for: %s";
    private static final String VERIFICATION_TEMPLATE = "Email verification: %s | Valid for: %s";

    @BeforeEach
    void setUp() throws Exception {
        meterRegistry = new SimpleMeterRegistry();
        
        // Set up log capture
        Logger logger = (Logger) LoggerFactory.getLogger(AwsSesEmailService.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);
        logger.setLevel(Level.ALL);
        
        // Set up template resources
        lenient().when(passwordResetTemplateResource.getInputStream())
            .thenReturn(new ByteArrayInputStream(PASSWORD_RESET_TEMPLATE.getBytes(StandardCharsets.UTF_8)));
        lenient().when(passwordResetTemplateResource.getDescription()).thenReturn("password-reset-template");
        
        lenient().when(verificationTemplateResource.getInputStream())
            .thenReturn(new ByteArrayInputStream(VERIFICATION_TEMPLATE.getBytes(StandardCharsets.UTF_8)));
        lenient().when(verificationTemplateResource.getDescription()).thenReturn("verification-template");
    }

    @AfterEach
    void tearDown() {
        if (listAppender != null) {
            listAppender.stop();
        }
    }

    // ========== fromEmail Warning Tests ==========

    @Test
    @DisplayName("@PostConstruct should log WARN when fromEmail is null")
    void postConstructShouldLogWarnWhenFromEmailIsNull() throws Exception {
        // Given
        emailService = new AwsSesEmailService(sesClient, meterRegistry);
        injectTemplateResources();
        injectProperty("fromEmail", null);
        
        // When
        emailService.initialize();
        
        // Then
        List<String> warnMessages = getLogMessages(Level.WARN);
        assertThat(warnMessages).hasSize(1);
        assertThat(warnMessages.get(0)).contains("fromEmail is not configured or using default placeholder");
        assertThat(warnMessages.get(0)).contains("Ensure app.email.from is set to a verified identity in SES");
        
        // Verify templates were still loaded
        verify(passwordResetTemplateResource).getInputStream();
        verify(verificationTemplateResource).getInputStream();
    }

    @Test
    @DisplayName("@PostConstruct should log WARN when fromEmail is blank")
    void postConstructShouldLogWarnWhenFromEmailIsBlank() throws Exception {
        // Given
        emailService = new AwsSesEmailService(sesClient, meterRegistry);
        injectTemplateResources();
        injectProperty("fromEmail", "   "); // Blank string
        
        // When
        emailService.initialize();
        
        // Then
        List<String> warnMessages = getLogMessages(Level.WARN);
        assertThat(warnMessages).hasSize(1);
        assertThat(warnMessages.get(0)).contains("fromEmail is not configured or using default placeholder");
        
        // Verify templates were still loaded
        verify(passwordResetTemplateResource).getInputStream();
        verify(verificationTemplateResource).getInputStream();
    }

    @Test
    @DisplayName("@PostConstruct should log WARN when fromEmail is placeholder")
    void postConstructShouldLogWarnWhenFromEmailIsPlaceholder() throws Exception {
        // Given
        emailService = new AwsSesEmailService(sesClient, meterRegistry);
        injectTemplateResources();
        injectProperty("fromEmail", "noreply@example.com");
        
        // When
        emailService.initialize();
        
        // Then
        List<String> warnMessages = getLogMessages(Level.WARN);
        assertThat(warnMessages).hasSize(1);
        assertThat(warnMessages.get(0)).contains("fromEmail is not configured or using default placeholder");
        
        // Verify templates were still loaded
        verify(passwordResetTemplateResource).getInputStream();
        verify(verificationTemplateResource).getInputStream();
    }

    @Test
    @DisplayName("@PostConstruct should log INFO when fromEmail is properly configured")
    void postConstructShouldLogInfoWhenFromEmailIsProperlyConfigured() throws Exception {
        // Given
        emailService = new AwsSesEmailService(sesClient, meterRegistry);
        injectTemplateResources();
        injectProperty("fromEmail", "sender@verified-domain.com");
        
        // When
        emailService.initialize();
        
        // Then
        List<String> infoMessages = getLogMessages(Level.INFO);
        assertThat(infoMessages).hasSize(2); // Constructor + initialization
        assertThat(infoMessages.get(1)).contains("AWS SES Email Service initialized with sender: sender@verified-domain.com");
        assertThat(infoMessages.get(1)).contains("Region: configured via AWS SDK");
        assertThat(infoMessages.get(1)).contains("Retry mode: Standard");
        
        // Verify no WARN messages about fromEmail
        List<String> warnMessages = getLogMessages(Level.WARN);
        assertThat(warnMessages).isEmpty();
        
        // Verify templates were loaded
        verify(passwordResetTemplateResource).getInputStream();
        verify(verificationTemplateResource).getInputStream();
    }

    // ========== Template Loading Tests ==========

    @Test
    @DisplayName("@PostConstruct should load both templates during initialization")
    void postConstructShouldLoadBothTemplatesDuringInitialization() throws Exception {
        // Given
        emailService = new AwsSesEmailService(sesClient, meterRegistry);
        injectTemplateResources();
        injectProperty("fromEmail", "sender@example.com");
        
        // When
        emailService.initialize();
        
        // Then
        verify(passwordResetTemplateResource, times(1)).getInputStream();
        verify(verificationTemplateResource, times(1)).getInputStream();
        
        // Verify both templates are loaded during a single initialization
        assertThat(getLogMessages(Level.INFO)).hasSize(2); // Constructor + initialization
    }

    @Test
    @DisplayName("@PostConstruct should load templates in correct order")
    void postConstructShouldLoadTemplatesInCorrectOrder() throws Exception {
        // Given
        emailService = new AwsSesEmailService(sesClient, meterRegistry);
        injectTemplateResources();
        injectProperty("fromEmail", "sender@example.com");
        
        // When
        emailService.initialize();
        
        // Then - verify order by checking that both templates are loaded
        verify(passwordResetTemplateResource).getInputStream();
        verify(verificationTemplateResource).getInputStream();
        
        // Both templates should be loaded during initialization
        assertThat(getLogMessages(Level.INFO)).hasSize(2); // Constructor + initialization
    }

    @Test
    @DisplayName("Template loading should handle empty templates during initialization")
    void templateLoadingShouldHandleEmptyTemplatesDuringInitialization() throws Exception {
        // Given - empty password reset template
        when(passwordResetTemplateResource.getInputStream())
            .thenReturn(new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8)));
        when(passwordResetTemplateResource.getDescription()).thenReturn("empty-password-reset-template");
        
        emailService = new AwsSesEmailService(sesClient, meterRegistry);
        injectTemplateResources();
        injectProperty("fromEmail", "sender@example.com");
        
        // When & Then - should throw during initialization
        try {
            emailService.initialize();
            assertThat(false).as("Should have thrown IllegalStateException").isTrue();
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).contains("Cannot load password reset email template");
            assertThat(e.getCause()).isInstanceOf(IllegalStateException.class);
            assertThat(e.getCause().getMessage()).contains("password reset template is empty");
        }
    }

    @Test
    @DisplayName("Template loading should handle missing template resources during initialization")
    void templateLoadingShouldHandleMissingTemplateResourcesDuringInitialization() throws Exception {
        // Given - null password reset template resource
        emailService = new AwsSesEmailService(sesClient, meterRegistry);
        
        var field = AwsSesEmailService.class.getDeclaredField("passwordResetTemplateResource");
        field.setAccessible(true);
        field.set(emailService, null);
        
        field = AwsSesEmailService.class.getDeclaredField("verificationTemplateResource");
        field.setAccessible(true);
        field.set(emailService, verificationTemplateResource);
        
        injectProperty("fromEmail", "sender@example.com");
        
        // When & Then - should throw during initialization
        try {
            emailService.initialize();
            assertThat(false).as("Should have thrown IllegalStateException").isTrue();
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).contains("Missing resource for password reset email template");
        }
    }

    @Test
    @DisplayName("Template loading should handle IOException during initialization")
    void templateLoadingShouldHandleIOExceptionDuringInitialization() throws Exception {
        // Given - IOException when reading password reset template
        when(passwordResetTemplateResource.getInputStream()).thenThrow(new java.io.IOException("I/O error"));
        when(passwordResetTemplateResource.getDescription()).thenReturn("error-password-reset-template");
        
        emailService = new AwsSesEmailService(sesClient, meterRegistry);
        injectTemplateResources();
        injectProperty("fromEmail", "sender@example.com");
        
        // When & Then - should throw during initialization
        try {
            emailService.initialize();
            assertThat(false).as("Should have thrown IllegalStateException").isTrue();
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).contains("Cannot load password reset email template");
            assertThat(e.getCause()).isInstanceOf(java.io.IOException.class);
            assertThat(e.getCause().getMessage()).contains("I/O error");
        }
    }

    // ========== Multiple Initialization Tests ==========

    @Test
    @DisplayName("Multiple initialize() calls should reload templates each time")
    void multipleInitializeCallsShouldReloadTemplatesEachTime() throws Exception {
        // Given - fresh mocks for this test
        Resource freshPasswordResetResource = mock(Resource.class);
        Resource freshVerificationResource = mock(Resource.class);
        
        when(freshPasswordResetResource.getInputStream())
            .thenAnswer(invocation -> new ByteArrayInputStream(PASSWORD_RESET_TEMPLATE.getBytes(StandardCharsets.UTF_8)));
        lenient().when(freshPasswordResetResource.getDescription()).thenReturn("password-reset-template");
        
        when(freshVerificationResource.getInputStream())
            .thenAnswer(invocation -> new ByteArrayInputStream(VERIFICATION_TEMPLATE.getBytes(StandardCharsets.UTF_8)));
        lenient().when(freshVerificationResource.getDescription()).thenReturn("verification-template");
        
        emailService = new AwsSesEmailService(sesClient, meterRegistry);
        
        var field = AwsSesEmailService.class.getDeclaredField("passwordResetTemplateResource");
        field.setAccessible(true);
        field.set(emailService, freshPasswordResetResource);
        
        field = AwsSesEmailService.class.getDeclaredField("verificationTemplateResource");
        field.setAccessible(true);
        field.set(emailService, freshVerificationResource);
        
        injectProperty("fromEmail", "sender@example.com");
        
        // When - call initialize multiple times
        emailService.initialize();
        emailService.initialize();
        emailService.initialize();
        
        // Then - templates should be loaded each time initialize() is called
        verify(freshPasswordResetResource, times(3)).getInputStream();
        verify(freshVerificationResource, times(3)).getInputStream();
    }

    @Test
    @DisplayName("Constructor should log INFO about service creation")
    void constructorShouldLogInfoAboutServiceCreation() throws Exception {
        // Given & When
        emailService = new AwsSesEmailService(sesClient, meterRegistry);
        
        // Then
        List<String> infoMessages = getLogMessages(Level.INFO);
        assertThat(infoMessages).hasSize(1);
        assertThat(infoMessages.get(0)).contains("AWS SES Email Service created (configuration will be injected)");
    }

    // ========== Helper Methods ==========

    private void injectTemplateResources() throws Exception {
        var field = AwsSesEmailService.class.getDeclaredField("passwordResetTemplateResource");
        field.setAccessible(true);
        field.set(emailService, passwordResetTemplateResource);
        
        field = AwsSesEmailService.class.getDeclaredField("verificationTemplateResource");
        field.setAccessible(true);
        field.set(emailService, verificationTemplateResource);
    }

    private void injectProperty(String fieldName, Object value) throws Exception {
        var field = AwsSesEmailService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(emailService, value);
    }

    private List<String> getLogMessages(Level level) {
        return listAppender.list.stream()
            .filter(event -> event.getLevel() == level)
            .map(ILoggingEvent::getFormattedMessage)
            .collect(Collectors.toList());
    }
}
