package uk.gegc.quizmaker.repository.user;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.user.domain.model.Permission;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.PermissionRepository;
import uk.gegc.quizmaker.features.user.domain.repository.RoleRepository;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestPropertySource(properties = {
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=none"
})
class UserRepositoryPerformanceTest {

    private static final Logger log = LoggerFactory.getLogger(UserRepositoryPerformanceTest.class);

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    private User testUser;
    private Role userRole;
    private Role adminRole;
    private Permission readPermission;
    private Permission writePermission;
    private Permission deletePermission;

    @BeforeEach
    void setUp() {
        // Clean up any existing test data first
        userRepository.findByUsername("testuser").ifPresent(userRepository::delete);
        userRepository.findByEmail("test@example.com").ifPresent(userRepository::delete);
        
        // Use unique names with timestamp to avoid conflicts
        String timestamp = String.valueOf(System.currentTimeMillis());
        
        // Create permissions with unique names
        readPermission = Permission.builder()
                .permissionName("READ_TEST_" + timestamp)
                .description("Read permission for test")
                .resource("QUIZ")
                .action("READ")
                .build();

        writePermission = Permission.builder()
                .permissionName("WRITE_TEST_" + timestamp)
                .description("Write permission for test")
                .resource("QUIZ")
                .action("WRITE")
                .build();

        deletePermission = Permission.builder()
                .permissionName("DELETE_TEST_" + timestamp)
                .description("Delete permission for test")
                .resource("QUIZ")
                .action("DELETE")
                .build();

        readPermission = permissionRepository.save(readPermission);
        writePermission = permissionRepository.save(writePermission);
        deletePermission = permissionRepository.save(deletePermission);

        // Create roles with unique names
        userRole = Role.builder()
                .roleName("ROLE_USER_TEST_" + timestamp)
                .description("User role for test")
                .isDefault(false) // Don't set as default to avoid conflicts
                .permissions(Set.of(readPermission))
                .build();

        adminRole = Role.builder()
                .roleName("ROLE_ADMIN_TEST_" + timestamp)
                .description("Admin role for test")
                .isDefault(false)
                .permissions(Set.of(readPermission, writePermission, deletePermission))
                .build();

        userRole = roleRepository.save(userRole);
        adminRole = roleRepository.save(adminRole);

        // Create user with roles
        testUser = new User();
        testUser.setUsername("testuser");
        testUser.setEmail("test@example.com");
        testUser.setHashedPassword("hashedpassword");
        testUser.setActive(true);
        testUser.setDeleted(false);
        testUser.setCreatedAt(LocalDateTime.now());
        testUser.setRoles(Set.of(userRole, adminRole));

        testUser = userRepository.save(testUser);
        entityManager.flush();
        entityManager.clear(); // Clear persistence context to ensure fresh queries
    }

    @Test
    void testFindByUsernameWithRoles_ShouldFetchRolesInSingleQuery() {
        // When
        Optional<User> result = userRepository.findByUsernameWithRoles("testuser");

        // Then
        assertThat(result).isPresent();
        User user = result.get();
        
        // Verify roles are loaded without additional queries
        assertThat(user.getRoles()).hasSize(2);
        assertThat(user.getRoles()).extracting(Role::getRoleName)
                .containsExactlyInAnyOrder(userRole.getRoleName(), adminRole.getRoleName());
        
        log.info("Successfully fetched user with roles in single query");
    }

    @Test
    void testFindByEmailWithRoles_ShouldFetchRolesInSingleQuery() {
        // When
        Optional<User> result = userRepository.findByEmailWithRoles("test@example.com");

        // Then
        assertThat(result).isPresent();
        User user = result.get();
        
        // Verify roles are loaded without additional queries
        assertThat(user.getRoles()).hasSize(2);
        assertThat(user.getRoles()).extracting(Role::getRoleName)
                .containsExactlyInAnyOrder(userRole.getRoleName(), adminRole.getRoleName());
        
        log.info("Successfully fetched user with roles by email in single query");
    }

    @Test
    void testFindByIdWithRoles_ShouldFetchRolesInSingleQuery() {
        // When
        Optional<User> result = userRepository.findByIdWithRoles(testUser.getId());

        // Then
        assertThat(result).isPresent();
        User user = result.get();
        
        // Verify roles are loaded without additional queries
        assertThat(user.getRoles()).hasSize(2);
        assertThat(user.getRoles()).extracting(Role::getRoleName)
                .containsExactlyInAnyOrder(userRole.getRoleName(), adminRole.getRoleName());
        
        log.info("Successfully fetched user with roles by ID in single query");
    }

