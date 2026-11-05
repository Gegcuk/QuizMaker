package uk.gegc.quizmaker.features.media.infra.storage;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.utils.http.SdkHttpUtils;
import uk.gegc.quizmaker.features.media.application.SavedImageStorage;
import uk.gegc.quizmaker.features.media.config.MediaStorageProperties;
import uk.gegc.quizmaker.features.media.domain.model.SavedImageException;
import uk.gegc.quizmaker.features.media.domain.model.SavedImageVersion;

import java.util.Map;

import static uk.gegc.quizmaker.features.media.domain.model.SavedImageException.Reason.*;

@Component
public class S3SavedImageStorage implements SavedImageStorage {
    private final S3Client client;
    private final MediaStorageProperties properties;
    public S3SavedImageStorage(S3Client client, MediaStorageProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override public Source inspect(String key) {
        outsideTransaction();
        try {
            var head = head(key);
            if (head.eTag() == null || head.eTag().isBlank() || head.eTag().length() > 255
                    || head.contentLength() == null || head.contentLength() <= 0
                    || head.contentType() == null) throw new SavedImageException(UNAVAILABLE);
            return new Source(head.eTag(), head.contentLength(), head.contentType());
        } catch (RuntimeException failure) { throw sanitize(failure); }
    }

    @Override public void retain(SavedImageVersion version) {
        outsideTransaction();
        try {
            try {
                validate(version, head(version.getObjectKey()));
                return; // Includes response loss / restart after an already successful copy.
            } catch (S3Exception missing) {
                if (missing.statusCode() != 404) throw missing;
            }
            client.copyObject(CopyObjectRequest.builder().bucket(properties.getBucket())
                    .key(version.getObjectKey())
                    .copySource(SdkHttpUtils.urlEncodeIgnoreSlashes(properties.getBucket() + "/" + version.getSourceKey()))
                    .copySourceIfMatch(version.getSourceEtag())
                    .metadataDirective(MetadataDirective.REPLACE)
                    .metadata(Map.of("retained-version", version.getId().toString()))
                    .contentType(version.getMimeType()).acl(ObjectCannedACL.PUBLIC_READ).build());
            validate(version, head(version.getObjectKey()));
        } catch (RuntimeException failure) { throw sanitize(failure); }
    }

    @Override public void verify(SavedImageVersion version) {
        outsideTransaction();
        try { validate(version, head(version.getObjectKey())); }
        catch (RuntimeException failure) { throw sanitize(failure); }
    }

    private HeadObjectResponse head(String key) {
        return client.headObject(HeadObjectRequest.builder().bucket(properties.getBucket()).key(key).build());
    }

    private void validate(SavedImageVersion version, HeadObjectResponse head) {
        if (!version.getId().toString().equals(head.metadata().get("retained-version"))
                || head.contentLength() == null || head.contentLength() != version.getSizeBytes()
                || !version.getMimeType().equalsIgnoreCase(head.contentType())) throw new SavedImageException(CONFLICT);
    }

    private static SavedImageException sanitize(RuntimeException failure) {
        if (failure instanceof SavedImageException known) return known;
        return new SavedImageException(failure instanceof S3Exception s3 && s3.statusCode() == 412 ? CONFLICT : UNAVAILABLE);
    }

    private static void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new SavedImageException(CONFLICT);
    }
}
