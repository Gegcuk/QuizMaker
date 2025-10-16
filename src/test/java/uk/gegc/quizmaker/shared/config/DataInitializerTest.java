package uk.gegc.quizmaker.shared.config;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.admin.aplication.RoleService;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.quiz.config.QuizDefaultsProperties;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataInitializerTest {

    private static final UUID LEGACY_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID NEW_DEFAULT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private RoleService roleService;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private QuizDefaultsProperties quizDefaultsProperties;
    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private DataInitializer dataInitializer;

    private List<String> executedSql;

    @BeforeEach
    void setUp() {
        executedSql = new ArrayList<>();

        doNothing().when(roleService).initializeDefaultRolesAndPermissions();
        when(quizDefaultsProperties.getDefaultCategoryId()).thenReturn(NEW_DEFAULT_ID);

        ReflectionTestUtils.setField(dataInitializer, "entityManager", entityManager);

        lenient().when(entityManager.createNativeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            executedSql.add(sql);
            Query query = org.mockito.Mockito.mock(Query.class);
            when(query.setParameter(anyInt(), any())).thenReturn(query);
            when(query.executeUpdate()).thenReturn(1);
            return query;
        });
    }

    @Test
    void migratesLegacyDefaultCategoryWhenIdentifierChanges() throws Exception {
        Category legacyCategory = new Category();
        legacyCategory.setId(LEGACY_ID);
        legacyCategory.setName("Uncategorized");

        when(categoryRepository.existsById(NEW_DEFAULT_ID)).thenReturn(false);
        when(categoryRepository.findByName("Uncategorized")).thenReturn(Optional.of(legacyCategory));
        when(categoryRepository.findById(NEW_DEFAULT_ID)).thenReturn(Optional.empty());

        dataInitializer.run();

        assertThat(executedSql)
                .containsExactly(
                        "UPDATE categories SET category_name = ?2 WHERE category_id = ?1",
                        "INSERT INTO categories (category_id, category_name, category_description) VALUES (?1, ?2, ?3)",
                        "UPDATE quizzes SET category_id = ?2 WHERE category_id = ?1",
                        "DELETE FROM categories WHERE category_id = ?1"
                );
    }
}
