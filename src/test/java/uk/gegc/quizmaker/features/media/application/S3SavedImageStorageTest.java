package uk.gegc.quizmaker.features.media.application;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import uk.gegc.quizmaker.features.media.config.MediaStorageProperties;
import uk.gegc.quizmaker.features.media.domain.model.*;
import uk.gegc.quizmaker.features.media.infra.storage.S3SavedImageStorage;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class S3SavedImageStorageTest {
    final S3Client client = mock(S3Client.class);
    final S3SavedImageStorage storage = new S3SavedImageStorage(client, new MediaStorageProperties());
    final SavedImageVersion version = new SavedImageVersion(UUID.randomUUID(), "source/image with space.png", "\"etag-A\"",
            "image/png", 42, 640, 480, Instant.parse("2026-10-23T12:00:00Z"));

    @Test void copiesOnlyTheCapturedSourceAndChecksTheDurableDestination() {
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(missing()).thenReturn(retained());
        storage.retain(version);
        var argument = org.mockito.ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(client).copyObject(argument.capture());
        var request = argument.getValue();
        assertThat(request.copySourceIfMatch()).isEqualTo("\"etag-A\"");
        assertThat(request.key()).isEqualTo(version.getObjectKey()).startsWith("retained-images/");
        assertThat(request.copySource()).endsWith("source/image%20with%20space.png");
        assertThat(request.metadata()).containsEntry("retained-version", version.getId().toString());
        verify(client, times(2)).headObject(any(HeadObjectRequest.class));
    }

    @Test void reconcilesAnAlreadyCopiedObjectWithoutRepeatingTheCopy() {
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(retained());
        storage.retain(version);
        verify(client, never()).copyObject(any(CopyObjectRequest.class));
    }

    @Test void unknownDestinationOutcomeDoesNotAuthorizeAnotherCopy() {
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(503).message("private provider detail").build());
        assertThatThrownBy(() -> storage.retain(version)).isInstanceOf(SavedImageException.class)
                .hasMessage("The exact saved image is temporarily unavailable").hasNoCause();
        verify(client, never()).copyObject(any(CopyObjectRequest.class));
    }

    @Test void changedSourceCannotBecomeTheCapturedVersion() {
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(missing());
        when(client.copyObject(any(CopyObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(412).build());
        assertThatThrownBy(() -> storage.retain(version)).isInstanceOfSatisfying(SavedImageException.class,
                e -> assertThat(e.reason()).isEqualTo(SavedImageException.Reason.CONFLICT));
        verify(client, times(1)).copyObject(any(CopyObjectRequest.class));
    }

    @Test void mismatchedRetainedIdentityNeverGetsOverwritten() {
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(retained().toBuilder()
                .metadata(Map.of("retained-version", UUID.randomUUID().toString())).build());
        assertThatThrownBy(() -> storage.retain(version)).isInstanceOf(SavedImageException.class);
        verify(client, never()).copyObject(any(CopyObjectRequest.class));
    }

    @Test void providerWorkIsRejectedBeforeAnyCallInsideDatabaseTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThatThrownBy(() -> storage.retain(version)).isInstanceOf(SavedImageException.class); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        verifyNoInteractions(client);
    }

    @Test void sourceMetadataIsTrustedOnlyWhenCompleteAndBounded() {
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().eTag("\"etag-A\"")
                .contentLength(42L).contentType("image/png").build());
        assertThat(storage.inspect("source/image.png")).isEqualTo(new SavedImageStorage.Source("\"etag-A\"", 42, "image/png"));
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().contentLength(42L).build());
        assertThatThrownBy(() -> storage.inspect("source/image.png")).isInstanceOf(SavedImageException.class);
    }

    private S3Exception missing() { var builder = S3Exception.builder(); builder.statusCode(404); return (S3Exception) builder.build(); }
    private HeadObjectResponse retained() {
        return HeadObjectResponse.builder().contentLength(42L).contentType("image/png")
                .metadata(Map.of("retained-version", version.getId().toString())).build();
    }
}