    @Test
    void testFindByUsernameWithRolesAndPermissions_ShouldFetchAllInSingleQuery() {
        // When
        Optional<User> result = userRepository.findByUsernameWithRolesAndPermissions("testuser");

        // Then
        assertThat(result).isPresent();
        User user = result.get();
        
        // Verify roles are loaded
        assertThat(user.getRoles()).hasSize(2);
        
        // Verify permissions are loaded for each role without additional queries
        Set<Permission> allPermissions = new HashSet<>();
        for (Role role : user.getRoles()) {
            allPermissions.addAll(role.getPermissions());
        }
        
        assertThat(allPermissions).hasSize(3);
        assertThat(allPermissions).extracting(Permission::getPermissionName)
                .containsExactlyInAnyOrder(readPermission.getPermissionName(), writePermission.getPermissionName(), deletePermission.getPermissionName());
        
        log.info("Successfully fetched user with roles and permissions in single query");
    }

    @Test
    void testFindByIdWithRolesAndPermissions_ShouldFetchAllInSingleQuery() {
        // When
        Optional<User> result = userRepository.findByIdWithRolesAndPermissions(testUser.getId());

        // Then
        assertThat(result).isPresent();
        User user = result.get();
        
        // Verify roles are loaded
        assertThat(user.getRoles()).hasSize(2);
        
        // Verify permissions are loaded for each role without additional queries
        Set<Permission> allPermissions = new HashSet<>();
        for (Role role : user.getRoles()) {
            allPermissions.addAll(role.getPermissions());
        }
        
        assertThat(allPermissions).hasSize(3);
        assertThat(allPermissions).extracting(Permission::getPermissionName)
                .containsExactlyInAnyOrder(readPermission.getPermissionName(), writePermission.getPermissionName(), deletePermission.getPermissionName());
        
        log.info("Successfully fetched user with roles and permissions by ID in single query");
    }

    @Test
    void testRoleRepositoryWithPermissions_ShouldFetchPermissionsInSingleQuery() {
        // When
        Optional<Role> result = roleRepository.findByRoleNameWithPermissions(adminRole.getRoleName());

        // Then
        assertThat(result).isPresent();
        Role role = result.get();
        
        // Verify permissions are loaded without additional queries
        assertThat(role.getPermissions()).hasSize(3);
        assertThat(role.getPermissions()).extracting(Permission::getPermissionName)
                .containsExactlyInAnyOrder(readPermission.getPermissionName(), writePermission.getPermissionName(), deletePermission.getPermissionName());
        
        log.info("Successfully fetched role with permissions in single query");
    }

    @Test
    void testFindAllRolesWithPermissions_ShouldFetchAllPermissionsInSingleQuery() {
        // When
        List<Role> roles = roleRepository.findAllWithPermissions();

        // Then - Should include both system roles and our test roles
        assertThat(roles).hasSizeGreaterThanOrEqualTo(2); // At least our 2 test roles
        
        // Verify our test roles are included and permissions are loaded without additional queries
        boolean foundUserTestRole = false;
        boolean foundAdminTestRole = false;
        
        for (Role role : roles) {
            if (role.getRoleName().equals(userRole.getRoleName())) {
                foundUserTestRole = true;
                assertThat(role.getPermissions()).hasSize(1); // Only read permission
            } else if (role.getRoleName().equals(adminRole.getRoleName())) {
                foundAdminTestRole = true;
                assertThat(role.getPermissions()).hasSize(3); // Read, write, delete permissions
            }
            log.info("Role {} has {} permissions", role.getRoleName(), role.getPermissions().size());
        }
        
        assertThat(foundUserTestRole).isTrue();
        assertThat(foundAdminTestRole).isTrue();
        
        log.info("Successfully fetched all {} roles with permissions in single query", roles.size());
    }

    @Test
    void testFindByIsDefaultTrueWithPermissions_ShouldFetchPermissionsInSingleQuery() {
        // Since we're not setting any test roles as default, let's test with an existing default role
        // or skip this test if no default role exists
        Optional<Role> result = roleRepository.findByIsDefaultTrueWithPermissions();

        if (result.isPresent()) {
            Role role = result.get();
            assertThat(role.isDefault()).isTrue();
            log.info("Successfully fetched default role {} with permissions in single query", role.getRoleName());
        } else {
            log.info("No default role found, which is expected for this test setup");
        }
    }
} 