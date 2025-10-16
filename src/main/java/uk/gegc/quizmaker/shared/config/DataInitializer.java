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

    private static final String DEFAULT_CATEGORY_NAME = "Uncategorized";
    private static final String DEFAULT_CATEGORY_DESCRIPTION = "Default category for quizzes without a specific category";

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

    protected void ensureDefaultCategoryPresent() {
        UUID defaultCategoryId = quizDefaultsProperties.getDefaultCategoryId();
        if (categoryRepository.existsById(defaultCategoryId)) {
            log.debug("Default quiz category {} already present", defaultCategoryId);
            maybeUpdateMetadata(defaultCategoryId);
            return;
        }

        categoryRepository.findByName(DEFAULT_CATEGORY_NAME)
                .ifPresentOrElse(existing -> {
                    if (existing.getId().equals(defaultCategoryId)) {
                        log.debug("Default category {} located via name lookup", defaultCategoryId);
                        maybeUpdateMetadata(defaultCategoryId);
                    } else {
                        log.info("Migrating legacy default category {} to {}", existing.getId(), defaultCategoryId);
                        migrateLegacyDefaultCategory(existing.getId(), defaultCategoryId);
                    }
                }, () -> createDefaultCategory(defaultCategoryId));
    }

    private void insertDefaultCategory(UUID defaultCategoryId) {
        entityManager.createNativeQuery(
                "INSERT INTO categories (category_id, category_name, category_description) VALUES (?1, ?2, ?3)")
                .setParameter(1, uuidToBytes(defaultCategoryId))
                .setParameter(2, DEFAULT_CATEGORY_NAME)
                .setParameter(3, DEFAULT_CATEGORY_DESCRIPTION)
                .executeUpdate();
    }

    private void createDefaultCategory(UUID defaultCategoryId) {
        try {
            insertDefaultCategory(defaultCategoryId);
            log.info("Seeded default quiz category {} during bootstrap", defaultCategoryId);
        } catch (DataIntegrityViolationException | PersistenceException ex) {
            if (categoryRepository.existsById(defaultCategoryId)) {
                log.debug("Detected concurrent creation of default category {}; using existing record", defaultCategoryId);
            } else {
                categoryRepository.findByName(DEFAULT_CATEGORY_NAME)
                        .ifPresentOrElse(existing -> {
                            if (!existing.getId().equals(defaultCategoryId)) {
                                log.info("Legacy default category {} detected during insert; migrating to {}", existing.getId(), defaultCategoryId);
                                migrateLegacyDefaultCategory(existing.getId(), defaultCategoryId);
                            } else {
                                log.debug("Default category {} appeared after insert conflict", defaultCategoryId);
                            }
                        }, () -> {
                            log.error("Failed to seed default category {}", defaultCategoryId, ex);
                            throw ex;
                        });
            }
        }
        maybeUpdateMetadata(defaultCategoryId);
    }

    private void maybeUpdateMetadata(UUID categoryId) {
        categoryRepository.findById(categoryId).ifPresent(category -> {
            boolean needsUpdate = false;
            if (!DEFAULT_CATEGORY_NAME.equals(category.getName())) {
                category.setName(DEFAULT_CATEGORY_NAME);
                needsUpdate = true;
            }
            if (!DEFAULT_CATEGORY_DESCRIPTION.equals(category.getDescription())) {
                category.setDescription(DEFAULT_CATEGORY_DESCRIPTION);
                needsUpdate = true;
            }
            if (needsUpdate) {
                // Use entity manager merge to avoid optimistic locking on detached entity
                entityManager.merge(category);
                log.info("Updated default category {} metadata", categoryId);
            }
        });
    }

    private void migrateLegacyDefaultCategory(UUID legacyId, UUID targetId) {
        String legacyName = (DEFAULT_CATEGORY_NAME + " (legacy " + legacyId.toString().substring(0, 8) + ")");
        String truncatedLegacyName = legacyName.substring(0, Math.min(legacyName.length(), 255));

        entityManager.createNativeQuery("UPDATE categories SET category_name = ?2 WHERE category_id = ?1")
                .setParameter(1, uuidToBytes(legacyId))
                .setParameter(2, truncatedLegacyName)
                .executeUpdate();

        insertDefaultCategory(targetId);

        entityManager.createNativeQuery("UPDATE quizzes SET category_id = ?2 WHERE category_id = ?1")
                .setParameter(1, uuidToBytes(legacyId))
                .setParameter(2, uuidToBytes(targetId))
                .executeUpdate();

        entityManager.createNativeQuery("DELETE FROM categories WHERE category_id = ?1")
                .setParameter(1, uuidToBytes(legacyId))
                .executeUpdate();

        maybeUpdateMetadata(targetId);
    }

    private static byte[] uuidToBytes(UUID uuid) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return buffer.array();
    }
}
