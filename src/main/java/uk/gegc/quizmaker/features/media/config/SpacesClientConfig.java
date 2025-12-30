package uk.gegc.quizmaker.features.media.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
public class SpacesClientConfig {

    @Bean
    public S3Configuration spacesS3Configuration() {
        return S3Configuration.builder()
                // Path-style avoids double-bucket hosts when using region endpoints
                .pathStyleAccessEnabled(true)
                .build();
    }

    @Bean
    public S3Client spacesS3Client(MediaStorageProperties properties, S3Configuration spacesS3Configuration) {
        return S3Client.builder()
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(credentials(properties))
                .endpointOverride(properties.getEndpoint())
                .serviceConfiguration(spacesS3Configuration)
                .build();
    }

    @Bean
    public S3Presigner spacesPresigner(MediaStorageProperties properties, S3Configuration spacesS3Configuration) {
        return S3Presigner.builder()
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(credentials(properties))
                .endpointOverride(URI.create(properties.getEndpoint().toString()))
                .serviceConfiguration(spacesS3Configuration)
                .build();
    }

    private AwsCredentialsProvider credentials(MediaStorageProperties properties) {
        AwsBasicCredentials creds = AwsBasicCredentials.create(
                properties.getAccessKey(),
                properties.getSecretKey()
        );
        return StaticCredentialsProvider.create(creds);
    }
}
