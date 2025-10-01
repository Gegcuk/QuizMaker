package uk.gegc.quizmaker.shared.email.health;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for AWS SES Health Indicator.
 * 
 * These tests verify that the health indicator correctly reports SES service health
 * by calling the SES getAccount API and interpreting the response or exceptions.
 * 
 * Tests cover:
 * - UP state: successful getAccount call with quota details
 * - DOWN state: SES exception with status code and error details
 * - DOWN state: generic exception without SES-specific details
 * - Edge cases: missing sendQuota, missing enforcementStatus
 * 
 * Uses Mockito to simulate SES client responses without Spring context overhead.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AWS SES Health Indicator Tests")
class AwsSesHealthIndicatorTest {

    @Mock
    private SesV2Client sesV2Client;

    private AwsSesHealthIndicator healthIndicator;

    @BeforeEach
    void setUp() {
        healthIndicator = new AwsSesHealthIndicator(sesV2Client);
    }

    // ========== UP State Tests ==========

    @Test
    @DisplayName("health: when SES getAccount succeeds then status UP with quota details")
    void health_whenSesGetAccountSucceeds_thenStatusUpWithQuotaDetails() {
        // Given
        SendQuota sendQuota = SendQuota.builder()
            .maxSendRate(14.0)
            .max24HourSend(50000.0)
            .sentLast24Hours(1234.0)
            .build();

        GetAccountResponse mockResponse = GetAccountResponse.builder()
            .enforcementStatus("HEALTHY")
            .sendQuota(sendQuota)
            .build();

        when(sesV2Client.getAccount(any(GetAccountRequest.class)))
            .thenReturn(mockResponse);

        // When
        Health health = healthIndicator.health();

        // Then
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
            .containsEntry("enforcementStatus", "HEALTHY")
            .containsEntry("maxSendRate", 14.0)
            .containsEntry("max24HourSend", 50000.0)
            .containsEntry("sentLast24Hours", 1234.0);
    }

    @Test
    @DisplayName("health: when SES getAccount succeeds with null enforcementStatus then defaults to UNKNOWN")
    void health_whenSesGetAccountSucceedsWithNullEnforcementStatus_thenDefaultsToUnknown() {
        // Given
        SendQuota sendQuota = SendQuota.builder()
            .maxSendRate(10.0)
            .max24HourSend(40000.0)
            .sentLast24Hours(500.0)
            .build();

        GetAccountResponse mockResponse = GetAccountResponse.builder()
            .enforcementStatus((String) null)
            .sendQuota(sendQuota)
            .build();

        when(sesV2Client.getAccount(any(GetAccountRequest.class)))
            .thenReturn(mockResponse);

        // When
        Health health = healthIndicator.health();

        // Then
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
            .containsEntry("enforcementStatus", "UNKNOWN")
            .containsEntry("maxSendRate", 10.0)
            .containsEntry("max24HourSend", 40000.0)
            .containsEntry("sentLast24Hours", 500.0);
    }

    @Test
    @DisplayName("health: when SES getAccount succeeds with null sendQuota then status UP without quota details")
    void health_whenSesGetAccountSucceedsWithNullSendQuota_thenStatusUpWithoutQuotaDetails() {
        // Given
        GetAccountResponse mockResponse = GetAccountResponse.builder()
            .enforcementStatus("HEALTHY")
            .sendQuota((SendQuota) null)
            .build();

        when(sesV2Client.getAccount(any(GetAccountRequest.class)))
            .thenReturn(mockResponse);

        // When
        Health health = healthIndicator.health();

        // Then
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
            .containsEntry("enforcementStatus", "HEALTHY")
            .doesNotContainKeys("maxSendRate", "max24HourSend", "sentLast24Hours");
    }

    @Test
    @DisplayName("health: when SES account in SUSPENDED status then still reports UP with details")
    void health_whenSesAccountSuspended_thenStillReportsUpWithDetails() {
        // Given
        SendQuota sendQuota = SendQuota.builder()
            .maxSendRate(0.0)
            .max24HourSend(0.0)
            .sentLast24Hours(100.0)
            .build();

        GetAccountResponse mockResponse = GetAccountResponse.builder()
            .enforcementStatus("SUSPENDED")
            .sendQuota(sendQuota)
            .build();

        when(sesV2Client.getAccount(any(GetAccountRequest.class)))
            .thenReturn(mockResponse);

        // When
        Health health = healthIndicator.health();

        // Then - still UP because API call succeeded, but status shows suspension
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
            .containsEntry("enforcementStatus", "SUSPENDED")
            .containsEntry("maxSendRate", 0.0)
            .containsEntry("max24HourSend", 0.0);
    }

    // ========== DOWN State (SES Exception) Tests ==========

    @Test
    @DisplayName("health: when SES throws 403 exception then status DOWN with error details")
    void health_whenSesThrows403Exception_thenStatusDownWithErrorDetails() {
        // Given
        SesV2Exception sesException = (SesV2Exception) SesV2Exception.builder()
            .statusCode(403)
            .message("Access Denied")
            .awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                .errorMessage("User not authorized to perform: ses:GetAccount")
                .errorCode("AccessDeniedException")
                .build())
            .build();

        when(sesV2Client.getAccount(any(GetAccountRequest.class)))
            .thenThrow(sesException);

        // When
        Health health = healthIndicator.health();

