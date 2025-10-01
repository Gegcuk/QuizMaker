package uk.gegc.quizmaker.shared.email.impl;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AwsSesEmailService Request Builder Tests")
class AwsSesEmailServiceRequestBuilderTest {

    @Mock
    private SesV2Client sesClient;
    @Mock
    private Resource passwordResetTemplateResource;
    @Mock
    private Resource verificationTemplateResource;
    
    private SimpleMeterRegistry meterRegistry;
    private AwsSesEmailService emailService;

    private static final String PASSWORD_RESET_TEMPLATE = "Reset: %s | Expires: %s";
    private static final String VERIFICATION_TEMPLATE = "Verify: %s | Expires: %s";

    @BeforeEach
    void setUp() throws Exception {
        meterRegistry = new SimpleMeterRegistry();
        emailService = new AwsSesEmailService(sesClient, meterRegistry);
        
        lenient().when(passwordResetTemplateResource.getInputStream())
            .thenReturn(new ByteArrayInputStream(PASSWORD_RESET_TEMPLATE.getBytes(StandardCharsets.UTF_8)));
        lenient().when(passwordResetTemplateResource.getDescription()).thenReturn("password-reset-template");
        
        lenient().when(verificationTemplateResource.getInputStream())
            .thenReturn(new ByteArrayInputStream(VERIFICATION_TEMPLATE.getBytes(StandardCharsets.UTF_8)));
        lenient().when(verificationTemplateResource.getDescription()).thenReturn("verification-template");
        
        injectTemplateResources();
        emailService.initialize();
    }

    private void injectTemplateResources() throws Exception {
        var field = AwsSesEmailService.class.getDeclaredField("passwordResetTemplateResource");
        field.setAccessible(true);
        field.set(emailService, passwordResetTemplateResource);
        
        field = AwsSesEmailService.class.getDeclaredField("verificationTemplateResource");
        field.setAccessible(true);
        field.set(emailService, verificationTemplateResource);
    }

    @Test
    @DisplayName("fromEmail should be applied to SendEmailRequest.fromEmailAddress")
    void fromEmailShouldBeAppliedToFromEmailAddress() throws Exception {
        // Given
        String fromEmail = "noreply@quizmaker.com";
        injectProperty("fromEmail", fromEmail);
        injectProperty("baseUrl", "http://localhost:3000");
        injectProperty("passwordResetSubject", "Reset");
        injectProperty("resetTokenTtlMinutes", 60L);
        mockSesResponse();
        
        // When
        emailService.sendPasswordResetEmail("user@example.com", "token");
        
        // Then
        ArgumentCaptor<SendEmailRequest> captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(captor.capture());
        assertThat(captor.getValue().fromEmailAddress()).isEqualTo(fromEmail);
    }

    @Test
    @DisplayName("Destination should have exactly one toAddresses item")
    void destinationShouldHaveExactlyOneToAddress() throws Exception {
        // Given
        String recipient = "user@example.com";
        setupBasicProperties();
        mockSesResponse();
        
        // When
        emailService.sendPasswordResetEmail(recipient, "token");
        
        // Then
        ArgumentCaptor<SendEmailRequest> captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(captor.capture());
        assertThat(captor.getValue().destination().toAddresses()).containsExactly(recipient);
    }

    @Test
    @DisplayName("Subject should equal configured subject")
    void subjectShouldEqualConfiguredSubject() throws Exception {
        // Given
        String subject = "Reset Your Password Now";
        setupBasicProperties();
        injectProperty("passwordResetSubject", subject);
        mockSesResponse();
        
        // When
        emailService.sendPasswordResetEmail("user@example.com", "token");
        
        // Then
        ArgumentCaptor<SendEmailRequest> captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(captor.capture());
        assertThat(captor.getValue().content().simple().subject().data()).isEqualTo(subject);
    }

    @Test
    @DisplayName("Body.Text should equal formatted template content")
    void bodyTextShouldEqualFormattedTemplateContent() throws Exception {
        // Given
        setupBasicProperties();
        mockSesResponse();
        
        // When
        emailService.sendPasswordResetEmail("user@example.com", "token123");
        
        // Then
        ArgumentCaptor<SendEmailRequest> captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(captor.capture());
        String bodyText = captor.getValue().content().simple().body().text().data();
        assertThat(bodyText).contains("http://localhost:3000/reset-password?token=token123");
        assertThat(bodyText).contains("1 hour");
    }

    @Test
    @DisplayName("configurationSetName should be applied when non-blank")
    void configurationSetNameShouldBeAppliedWhenNonBlank() throws Exception {
        // Given
        String configSet = "my-config-set";
        setupBasicProperties();
        injectProperty("configurationSetName", configSet);
        mockSesResponse();
        
        // When
        emailService.sendPasswordResetEmail("user@example.com", "token");
        
        // Then
        ArgumentCaptor<SendEmailRequest> captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(captor.capture());
        assertThat(captor.getValue().configurationSetName()).isEqualTo(configSet);
    }

    @Test
    @DisplayName("configurationSetName should NOT be applied when null")
    void configurationSetNameShouldNotBeAppliedWhenNull() throws Exception {
        // Given
        setupBasicProperties();
        injectProperty("configurationSetName", null);
        mockSesResponse();
        
        // When
        emailService.sendPasswordResetEmail("user@example.com", "token");
        
        // Then
        ArgumentCaptor<SendEmailRequest> captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(captor.capture());
        assertThat(captor.getValue().configurationSetName()).isNull();
    }

    @Test
    @DisplayName("configurationSetName should NOT be applied when empty")
    void configurationSetNameShouldNotBeAppliedWhenEmpty() throws Exception {
        // Given
        setupBasicProperties();
        injectProperty("configurationSetName", "");
        mockSesResponse();
        
        // When
        emailService.sendPasswordResetEmail("user@example.com", "token");
        
        // Then
        ArgumentCaptor<SendEmailRequest> captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(captor.capture());
        assertThat(captor.getValue().configurationSetName()).isNull();
    }

    @Test
    @DisplayName("configurationSetName should NOT be applied when blank")
    void configurationSetNameShouldNotBeAppliedWhenBlank() throws Exception {
        // Given
        setupBasicProperties();
        injectProperty("configurationSetName", "   ");
        mockSesResponse();
        
        // When
        emailService.sendPasswordResetEmail("user@example.com", "token");
        
        // Then
        ArgumentCaptor<SendEmailRequest> captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesClient).sendEmail(captor.capture());
        assertThat(captor.getValue().configurationSetName()).isNull();
    }

    private void setupBasicProperties() throws Exception {
        injectProperty("fromEmail", "from@test.com");
        injectProperty("baseUrl", "http://localhost:3000");
        injectProperty("passwordResetSubject", "Reset Password");
        injectProperty("verificationSubject", "Verify Email");
        injectProperty("resetTokenTtlMinutes", 60L);
        injectProperty("verificationTokenTtlMinutes", 120L);
    }

    private void injectProperty(String fieldName, Object value) throws Exception {
        var field = AwsSesEmailService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(emailService, value);
    }

    private void mockSesResponse() {
        var mockResponse = SendEmailResponse.builder()
            .messageId("test-message-id-" + System.nanoTime())
            .build();
        lenient().when(sesClient.sendEmail(any(SendEmailRequest.class)))
            .thenReturn(mockResponse);
    }
}

