package uk.gegc.quizmaker.features.media.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.media.application.*;
import uk.gegc.quizmaker.features.media.config.MediaStorageProperties;
import uk.gegc.quizmaker.features.media.domain.model.*;
import uk.gegc.quizmaker.features.media.domain.repository.*;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.shared.dto.MediaRefDto;
import uk.gegc.quizmaker.shared.exception.*;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import java.time.Clock;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static uk.gegc.quizmaker.features.media.domain.model.SavedImageException.Reason.*;

@Service
public class RetainedImageServiceImpl implements RetainedImageService {
    private final MediaAssetRepository assets;
    private final SavedImageVersionRepository versions;
    private final SavedImageBindingRepository bindings;
    private final SavedImageStorage storage;
    private final AppPermissionEvaluator permissions;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final MediaStorageProperties properties;
    private final MeterRegistry metrics;
    private final EntityManagerFactory entityManagers;

    public RetainedImageServiceImpl(MediaAssetRepository assets, SavedImageVersionRepository versions,
                                   SavedImageBindingRepository bindings, SavedImageStorage storage,
                                   AppPermissionEvaluator permissions, Clock clock,
                                   PlatformTransactionManager manager, MediaStorageProperties properties, MeterRegistry metrics,
                                   EntityManagerFactory entityManagers) {
        this.assets = assets;
        this.versions = versions;
        this.bindings = bindings;
        this.storage = storage;
        this.permissions = permissions;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
        this.properties = properties;
        this.metrics = metrics;
        this.entityManagers = entityManagers;
    }

    @Override
    public void prepare(Collection<UUID> ids, String username) {
        observe("prepare", () -> prepareImages(ids, username));
    }

    @Override
    public void validateNewSelections(Collection<UUID> ids, String username) {
        var sorted = ordered(ids);
        if (sorted.isEmpty()) return;
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new SavedImageException(CONFLICT);
        if (username == null || username.isBlank()) throw new ForbiddenException("Authentication required");
        boolean administrator = permissions.hasAnyPermission(PermissionName.MEDIA_ADMIN,
                PermissionName.ARTICLE_ADMIN, PermissionName.SYSTEM_ADMIN);
        for (int i = 0; i < sorted.size(); i += 100) {
            var batch = sorted.subList(i, Math.min(i + 100, sorted.size()));
            var current = assets.findSelectionStatesForUpdate(batch);
            if (current.size() != batch.size()) throw new ValidationException("Image is unavailable for selection");
            for (var state : current) {
                if (!username.equals(state.getCreatedBy()) && !administrator)
                    throw new ForbiddenException("You cannot use this media asset");
                if (state.getType() != MediaAssetType.IMAGE || state.getStatus() != MediaAssetStatus.READY
                        || state.isLibraryRemoved() || state.getWidth() == null || state.getHeight() == null
                        || state.getWidth() <= 0 || state.getHeight() <= 0)
                    throw new ValidationException("Image is unavailable for selection");
            }
        }
    }

    private void prepareImages(Collection<UUID> ids, String username) {
        outsideTransaction();
        for (UUID id : ordered(ids)) {
            var source = freshTransaction(s -> { var a = asset(id, false); selectable(a, username); return a; });
            var version = freshTransaction(s -> versions.findByAssetId(id).orElse(null));
            if (version == null) {
                var head = storage.inspect(source.getKey());
                if (!source.getMimeType().equalsIgnoreCase(head.mimeType()) || head.size() <= 0
                        || head.size() > properties.getLimits().getMaxImageSizeBytes()) throw new SavedImageException(CONFLICT);
                version = freshTransaction(s -> {
                    var current = asset(id, true);
                    selectable(current, username);
                    if (!Objects.equals(current.getVersion(), source.getVersion())) throw new SavedImageException(CONFLICT);
                    return versions.findByAssetId(id).orElseGet(() -> versions.saveAndFlush(new SavedImageVersion(id,
                            source.getKey(), head.etag(), source.getMimeType(), head.size(),
                            source.getWidth(), source.getHeight(), clock.instant())));
                });
            }
            if (version.isReady()) storage.verify(version);
            else {
                metric("prepare", "pending");
                storage.retain(version);
            }
            UUID versionId = version.getId();
            freshWrite(s -> {
                selectable(asset(id, true), username); // Revocation/removal during storage work cannot authorize a binding.
                var prepared = versions.findById(versionId).orElseThrow(() -> new SavedImageException(CONFLICT));
                prepared.markReady();
                versions.saveAndFlush(prepared);
            });
        }
    }

