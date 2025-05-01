package uk.gegc.quizmaker.repository.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.model.user.User;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
}
