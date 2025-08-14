package uk.gegc.quizmaker.repository.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test-mysql")
@org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase(replace = org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE)
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    @DisplayName("findByUsernameWithRoles returns user with roles")
    void findByUsernameWithRoles_ReturnsUser() {
        // Create a role first
        Role role = new Role();
        role.setRoleName("ROLE_USER");
        role.setDescription("User role");
        role.setDefault(true);
        entityManager.persist(role);
        entityManager.flush();

        User u = new User();
        u.setUsername("bob");
        u.setEmail("bob@example.com");
        u.setHashedPassword("{noop}password");
        u.setActive(true);
        u.setDeleted(false);
        u.setEmailVerified(false);
        u.setRoles(Set.of(role));
        entityManager.persist(u);
        entityManager.flush();

        Optional<User> got = userRepository.findByUsernameWithRoles("bob");
        assertTrue(got.isPresent());
        assertNotNull(got.get().getUsername());
        assertNotNull(got.get().getRoles());
        assertFalse(got.get().getRoles().isEmpty());
    }

    @Test
    @DisplayName("findByEmailWithRoles returns empty for unknown email")
    void findByEmailWithRoles_Unknown_ReturnsEmpty() {
        Optional<User> got = userRepository.findByEmailWithRoles("nope@example.com");
        assertTrue(got.isEmpty());
    }
}


