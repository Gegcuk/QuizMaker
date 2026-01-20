package uk.gegc.quizmaker.features.quiz.application.imports.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.quiz.application.imports.ReferenceResolutionService;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.tag.domain.repository.TagRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
public class ReferenceResolutionServiceImpl implements ReferenceResolutionService {

    private static final String DEFAULT_CATEGORY_DESCRIPTION = "Category created by import";

    private final CategoryRepository categoryRepository;
    private final TagRepository tagRepository;

    private final ThreadLocal<ReferenceCache> cache = ThreadLocal.withInitial(ReferenceCache::new);

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

        cache.categories.put(normalized, resolved);
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
                cache.tags.put(normalized, tag);
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
                cache.tags.put(normalized, tag);
                resolved.add(tag);
            }

            try {
                List<Tag> saved = tagRepository.saveAll(newTags);
                for (Tag tag : saved) {
                    cache.tags.put(normalizeName(tag.getName()), tag);
                }
            } catch (DataIntegrityViolationException ex) {
                List<Tag> refreshed = tagRepository.findByNameInIgnoreCase(new ArrayList<>(unresolved));
                for (Tag tag : refreshed) {
                    cache.tags.put(normalizeName(tag.getName()), tag);
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
    public void clearCaches() {
        cache.remove();
    }

    private ReferenceCache cache() {
        return cache.get();
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
