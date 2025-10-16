package uk.gegc.quizmaker.shared.config;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.admin.aplication.RoleService;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.quiz.config.QuizDefaultsProperties;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final RoleService roleService;
    private final CategoryRepository categoryRepository;
    private final QuizDefaultsProperties quizDefaultsProperties;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        log.info("Starting data initialization...");

        try {
            // Initialize all roles and permissions
            log.info("Calling roleService.initializeDefaultRolesAndPermissions()...");
            roleService.initializeDefaultRolesAndPermissions();
            ensureDefaultCategoryPresent();
            log.info("Data initialization completed successfully");
        } catch (Exception e) {
            log.error("Error during data initialization: {}", e.getMessage(), e);
            throw e;
        }
    }

    @Transactional
    protected void ensureDefaultCategoryPresent() {
        UUID defaultCategoryId = quizDefaultsProperties.getDefaultCategoryId();
        if (categoryRepository.existsById(defaultCategoryId)) {
            log.debug("Default quiz category {} already present", defaultCategoryId);
            maybeUpdateMetadata(defaultCategoryId);
            return;
        }

        try {
            insertDefaultCategory(defaultCategoryId);
            log.info("Seeded default quiz category {} during bootstrap", defaultCategoryId);
        } catch (DataIntegrityViolationException | PersistenceException ex) {
            if (categoryRepository.existsById(defaultCategoryId)) {
                log.debug("Detected concurrent creation of default category {}; using existing record", defaultCategoryId);
                maybeUpdateMetadata(defaultCategoryId);
            } else {
                throw ex;
            }
        }
    }

    private void insertDefaultCategory(UUID defaultCategoryId) {
        entityManager.createNativeQuery(
                "INSERT INTO categories (category_id, category_name, category_description) VALUES (?1, ?2, ?3)")
                .setParameter(1, uuidToBytes(defaultCategoryId))
                .setParameter(2, "Uncategorized")
                .setParameter(3, "Default category for quizzes without a specific category")
                .executeUpdate();
    }

    private void maybeUpdateMetadata(UUID defaultCategoryId) {
        categoryRepository.findById(defaultCategoryId).ifPresent(category -> {
            boolean needsUpdate = false;
            if (!"Uncategorized".equals(category.getName())) {
                category.setName("Uncategorized");
                needsUpdate = true;
            }
            if (!"Default category for quizzes without a specific category".equals(category.getDescription())) {
                category.setDescription("Default category for quizzes without a specific category");
                needsUpdate = true;
            }
            if (needsUpdate) {
                // Use entity manager merge to avoid optimistic locking on detached entity
                entityManager.merge(category);
                log.info("Updated default category {} metadata", defaultCategoryId);
            }
        });
    }

    private static byte[] uuidToBytes(UUID uuid) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return buffer.array();
    }
}
