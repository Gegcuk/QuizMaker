package uk.gegc.quizmaker;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;

/**
 * Base class for all integration tests providing standardized configuration.
 * 
 * This class ensures consistent transaction management and test isolation
 * across all integration tests by providing:
 * - @Transactional for automatic rollback after each test
 * - Standardized test properties
 * - Common autowired dependencies (MockMvc, EntityManager, JdbcTemplate)
 * - Consistent test profile configuration
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false"
})
public abstract class BaseIntegrationTest {
    
    @Autowired
    protected MockMvc mockMvc;
    
    @Autowired
    protected EntityManager entityManager;
    
    @Autowired
    protected JdbcTemplate jdbcTemplate;
}
