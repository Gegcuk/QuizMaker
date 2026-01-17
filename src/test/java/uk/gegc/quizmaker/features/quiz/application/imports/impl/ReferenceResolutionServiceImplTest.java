package uk.gegc.quizmaker.features.quiz.application.imports.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.dao.DataIntegrityViolationException;
import uk.gegc.quizmaker.BaseUnitTest;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.tag.domain.repository.TagRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

@DisplayName("ReferenceResolutionService")
class ReferenceResolutionServiceImplTest extends BaseUnitTest {

    @Mock
    CategoryRepository categoryRepository;
    @Mock
    TagRepository tagRepository;

    @InjectMocks
    ReferenceResolutionServiceImpl service;

    @BeforeEach
    void resetCache() {
        service.clearCaches();
    }

    @Test
    @DisplayName("resolveCategory returns existing category")
    void resolveCategory_existingCategory_returnsCategory() {
        Category category = categoryWithName("Science");
        when(categoryRepository.findByNameIgnoreCase("Science")).thenReturn(Optional.of(category));

        Category result = service.resolveCategory("Science", false, "user");

        assertThat(result).isSameAs(category);
        verify(categoryRepository).findByNameIgnoreCase("Science");
        verify(categoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("resolveCategory uses case-insensitive lookup")
    void resolveCategory_caseInsensitiveLookup() {
        Category category = categoryWithName("Science");
        when(categoryRepository.findByNameIgnoreCase("SCIENCE")).thenReturn(Optional.of(category));

        Category result = service.resolveCategory("SCIENCE", false, "user");

        assertThat(result).isSameAs(category);
        verify(categoryRepository).findByNameIgnoreCase("SCIENCE");
    }

    @Test
    @DisplayName("resolveCategory auto-creates when missing and enabled")
    void resolveCategory_missingCategory_autoCreate_createsCategory() {
        when(categoryRepository.findByNameIgnoreCase("History")).thenReturn(Optional.empty());
        when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> inv.getArgument(0));

        Category result = service.resolveCategory("History", true, "user");

        ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
        verify(categoryRepository).save(captor.capture());
        Category created = captor.getValue();
        assertThat(created.getName()).isEqualTo("History");
        assertThat(created.getDescription()).isEqualTo("Category created by import");
        assertThat(result).isSameAs(created);
    }

    @Test
    @DisplayName("resolveCategory throws when missing and auto-create disabled")
    void resolveCategory_missingCategory_noAutoCreate_throwsException() {
        when(categoryRepository.findByNameIgnoreCase("Unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveCategory("Unknown", false, "user"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Category Unknown not found");
        verify(categoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("resolveCategory returns null for blank name")
    void resolveCategory_nullName_returnsNull() {
        Category result = service.resolveCategory("  ", false, "user");

        assertThat(result).isNull();
        verifyNoInteractions(categoryRepository);
    }

    @Test
    @DisplayName("resolveCategory caches within import")
    void resolveCategory_cachesWithinImport() {
        Category category = categoryWithName("Science");
        when(categoryRepository.findByNameIgnoreCase("Science")).thenReturn(Optional.of(category));

        Category first = service.resolveCategory("Science", false, "user");
        Category second = service.resolveCategory("Science", false, "user");

        assertThat(first).isSameAs(category);
        assertThat(second).isSameAs(category);
        verify(categoryRepository, times(1)).findByNameIgnoreCase("Science");
    }

    @Test
    @DisplayName("resolveCategory retries after race condition")
    void resolveCategory_raceCondition_retries() {
        Category category = categoryWithName("Science");
        when(categoryRepository.findByNameIgnoreCase("Science"))
                .thenReturn(Optional.empty(), Optional.of(category));
        when(categoryRepository.save(any(Category.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        Category result = service.resolveCategory("Science", true, "user");

        assertThat(result).isSameAs(category);
        verify(categoryRepository, times(2)).findByNameIgnoreCase("Science");
    }

    @Test
    @DisplayName("resolveTags returns existing tags")
    void resolveTags_existingTags_returnsTags() {
        Tag tag1 = tagWithName("science");
        Tag tag2 = tagWithName("math");
        when(tagRepository.findByNameInIgnoreCase(List.of("science", "math"))).thenReturn(List.of(tag1, tag2));

        Set<Tag> result = service.resolveTags(List.of("science", "math"), false, "user");

        assertThat(result).containsExactlyInAnyOrder(tag1, tag2);
        verify(tagRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("resolveTags performs case-insensitive lookup")
    void resolveTags_caseInsensitiveLookup() {
        Tag tag = tagWithName("science");
        when(tagRepository.findByNameInIgnoreCase(any())).thenReturn(List.of(tag));

        service.resolveTags(List.of("  SCIENCE "), false, "user");

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(tagRepository).findByNameInIgnoreCase(captor.capture());
        assertThat(captor.getValue()).containsExactly("science");
    }

    @Test
    @DisplayName("resolveTags auto-creates missing tags when enabled")
    void resolveTags_missingTags_autoCreate_createsTags() {
        when(tagRepository.findByNameInIgnoreCase(List.of("newtag"))).thenReturn(List.of());
        when(tagRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        Set<Tag> result = service.resolveTags(List.of("newtag"), true, "user");

        assertThat(result).hasSize(1);
        assertThat(result.iterator().next().getName()).isEqualTo("newtag");
        verify(tagRepository).saveAll(any());
    }

    @Test
    @DisplayName("resolveTags throws when missing and auto-create disabled")
    void resolveTags_missingTags_noAutoCreate_throwsException() {
        when(tagRepository.findByNameInIgnoreCase(List.of("missing"))).thenReturn(List.of());

        assertThatThrownBy(() -> service.resolveTags(List.of("missing"), false, "user"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Tag(s) not found: missing");
        verify(tagRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("resolveTags returns empty set for null list")
    void resolveTags_nullList_returnsEmptySet() {
        Set<Tag> result = service.resolveTags(null, false, "user");

        assertThat(result).isEmpty();
        verifyNoInteractions(tagRepository);
    }

    @Test
    @DisplayName("resolveTags returns empty set for empty list")
    void resolveTags_emptyList_returnsEmptySet() {
        Set<Tag> result = service.resolveTags(List.of(), false, "user");

        assertThat(result).isEmpty();
        verifyNoInteractions(tagRepository);
    }

    @Test
    @DisplayName("resolveTags persists new tags in batch")
    void resolveTags_batchPersistence() {
        when(tagRepository.findByNameInIgnoreCase(List.of("alpha", "beta"))).thenReturn(List.of());
        when(tagRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        service.resolveTags(List.of("alpha", "beta"), true, "user");

        ArgumentCaptor<List<Tag>> captor = ArgumentCaptor.forClass(List.class);
        verify(tagRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    @Test
    @DisplayName("resolveTags caches within import")
    void resolveTags_cachesWithinImport() {
        Tag tag = tagWithName("math");
        when(tagRepository.findByNameInIgnoreCase(List.of("math"))).thenReturn(List.of(tag));

        Set<Tag> first = service.resolveTags(List.of("math"), false, "user");
        Set<Tag> second = service.resolveTags(List.of("math"), false, "user");

        assertThat(first).containsExactly(tag);
        assertThat(second).containsExactly(tag);
        verify(tagRepository, times(1)).findByNameInIgnoreCase(any());
    }

    @Test
    @DisplayName("clearCaches clears ThreadLocal cache")
    void clearCaches_clearsThreadLocalCache() {
        Category category = categoryWithName("Science");
        when(categoryRepository.findByNameIgnoreCase("Science")).thenReturn(Optional.of(category));

        service.resolveCategory("Science", false, "user");
        service.clearCaches();
        service.resolveCategory("Science", false, "user");

        verify(categoryRepository, times(2)).findByNameIgnoreCase("Science");
    }

    @Test
    @DisplayName("resolveTags retries after race condition")
    void resolveTags_raceCondition_retries() {
        Tag tag1 = tagWithName("alpha");
        Tag tag2 = tagWithName("beta");
        when(tagRepository.findByNameInIgnoreCase(any()))
                .thenReturn(List.of(), List.of(tag1, tag2));
        when(tagRepository.saveAll(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        Set<Tag> result = service.resolveTags(List.of("alpha", "beta"), true, "user");

        assertThat(result.stream().map(Tag::getName).collect(java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrder("alpha", "beta");
        verify(tagRepository, times(2)).findByNameInIgnoreCase(any());
    }

    private Category categoryWithName(String name) {
        Category category = new Category();
        category.setId(UUID.randomUUID());
        category.setName(name);
        return category;
    }

    private Tag tagWithName(String name) {
        Tag tag = new Tag();
        tag.setId(UUID.randomUUID());
        tag.setName(name);
        return tag;
    }
}
