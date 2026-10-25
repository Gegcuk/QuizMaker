package uk.gegc.quizmaker.features.attempt.subject;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import uk.gegc.quizmaker.BaseIntegrationTest;
import uk.gegc.quizmaker.features.attempt.application.AttemptSubjectService;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubject;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubjectLookup;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.util.HashSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Full application schema lifecycle, unlike the isolated Flyway/storage fixtures. */
class AttemptSubjectSchemaMySqlIntegrationTest extends BaseIntegrationTest {
    @Autowired AttemptSubjectService subjects;
    @Autowired UserRepository users;

    @Test
    void hibernateRecreatesTheUserSchemaAndJdbcBindingJoinsItsJpaTransaction() {
        // An unmapped subject FK used to prevent dropping users, leaving Flyway's TIMESTAMP(0)
        // instead of Hibernate's microsecond column and breaking refresh-token version checks.
        assertThat(jdbcTemplate.queryForObject("""
                SELECT DATETIME_PRECISION FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='users' AND column_name='password_changed_at'
                """, Integer.class)).isEqualTo(6);
        User user = new User();
        String suffix = UUID.randomUUID().toString();
        user.setUsername("subject-" + suffix); user.setEmail(suffix + "@example.invalid");
        user.setHashedPassword("synthetic-unused"); user.setActive(true); user.setRoles(new HashSet<>());
        user = users.saveAndFlush(user);
        var binding = AttemptSubject.authenticated(UUID.randomUUID(), user.getId());
        assertThat(subjects.bindOrReplay(user.getId(), binding)).isEqualTo(binding);
        assertThat(subjects.read(binding.attemptId())).isEqualTo(new AttemptSubjectLookup.Bound(binding));
    }
}
