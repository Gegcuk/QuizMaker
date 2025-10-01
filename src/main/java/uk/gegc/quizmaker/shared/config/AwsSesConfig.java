package uk.gegc.quizmaker.shared.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;

/**
 * AWS SES configuration for email delivery via SESv2 API.
 * 
 * This configuration:
 * - Creates SesV2Client only when app.email.provider=ses
 * - Uses AWS Default Credentials Chain (respects env vars, IAM roles, profiles)
 * - Configures the AWS region for SES operations
 * 
 * Required configuration:
 * - app.email.provider=ses
 * - app.email.region (defaults to us-east-1)
 * - AWS credentials via environment variables or IAM role:
 *   - AWS_ACCESS_KEY_ID
 *   - AWS_SECRET_ACCESS_KEY
 *   - AWS_REGION (optional, overridden by app.email.region)
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "app.email.provider", havingValue = "ses")
public class AwsSesConfig {

    @Value("${app.email.region:us-east-1}")
    private String awsRegion;
    
    @Value("${AWS_ACCESS_KEY_ID}")
    private String awsAccessKeyId;
    
    @Value("${AWS_SECRET_ACCESS_KEY}")
    private String awsSecretAccessKey;

    /**
     * Creates the AWS SES v2 client using credentials from Spring properties.
     * 
     * This implementation uses StaticCredentialsProvider with credentials injected
     * from Spring properties (loaded from .env file via spring.config.import).
     * 
     * Retry behavior: AWS SDK v2 uses Standard retry mode by default (up to 3 attempts
     * with exponential backoff and jitter). Configure via AWS_RETRY_MODE and AWS_MAX_ATTEMPTS
     * environment variables if custom behavior is needed.
     * 
     * @return configured SesV2Client (automatically closed on shutdown)
     */
    @Bean(destroyMethod = "close")
    public SesV2Client sesV2Client() {
        log.info("Creating AWS SES v2 client for region: {}", awsRegion);
        
        try {
            // Create AWS credentials from Spring properties
            AwsBasicCredentials awsCredentials = AwsBasicCredentials.create(awsAccessKeyId, awsSecretAccessKey);
            StaticCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(awsCredentials);
            
            SesV2Client client = SesV2Client.builder()
                    .region(Region.of(awsRegion))
                    .credentialsProvider(credentialsProvider)
                    .build();
            
            log.info("AWS SES v2 client created successfully with credentials from Spring properties");
            return client;
            
        } catch (Exception e) {
            log.error("Failed to create AWS SES v2 client. Ensure AWS credentials are configured.", e);
            throw new IllegalStateException("Cannot initialize AWS SES client. Check credentials and region configuration.", e);
        }
    }
}