    @Override
    public void prepareForQuestion(UUID questionId, Set<UUID> desired, Set<UUID> previous, String username) {
        Set<UUID> needed = new HashSet<>(desired);
        if (questionId != null) {
            var retained = TransactionSynchronizationManager.isActualTransactionActive()
                    ? versions.findAllByAssetIdInAndReadyTrue(previous)
                    : freshTransaction(s -> versions.findAllByAssetIdInAndReadyTrue(previous));
            retained.stream().map(SavedImageVersion::getAssetId).forEach(needed::remove);
        }
        // An unchanged legacy reference can adopt an already retained version without provider work.
        // bind() rechecks previous references from the freshly locked question before granting reuse.
        if (!needed.isEmpty()) prepare(needed, username);
    }

    @Override
    public void bind(UUID questionId, Set<UUID> desired, Set<UUID> previous, String username) {
        if (desired.isEmpty()) return;
        if (!TransactionSynchronizationManager.isActualTransactionActive() || questionId == null)
            throw new SavedImageException(CONFLICT);
        var existing = bindings.findAllByQuestionId(questionId).stream()
                .collect(Collectors.toMap(SavedImageBinding::getAssetId, Function.identity()));
        for (UUID id : ordered(desired)) {
            var asset = asset(id, true);
            // An old evidence row alone does not grant reuse after the author removed the reference.
            if (!previous.contains(id)) selectable(asset, username);
            var version = ready(id);
            var binding = existing.get(id);
            if (binding == null) bindings.saveAndFlush(new SavedImageBinding(questionId, version, clock.instant()));
            else if (!binding.getImageVersionId().equals(version.getId())) throw new SavedImageException(CONFLICT);
        }
    }

