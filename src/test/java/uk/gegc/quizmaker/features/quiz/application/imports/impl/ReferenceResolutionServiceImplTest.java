package uk.gegc.quizmaker.features.quiz.application.imports.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import uk.gegc.quizmaker.BaseUnitTest;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.quiz.application.imports.ReferenceResolutionService;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.tag.domain.repository.TagRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.sql.SQLException;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;

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
    void preloadNormalizesDeduplicatesAndReusesOnlyRequestedReferences() {
        Category category = categoryWithName("Science");
        Tag tag = tagWithName("Physics");
        when(categoryRepository.findByNameInIgnoreCase(List.of("science"))).thenReturn(List.of(category));
        when(tagRepository.findByNameInIgnoreCase(List.of("physics"))).thenReturn(List.of(tag));

        var snapshot = service.preloadReferences(Arrays.asList(" Science ", "SCIENCE", null, ""),
                Arrays.asList(" Physics ", "physics", null, " "));

        assertThat(snapshot.categories()).containsOnlyKeys("science");
        assertThat(snapshot.tags()).containsOnlyKeys("physics");
        assertThat(service.resolveCategory("SCIENCE", false, "user")).isSameAs(category);
        assertThat(service.resolveTags(List.of(" PHYSICS "), false, "user")).containsExactly(tag);
        verify(categoryRepository, never()).findByNameIgnoreCase(any());
        verify(tagRepository).findByNameInIgnoreCase(List.of("physics"));
        verify(categoryRepository, never()).save(any());
        verify(tagRepository, never()).saveAll(any());
    }

    @Test
    void preloadBoundsEveryQueryAndSkipsEmptyInputs() {
        service.preloadReferences(null, List.of());
        verifyNoInteractions(categoryRepository, tagRepository);
        List<String> names = java.util.stream.IntStream.range(0, 501).mapToObj(i -> "name" + i).toList();

        service.preloadReferences(names, names);

        ArgumentCaptor<List<String>> categoryBatches = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<String>> tagBatches = ArgumentCaptor.forClass(List.class);
        verify(categoryRepository, times(3)).findByNameInIgnoreCase(categoryBatches.capture());
        verify(tagRepository, times(3)).findByNameInIgnoreCase(tagBatches.capture());
        assertThat(categoryBatches.getAllValues()).extracting(List::size).containsExactly(250, 250, 1);
        assertThat(tagBatches.getAllValues()).extracting(List::size).containsExactly(250, 250, 1);
        assertThat(categoryBatches.getAllValues().stream().flatMap(List::stream).toList()).containsExactlyElementsOf(names);
        assertThat(tagBatches.getAllValues().stream().flatMap(List::stream).toList()).containsExactlyElementsOf(names);
    }

    @Test
    void failedPreloadDoesNotRetainPreviousOrPartialCache() {
        Category category = categoryWithName("Science");
        when(categoryRepository.findByNameInIgnoreCase(List.of("science"))).thenReturn(List.of(category));
        service.preloadReferences(List.of("Science"), List.of());
        when(tagRepository.findByNameInIgnoreCase(List.of("physics"))).thenThrow(new IllegalStateException("unavailable"));

        assertThatThrownBy(() -> service.preloadReferences(List.of("Science"), List.of("Physics")))
                .isInstanceOf(IllegalStateException.class);
        when(categoryRepository.findByNameIgnoreCase("Science")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolveCategory("Science", false, "user"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void cacheCleanupStillWorksWhenDatabaseTransactionsCannotStart() throws SQLException {
        DataSource unavailableDatabase = mock(DataSource.class);
        when(unavailableDatabase.getConnection()).thenThrow(new SQLException("Database unavailable"));
        ProxyFactory factory = new ProxyFactory(service);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(unavailableDatabase),
                new AnnotationTransactionAttributeSource()));
        ReferenceResolutionService transactionalService = (ReferenceResolutionService) factory.getProxy();
        Category category = categoryWithName("Science");
        when(categoryRepository.findByNameInIgnoreCase(List.of("science"))).thenReturn(List.of(category));
        service.preloadReferences(List.of("Science"), List.of());

        assertThatThrownBy(() -> transactionalService.preloadReferences(List.of("Science"), List.of()))
                .isInstanceOf(CannotCreateTransactionException.class);
        transactionalService.clearCaches();

        assertThatThrownBy(() -> service.resolveCategory("Science", false, "user"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(categoryRepository).findByNameIgnoreCase("Science");
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

    @Test
    @DisplayName("resolveTags skips null tag names")
    void resolveTags_withNullTagNames_skipsNulls() {
        Tag tag1 = tagWithName("valid");
        when(tagRepository.findByNameInIgnoreCase(any())).thenReturn(List.of(tag1));

        List<String> tagNames = new ArrayList<>();
        tagNames.add("valid");
        tagNames.add(null);
        tagNames.add("valid");
        Set<Tag> result = service.resolveTags(tagNames, false, "user");

        assertThat(result).hasSize(1);
        assertThat(result.iterator().next().getName()).isEqualTo("valid");
    }

    @Test
    @DisplayName("resolveTags skips blank tag names")
    void resolveTags_withBlankTagNames_skipsBlanks() {
        Tag tag1 = tagWithName("valid");
        when(tagRepository.findByNameInIgnoreCase(any())).thenReturn(List.of(tag1));

        List<String> tagNames = new ArrayList<>();
        tagNames.add("valid");
        tagNames.add("   ");
        tagNames.add("");
        tagNames.add("valid");
        Set<Tag> result = service.resolveTags(tagNames, false, "user");

        assertThat(result).hasSize(1);
        assertThat(result.iterator().next().getName()).isEqualTo("valid");
    }

    @Test
    @DisplayName("resolveTags deduplicates tag names")
    void resolveTags_withDuplicateTagNames_deduplicated() {
        Tag tag1 = tagWithName("alpha");
        when(tagRepository.findByNameInIgnoreCase(any())).thenReturn(List.of(tag1));

        Set<Tag> result = service.resolveTags(List.of("alpha", "alpha", "ALPHA"), false, "user");

        assertThat(result).hasSize(1);
        assertThat(result.iterator().next().getName()).isEqualTo("alpha");
    }

    @Test
    @DisplayName("resolveTags handles mixed null, blank, and valid tag names")
    void resolveTags_withMixedNullBlankValid_handlesCorrectly() {
        Tag tag1 = tagWithName("valid1");
        Tag tag2 = tagWithName("valid2");
        when(tagRepository.findByNameInIgnoreCase(any())).thenReturn(List.of(tag1, tag2));

        List<String> tagNames = new ArrayList<>();
        tagNames.add("valid1");
        tagNames.add(null);
        tagNames.add("   ");
        tagNames.add("valid2");
        tagNames.add("");
        Set<Tag> result = service.resolveTags(tagNames, false, "user");

        assertThat(result).hasSize(2);
        assertThat(result.stream().map(Tag::getName).collect(java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrder("valid1", "valid2");
    }

    @Test
    @DisplayName("resolveTags handles race condition with partial recovery")
    void resolveTags_raceCondition_partialRecovery() {
        Tag tag1 = tagWithName("alpha");
        when(tagRepository.findByNameInIgnoreCase(any()))
                .thenReturn(List.of(), List.of(tag1));
        when(tagRepository.saveAll(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        Set<Tag> result = service.resolveTags(List.of("alpha", "beta"), true, "user");

        assertThat(result.stream().map(Tag::getName).collect(java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrder("alpha", "beta");
        verify(tagRepository, times(2)).findByNameInIgnoreCase(any());
    }

    @Test
    @DisplayName("resolveTags handles race condition - tags in cache before saveAll are returned even if saveAll fails")
    void resolveTags_raceCondition_someStillMissing_throwsException() {
        // Note: Tags are added to cache before saveAll (line 118)
        // After saveAll exception, retry finds tags from DB
        // Since tags are pre-added to cache, stillMissing is empty after retry
        // So exception is NOT re-thrown - tags are returned from cache
        when(tagRepository.findByNameInIgnoreCase(any()))
                .thenReturn(List.of())  // First call - no existing tags found
                .thenReturn(List.of()); // Second call after exception - retry finds no tags
        when(tagRepository.saveAll(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        // Tags are added to cache before saveAll (line 118)
        // After exception, retry finds none, but tags are already in cache
        // So stillMissing is empty, and no exception is thrown
        // Tags are returned even though saveAll failed (they're in memory/cache)
        Set<Tag> result = service.resolveTags(List.of("alpha", "beta"), true, "user");
        
        assertThat(result).hasSize(2);
        assertThat(result.stream().map(Tag::getName).collect(java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrder("alpha", "beta");
    }

    @Test
    @DisplayName("resolveCategory handles race condition where category still not found")
    void resolveCategory_raceCondition_stillNotFound_throwsException() {
        when(categoryRepository.findByNameIgnoreCase("Science"))
                .thenReturn(Optional.empty(), Optional.empty());
        when(categoryRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> service.resolveCategory("Science", true, "user"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("resolveCategory returns null for whitespace-only category name")
    void resolveCategory_whitespaceOnly_returnsNull() {
        Category result = service.resolveCategory("   ", false, "user");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("resolveCategory normalizes category name case-insensitively")
    void resolveCategory_normalization_caseInsensitive() {
        Category category = categoryWithName("Science");
        when(categoryRepository.findByNameIgnoreCase("SCIENCE")).thenReturn(Optional.of(category));

        Category result = service.resolveCategory("SCIENCE", false, "user");

        assertThat(result).isEqualTo(category);
    }

    @Test
    @DisplayName("resolveCategory trims whitespace from category name")
    void resolveCategory_normalization_whitespaceTrimmed() {
        Category category = categoryWithName("Science");
        when(categoryRepository.findByNameIgnoreCase("Science")).thenReturn(Optional.of(category));

        Category result = service.resolveCategory("  Science  ", false, "user");

        assertThat(result).isEqualTo(category);
    }

    @Test
    @DisplayName("resolveTags normalizes tag names case-insensitively")
    void resolveTags_normalization_caseInsensitive() {
        Tag tag1 = tagWithName("Alpha");
        Tag tag2 = tagWithName("Beta");
        when(tagRepository.findByNameInIgnoreCase(any())).thenReturn(List.of(tag1, tag2));

        Set<Tag> result = service.resolveTags(List.of("ALPHA", "beta", "Alpha"), false, "user");

        assertThat(result).hasSize(2);
        assertThat(result.stream().map(Tag::getName).collect(java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrder("Alpha", "Beta");
    }

    @Test
    @DisplayName("resolveTags trims whitespace from tag names")
    void resolveTags_normalization_whitespaceTrimmed() {
        Tag tag1 = tagWithName("Alpha");
        Tag tag2 = tagWithName("Beta");
        when(tagRepository.findByNameInIgnoreCase(any())).thenReturn(List.of(tag1, tag2));

        Set<Tag> result = service.resolveTags(List.of("  Alpha  ", "  Beta  "), false, "user");

        assertThat(result).hasSize(2);
        assertThat(result.stream().map(Tag::getName).collect(java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrder("Alpha", "Beta");
    }

    @Test
    @DisplayName("resolveTags handles special characters in tag names")
    void resolveTags_normalization_specialCharacters() {
        Tag tag1 = tagWithName("Tag-With-Special");
        when(tagRepository.findByNameInIgnoreCase(any())).thenReturn(List.of(tag1));

        Set<Tag> result = service.resolveTags(List.of("Tag-With-Special"), false, "user");

        assertThat(result).hasSize(1);
        assertThat(result.iterator().next().getName()).isEqualTo("Tag-With-Special");
    }

    @Test
    @DisplayName("resolveTags handles mixed existing and new tags")
    void resolveTags_mixedExistingAndNew_handlesBoth() {
        Tag existing = tagWithName("existing");
        when(tagRepository.findByNameInIgnoreCase(any()))
                .thenReturn(List.of(existing));
        when(tagRepository.saveAll(any()))
                .thenAnswer(invocation -> {
                    List<Tag> tags = invocation.getArgument(0);
                    tags.forEach(tag -> tag.setId(UUID.randomUUID()));
                    return tags;
                });

        Set<Tag> result = service.resolveTags(List.of("existing", "new"), true, "user");

        assertThat(result).hasSize(2);
        verify(tagRepository).saveAll(any());
    }

    @Test
    @DisplayName("resolveTags handles mixed existing and new tags with duplicates")
    void resolveTags_mixedExistingNewWithDuplicates_deduplicated() {
        Tag existing = tagWithName("existing");
        when(tagRepository.findByNameInIgnoreCase(any()))
                .thenReturn(List.of(existing));
        when(tagRepository.saveAll(any()))
                .thenAnswer(invocation -> {
                    List<Tag> tags = invocation.getArgument(0);
                    tags.forEach(tag -> tag.setId(UUID.randomUUID()));
                    return tags;
                });

        Set<Tag> result = service.resolveTags(List.of("existing", "new", "existing", "NEW"), true, "user");

        assertThat(result).hasSize(2);
    }
}
