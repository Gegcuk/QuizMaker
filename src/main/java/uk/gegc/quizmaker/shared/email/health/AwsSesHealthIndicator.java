package uk.gegc.quizmaker.shared.email.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.GetAccountRequest;
import software.amazon.awssdk.services.sesv2.model.GetAccountResponse;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

/**
 * Health indicator that validates AWS SES availability when SES is the active email provider.
 */
@Component
@ConditionalOnBean(SesV2Client.class)
@ConditionalOnProperty(name = "app.email.provider", havingValue = "ses")
public class AwsSesHealthIndicator implements HealthIndicator {

    private final SesV2Client sesV2Client;

    public AwsSesHealthIndicator(SesV2Client sesV2Client) {
        this.sesV2Client = sesV2Client;
    }

    @Override
    public Health health() {
        try {
            GetAccountResponse response = sesV2Client.getAccount(GetAccountRequest.builder().build());

            Health.Builder builder = Health.up()
                    .withDetail("enforcementStatus", response.enforcementStatus() != null ? response.enforcementStatus().toString() : "UNKNOWN");

            if (response.sendQuota() != null) {
                builder.withDetail("maxSendRate", response.sendQuota().maxSendRate())
                        .withDetail("max24HourSend", response.sendQuota().max24HourSend())
                        .withDetail("sentLast24Hours", response.sendQuota().sentLast24Hours());
            }

            return builder.build();
        } catch (SesV2Exception ex) {
            return Health.down(ex)
                    .withDetail("statusCode", ex.statusCode())
                    .withDetail("awsError", ex.awsErrorDetails() != null ? ex.awsErrorDetails().errorMessage() : "unknown")
                    .build();
        } catch (Exception ex) {
            return Health.down(ex).build();
        }
    }
}
