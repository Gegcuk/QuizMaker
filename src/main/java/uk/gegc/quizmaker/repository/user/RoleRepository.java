package uk.gegc.quizmaker.repository.user;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.gegc.quizmaker.model.user.Role;

import java.util.UUID;

public interface RoleRepository extends JpaRepository<Role, UUID> {
}