        // Then
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
            .containsEntry("statusCode", 403)
            .containsEntry("awsError", "User not authorized to perform: ses:GetAccount");
        assertThat(health.getDetails()).containsKey("error");
    }

    @Test
    @DisplayName("health: when SES throws 400 exception then status DOWN with error details")
    void health_whenSesThrows400Exception_thenStatusDownWithErrorDetails() {
        // Given
        SesV2Exception sesException = (SesV2Exception) SesV2Exception.builder()
            .statusCode(400)
            .message("Bad Request")
            .awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                .errorMessage("Invalid request parameters")
                .errorCode("BadRequestException")
                .build())
            .build();

        when(sesV2Client.getAccount(any(GetAccountRequest.class)))
            .thenThrow(sesException);

        // When
        Health health = healthIndicator.health();

        // Then
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
            .containsEntry("statusCode", 400)
            .containsEntry("awsError", "Invalid request parameters");
    }

    @Test
    @DisplayName("health: when SES throws 500 exception then status DOWN with error details")
    void health_whenSesThrows500Exception_thenStatusDownWithErrorDetails() {
        // Given
        SesV2Exception sesException = (SesV2Exception) SesV2Exception.builder()
            .statusCode(500)
            .message("Internal Server Error")
            .awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                .errorMessage("SES internal error")
                .errorCode("InternalServerError")
                .build())
            .build();

        when(sesV2Client.getAccount(any(GetAccountRequest.class)))
            .thenThrow(sesException);

        // When
        Health health = healthIndicator.health();

        // Then
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
            .containsEntry("statusCode", 500)
            .containsEntry("awsError", "SES internal error");
    }

    @Test
    @DisplayName("health: when SES exception has no awsErrorDetails then error defaults to unknown")
    void health_whenSesExceptionHasNoAwsErrorDetails_thenErrorDefaultsToUnknown() {
        // Given
        SesV2Exception sesException = (SesV2Exception) SesV2Exception.builder()
            .statusCode(503)
            .message("Service Unavailable")
            .awsErrorDetails(null)
            .build();

        when(sesV2Client.getAccount(any(GetAccountRequest.class)))
            .thenThrow(sesException);

        // When
        Health health = healthIndicator.health();

        // Then
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
            .containsEntry("statusCode", 503)
            .containsEntry("awsError", "unknown");
    }

    // ========== DOWN State (Generic Exception) Tests ==========

    @Test
    @DisplayName("health: when generic RuntimeException then status DOWN with error")
    void health_whenGenericRuntimeException_thenStatusDownWithError() {
        // Given
        RuntimeException genericException = new RuntimeException("Network connection failed");

        when(sesV2Client.getAccount(any(GetAccountRequest.class)))
            .thenThrow(genericException);

        // When
        Health health = healthIndicator.health();

        // Then
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
        assertThat(health.getDetails().get("error").toString())
            .contains("Network connection failed");
    }

    @Test
    @DisplayName("health: when NullPointerException then status DOWN with error")
    void health_whenNullPointerException_thenStatusDownWithError() {
        // Given
        NullPointerException npe = new NullPointerException("Unexpected null value");

        when(sesV2Client.getAccount(any(GetAccountRequest.class)))
            .thenThrow(npe);

        // When
        Health health = healthIndicator.health();

        // Then
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
    }

    @Test
    @DisplayName("health: when IllegalStateException then status DOWN with error")
    void health_whenIllegalStateException_thenStatusDownWithError() {
        // Given
        IllegalStateException illegalState = new IllegalStateException("Client not properly configured");

        when(sesV2Client.getAccount(any(GetAccountRequest.class)))
            .thenThrow(illegalState);

        // When
        Health health = healthIndicator.health();

        // Then
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
        assertThat(health.getDetails().get("error").toString())
            .contains("Client not properly configured");
    }

    // ========== Edge Cases Tests ==========

    @Test
    @DisplayName("health: when sendQuota has zero values then still reports UP")
    void health_whenSendQuotaHasZeroValues_thenStillReportsUp() {
        // Given - all quota values at zero
        SendQuota sendQuota = SendQuota.builder()
            .maxSendRate(0.0)
            .max24HourSend(0.0)
            .sentLast24Hours(0.0)
            .build();

        GetAccountResponse mockResponse = GetAccountResponse.builder()
            .enforcementStatus("HEALTHY")
            .sendQuota(sendQuota)
            .build();

        when(sesV2Client.getAccount(any(GetAccountRequest.class)))
            .thenReturn(mockResponse);

        // When
        Health health = healthIndicator.health();

        // Then
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
            .containsEntry("maxSendRate", 0.0)
            .containsEntry("max24HourSend", 0.0)
            .containsEntry("sentLast24Hours", 0.0);
    }

    @Test
    @DisplayName("health: when enforcementStatus is PENDING then still reports UP")
    void health_whenEnforcementStatusPending_thenStillReportsUp() {
        // Given
        SendQuota sendQuota = SendQuota.builder()
            .maxSendRate(1.0)
            .max24HourSend(200.0)
            .sentLast24Hours(0.0)
            .build();

        GetAccountResponse mockResponse = GetAccountResponse.builder()
            .enforcementStatus("PENDING_VERIFICATION")
            .sendQuota(sendQuota)
            .build();

        when(sesV2Client.getAccount(any(GetAccountRequest.class)))
            .thenReturn(mockResponse);

        // When
        Health health = healthIndicator.health();

        // Then
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
            .containsEntry("enforcementStatus", "PENDING_VERIFICATION");
    }
}

