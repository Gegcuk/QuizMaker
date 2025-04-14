package uk.gegc.quizmaker.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.model.userManagment.User;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
}
