package uk.gegc.quizmaker.shared.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;

import java.time.Duration;

/**
 * AWS SES configuration for email delivery via SESv2 API.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "app.email.provider", havingValue = "ses")
public class AwsSesConfig {

    @Value("${app.email.region:us-east-1}")
    private String awsRegion;

    @Value("${AWS_ACCESS_KEY_ID:}")
    private String awsAccessKeyId;

    @Value("${AWS_SECRET_ACCESS_KEY:}")
    private String awsSecretAccessKey;

    @Value("${app.email.ses.api-call-timeout-ms:30000}")
    private long apiCallTimeoutMs;

    @Value("${app.email.ses.api-call-attempt-timeout-ms:10000}")
    private long apiCallAttemptTimeoutMs;

    @Value("${app.email.ses.http-connection-timeout-ms:5000}")
    private int httpConnectionTimeoutMs;

    @Value("${app.email.ses.http-socket-timeout-ms:15000}")
    private int httpSocketTimeoutMs;

    @Bean(destroyMethod = "close")
    public SesV2Client sesV2Client() {
        log.info("Creating AWS SES v2 client for region: {}", awsRegion);

        try {
            SesV2Client client = SesV2Client.builder()
                    .region(Region.of(awsRegion))
                    .credentialsProvider(resolveCredentialsProvider())
                    .overrideConfiguration(buildClientOverrideConfiguration())
                    .httpClientBuilder(buildHttpClientBuilder())
                    .build();

            log.info("AWS SES v2 client created successfully");
            return client;

        } catch (Exception e) {
            log.error("Failed to create AWS SES v2 client. Ensure AWS credentials are configured.", e);
            throw new IllegalStateException("Cannot initialize AWS SES client. Check credentials and region configuration.", e);
        }
    }

    private AwsCredentialsProvider resolveCredentialsProvider() {
        boolean hasStaticCredentials = awsAccessKeyId != null && !awsAccessKeyId.isBlank()
                && awsSecretAccessKey != null && !awsSecretAccessKey.isBlank();

        if (hasStaticCredentials) {
            log.info("Using static AWS credentials from environment/application properties for SES client");
            AwsBasicCredentials credentials = AwsBasicCredentials.create(awsAccessKeyId, awsSecretAccessKey);
            return StaticCredentialsProvider.create(credentials);
        }

        log.info("Using AWS default credentials provider chain for SES client");
        return DefaultCredentialsProvider.create();
    }

    private ClientOverrideConfiguration buildClientOverrideConfiguration() {
        return ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofMillis(Math.max(apiCallTimeoutMs, 1000)))
                .apiCallAttemptTimeout(Duration.ofMillis(Math.max(apiCallAttemptTimeoutMs, 1000)))
                .build();
    }

    private ApacheHttpClient.Builder buildHttpClientBuilder() {
        return ApacheHttpClient.builder()
                .connectionTimeout(Duration.ofMillis(Math.max(httpConnectionTimeoutMs, 500)))
                .socketTimeout(Duration.ofMillis(Math.max(httpSocketTimeoutMs, 1000)));
    }
}