    @Override
    public Map<UUID, ImageReference> acquireForPublication(UUID questionId, Set<UUID> ids, Runnable authorize) {
        if (questionId == null || ids == null || authorize == null) throw new ValidationException("Invalid image binding request");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) discardReadCache();
        return transaction.execute(s -> {
            authorize.run();
            var owned = bindings.findAllByQuestionId(questionId).stream().filter(b -> ids.contains(b.getAssetId())).toList();
            if (owned.size() != ids.size()) throw unavailableBinding();
            var loaded = versions.findAllById(owned.stream().map(SavedImageBinding::getImageVersionId).toList()).stream()
                    .collect(Collectors.toMap(SavedImageVersion::getId, Function.identity()));
            Map<UUID, ImageReference> result = new HashMap<>();
            for (var binding : owned) {
                var v = loaded.get(binding.getImageVersionId());
                if (v == null || !v.isReady()) throw unavailableBinding();
                result.put(binding.getAssetId(), new ImageReference(binding.getId(), v.getId(), v.getAssetId(),
                        v.getObjectKey(), v.getMimeType(), v.getSizeBytes(), v.getWidth(), v.getHeight()));
            }
            return Map.copyOf(result);
        });
    }

    @Override
    public Map<UUID, MediaRefDto> resolve(Collection<UUID> ids) {
        return describe(ids).entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                e -> new MediaRefDto(e.getKey(), e.getValue().cdnUrl(), null, null,
                        e.getValue().width(), e.getValue().height(), e.getValue().mimeType())));
    }

    @Override
    public Map<UUID, ImageVersion> describe(Collection<UUID> ids) {
        List<UUID> sorted = ordered(ids);
        Map<UUID, ImageVersion> result = new HashMap<>();
        for (int i = 0; i < sorted.size(); i += 100) {
            var batch = sorted.subList(i, Math.min(i + 100, sorted.size()));
            versions.findAllByAssetIdInAndReadyTrue(batch).forEach(v -> result.put(v.getAssetId(), new ImageVersion(
                    v.getObjectKey(), properties.getCdnBaseUrl().replaceAll("/+$", "") + "/" + v.getObjectKey(),
                    v.getMimeType(), v.getSizeBytes(), v.getWidth(), v.getHeight())));
        }
        return Map.copyOf(result);
    }

    @Override
    public void remove(UUID id, String username) {
        observe("remove", () -> removeImage(id, username));
    }

    private void removeImage(UUID id, String username) {
        outsideTransaction();
        var image = freshTransaction(s -> { var a = asset(id, false); owner(a, username); return a; });
        if (image.isLibraryRemoved()) return;
        // Preserve legacy current bytes prospectively, without claiming they identify historical stimuli.
        if (freshTransaction(s -> versions.findByAssetId(id).filter(SavedImageVersion::isReady).isEmpty()))
            prepare(Set.of(id), username);
        freshWrite(s -> {
            var current = asset(id, true); owner(current, username); ready(id);
            current.setLibraryRemoved(true); assets.saveAndFlush(current);
        });
    }

    @Override
    public void restore(UUID id, String username) {
        observe("restore", () -> restoreImage(id, username));
    }

    private void restoreImage(UUID id, String username) {
        outsideTransaction();
        var version = freshTransaction(s -> { owner(asset(id, false), username); return ready(id); });
        storage.verify(version);
        freshWrite(s -> {
            var current = asset(id, true); owner(current, username);
            if (!ready(id).getId().equals(version.getId())) throw new SavedImageException(CONFLICT);
            current.setLibraryRemoved(false); assets.saveAndFlush(current);
        });
    }

    private <T> T freshTransaction(TransactionCallback<T> action) {
        outsideTransaction();
        discardReadCache();
        return transaction.execute(action);
    }

    private void freshWrite(Consumer<TransactionStatus> action) {
        freshTransaction(status -> { action.accept(status); return null; });
    }

    private void discardReadCache() {
        // OSIV can otherwise reuse pre-storage ownership/library state in a later transaction.
        var context = TransactionSynchronizationManager.getResource(entityManagers);
        if (context instanceof EntityManagerHolder holder) holder.getEntityManager().clear();
    }

    private MediaAsset asset(UUID id, boolean lock) {
        if (id == null) throw unavailableBinding();
        var value = (lock ? assets.findByIdForUpdate(id) : assets.findById(id)).orElseThrow(RetainedImageServiceImpl::unavailableBinding);
        if (!value.isImage() || value.getStatus() != MediaAssetStatus.READY) throw unavailableBinding();
        return value;
    }
    private SavedImageVersion ready(UUID id) {
        return versions.findByAssetId(id).filter(SavedImageVersion::isReady).orElseThrow(RetainedImageServiceImpl::unavailableBinding);
    }
    private void selectable(MediaAsset asset, String username) {
        owner(asset, username);
        if (asset.isLibraryRemoved() || asset.getWidth() == null || asset.getHeight() == null
                || asset.getWidth() <= 0 || asset.getHeight() <= 0) throw unavailableBinding();
    }
    private void owner(MediaAsset asset, String username) {
        if (username == null || username.isBlank()) throw new ForbiddenException("Authentication required");
        if (!username.equals(asset.getCreatedBy()) && !permissions.hasAnyPermission(PermissionName.MEDIA_ADMIN,
                PermissionName.ARTICLE_ADMIN, PermissionName.SYSTEM_ADMIN))
            throw new ForbiddenException("You cannot modify this media asset");
    }
    private static ResourceNotFoundException unavailableBinding() { return new ResourceNotFoundException("Saved image binding is unavailable"); }
    private static List<UUID> ordered(Collection<UUID> ids) {
        if (ids == null) throw new ValidationException("Image identifiers are required");
        // Match MySQL BINARY(16) order for both individual and batch asset locks.
        return ids.stream().filter(Objects::nonNull).distinct().sorted(Comparator.comparing(UUID::toString)).toList();
    }
    private static void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new SavedImageException(CONFLICT);
    }
    private void observe(String operation, Runnable action) {
        long started = System.nanoTime();
        String outcome = "failed";
        try { action.run(); outcome = "committed"; }
        finally {
            metric(operation, outcome);
            try { metrics.timer("quizmaker.media.retained.duration", "operation", operation, "outcome", outcome)
                    .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS); }
            catch (RuntimeException ignored) { /* telemetry is not lifecycle authority */ }
        }
    }
    private void metric(String operation, String outcome) {
        try { metrics.counter("quizmaker.media.retained", "operation", operation, "outcome", outcome).increment(); }
        catch (RuntimeException ignored) { /* telemetry is not lifecycle authority */ }
    }
}
