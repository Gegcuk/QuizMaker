package uk.gegc.quizmaker.features.auth.infra;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import uk.gegc.quizmaker.features.auth.domain.model.OAuthAccount;
import uk.gegc.quizmaker.features.auth.domain.model.OAuthProvider;
import uk.gegc.quizmaker.features.auth.domain.repository.OAuthAccountRepository;
import uk.gegc.quizmaker.features.user.domain.model.Permission;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.RoleRepository;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Tag("db-serial")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthThrottleFetchPlanTest {
    @Autowired EntityManager em;
    @Autowired EntityManagerFactory emf;
    @Autowired UserRepository users;
    @Autowired OAuthAccountRepository accounts;
    @Autowired RoleRepository roles;
    Statistics statistics;

    @BeforeEach
    void fixture() {
        for (int i = 0; i < 3; i++) {
            Set<Role> assigned = new HashSet<>();
            for (int j = 0; j < 3; j++) {
                Permission permission = new Permission();
                permission.setPermissionName("fixture-permission-" + i + "-" + j);
                em.persist(permission);
                Role role = new Role();
                role.setRoleName("fixture-role-" + i + "-" + j);
                role.setPermissions(new HashSet<>(Set.of(permission)));
                em.persist(role);
                assigned.add(role);
            }
            User user = new User();
            user.setUsername("fixture-user-" + i);
            user.setEmail("fixture-" + i + "@example.test");
            user.setHashedPassword("synthetic-not-a-login-verifier");
            user.setActive(true);
            user.setRoles(assigned);
            em.persist(user);
            OAuthAccount account = new OAuthAccount();
            account.setUser(user);
            account.setProvider(OAuthProvider.GOOGLE);
            account.setProviderUserId("fixture-subject-" + i);
            em.persist(account);
        }
        em.flush();
        em.clear();
        statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
    }

    @Test
    void admissionIdentityResolutionDoesNotLoadAnyUserOrRoleEntities() {
        for (int i = 0; i < 3; i++) {
            UUID user = users.findAuthThrottleIdByUsername("fixture-user-" + i).orElseThrow();
            assertThat(users.findAuthThrottleIdByEmail("fixture-" + i + "@example.test")).contains(user);
        }
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(6);
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(statistics.getCollectionFetchCount()).isZero();
    }

    @Test
    void multipleOAuthUsersWithMultipleRolesSerializeAuthoritiesWithoutAdditionalQueries() {
        for (int i = 0; i < 3; i++) {
            User user = accounts.findByProviderAndProviderUserIdWithUser(OAuthProvider.GOOGLE, "fixture-subject-" + i)
                    .orElseThrow().getUser();
            assertThat(user.getRoles()).hasSize(3);
            assertThat(user.getRoles().stream().flatMap(role -> role.getPermissions().stream()).count()).isEqualTo(3);
        }
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
        assertThat(statistics.getCollectionFetchCount()).isZero();
    }

    @Test
    void emailLinkAndSignupRoleReadsHaveBoundedPermissionFetchPlans() {
        for (int i = 0; i < 3; i++) {
            User user = users.findByEmailWithRolesAndPermissions("fixture-" + i + "@example.test").orElseThrow();
            assertThat(user.getRoles().stream().flatMap(role -> role.getPermissions().stream()).count()).isEqualTo(3);
        }
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
        assertThat(statistics.getCollectionFetchCount()).isZero();
        em.clear(); statistics.clear();
        for (int j = 0; j < 3; j++) {
            assertThat(roles.findByRoleNameWithPermissions("fixture-role-0-" + j).orElseThrow().getPermissions()).hasSize(1);
        }
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
        assertThat(statistics.getCollectionFetchCount()).isZero();
    }
}
