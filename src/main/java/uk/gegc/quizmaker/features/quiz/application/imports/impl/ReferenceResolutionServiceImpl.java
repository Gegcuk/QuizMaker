package uk.gegc.quizmaker.features.quiz.application.imports.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.quiz.application.imports.ImportReferenceSnapshot;
import uk.gegc.quizmaker.features.quiz.application.imports.ReferenceResolutionService;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.tag.domain.repository.TagRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Positive cache scoped to one synchronous import. Preloading reads only requested names;
 * for example, 100 distinct existing categories and 100 tags need two reference SELECTs.
 * Missing names are resolved per item, preserving auto-create and partial-success behavior.
 * Entries added in an item transaction survive only if it commits. Cleanup needs no database
 * connection, including when preload transaction creation or completion fails.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ReferenceResolutionServiceImpl implements ReferenceResolutionService {

    private static final String DEFAULT_CATEGORY_DESCRIPTION = "Category created by import";
    private static final int PRELOAD_BATCH_SIZE = 250;

    private final CategoryRepository categoryRepository;
    private final TagRepository tagRepository;

    private final ThreadLocal<ReferenceCache> cache = ThreadLocal.withInitial(ReferenceCache::new);

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public ImportReferenceSnapshot preloadReferences(Collection<String> categoryNames, Collection<String> tagNames) {
        clearCaches();
        ReferenceCache preloaded = new ReferenceCache();
        List<String> categories = normalizedNames(categoryNames);
        List<String> tags = normalizedNames(tagNames);
        for (int start = 0; start < categories.size(); start += PRELOAD_BATCH_SIZE) {
            List<String> batch = categories.subList(start, Math.min(start + PRELOAD_BATCH_SIZE, categories.size()));
            for (Category category : categoryRepository.findByNameInIgnoreCase(batch)) {
                preloaded.categories.put(normalizeName(category.getName()), category);
            }
        }
        for (int start = 0; start < tags.size(); start += PRELOAD_BATCH_SIZE) {
            List<String> batch = tags.subList(start, Math.min(start + PRELOAD_BATCH_SIZE, tags.size()));
            for (Tag tag : tagRepository.findByNameInIgnoreCase(batch)) {
                preloaded.tags.put(normalizeName(tag.getName()), tag);
            }
        }
        // Publish only after every batch succeeds; callers clear the cache if transaction completion fails.
        cache.set(preloaded);
        return new ImportReferenceSnapshot(preloaded.categories, preloaded.tags);
    }

    @Override
    public Category resolveCategory(String categoryName, boolean autoCreate, String username) {
        if (categoryName == null || categoryName.isBlank()) {
            return null;
        }
        String trimmed = categoryName.trim();
        String normalized = normalizeName(trimmed);

        ReferenceCache cache = cache();
        Category cached = cache.categories.get(normalized);
        if (cached != null) {
            return cached;
        }

        Category resolved = categoryRepository.findByNameIgnoreCase(trimmed)
                .orElseGet(() -> {
                    if (!autoCreate) {
                        throw new ResourceNotFoundException("Category " + trimmed + " not found");
                    }
                    Category created = new Category();
                    created.setName(trimmed);
                    created.setDescription(DEFAULT_CATEGORY_DESCRIPTION);
                    try {
                        return categoryRepository.save(created);
                    } catch (DataIntegrityViolationException ex) {
                        return categoryRepository.findByNameIgnoreCase(trimmed)
                                .orElseThrow(() -> ex);
                    }
                });

        cacheValue(cache.categories, normalized, resolved);
        return resolved;
    }

    @Override
    public Set<Tag> resolveTags(List<String> tagNames, boolean autoCreate, String username) {
        if (tagNames == null || tagNames.isEmpty()) {
            return new LinkedHashSet<>();
        }

        ReferenceCache cache = cache();
        LinkedHashSet<Tag> resolved = new LinkedHashSet<>();
        LinkedHashSet<String> unresolved = new LinkedHashSet<>();
        Map<String, String> normalizedToOriginal = new HashMap<>();

        for (String tagName : tagNames) {
            if (tagName == null || tagName.isBlank()) {
                continue;
            }
            String trimmed = tagName.trim();
            String normalized = normalizeName(trimmed);

            Tag cached = cache.tags.get(normalized);
            if (cached != null) {
                resolved.add(cached);
                continue;
            }

            unresolved.add(normalized);
            normalizedToOriginal.putIfAbsent(normalized, trimmed);
        }

        if (!unresolved.isEmpty()) {
            List<Tag> existing = tagRepository.findByNameInIgnoreCase(new ArrayList<>(unresolved));
            for (Tag tag : existing) {
                String normalized = normalizeName(tag.getName());
                cacheValue(cache.tags, normalized, tag);
                resolved.add(tag);
            }
            unresolved.removeIf(name -> cache.tags.containsKey(name));
        }

        if (!unresolved.isEmpty()) {
            if (!autoCreate) {
                throw new ResourceNotFoundException("Tag(s) not found: " + String.join(", ",
                        originalNames(unresolved, normalizedToOriginal)));
            }

            List<Tag> newTags = new ArrayList<>();
            for (String normalized : unresolved) {
                String name = normalizedToOriginal.getOrDefault(normalized, normalized);
                Tag tag = new Tag();
                tag.setName(name);
                newTags.add(tag);
                cacheValue(cache.tags, normalized, tag);
                resolved.add(tag);
            }

            try {
                List<Tag> saved = tagRepository.saveAll(newTags);
                for (Tag tag : saved) {
                    cacheValue(cache.tags, normalizeName(tag.getName()), tag);
                }
            } catch (DataIntegrityViolationException ex) {
                List<Tag> refreshed = tagRepository.findByNameInIgnoreCase(new ArrayList<>(unresolved));
                for (Tag tag : refreshed) {
                    cacheValue(cache.tags, normalizeName(tag.getName()), tag);
                    resolved.add(tag);
                }
                List<String> stillMissing = new ArrayList<>();
                for (String normalized : unresolved) {
                    if (!cache.tags.containsKey(normalized)) {
                        stillMissing.add(normalized);
                    }
                }
                if (!stillMissing.isEmpty()) {
                    throw ex;
                }
            }
        }

        return resolved;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void clearCaches() {
        cache.remove();
    }

    private ReferenceCache cache() {
        return cache.get();
    }

    private List<String> normalizedNames(Collection<String> names) {
        if (names == null) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                normalized.add(normalizeName(name));
            }
        }
        return new ArrayList<>(normalized);
    }

    private <T> void cacheValue(Map<String, T> values, String name, T value) {
        T previous = values.put(name, value);
        if (previous != value && TransactionSynchronizationManager.isSynchronizationActive()) {
            // A later quiz must never reuse a reference whose item transaction did not commit.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        values.remove(name, value);
                    }
                }
            });
        }
    }

    private String normalizeName(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private List<String> originalNames(Set<String> normalized, Map<String, String> normalizedToOriginal) {
        List<String> names = new ArrayList<>();
        for (String name : normalized) {
            names.add(normalizedToOriginal.getOrDefault(name, name));
        }
        return names;
    }

    private static final class ReferenceCache {
        private final Map<String, Category> categories = new HashMap<>();
        private final Map<String, Tag> tags = new HashMap<>();
    }
}
