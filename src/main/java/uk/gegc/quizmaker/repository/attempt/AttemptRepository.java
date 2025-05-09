package uk.gegc.quizmaker.repository.attempt;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.gegc.quizmaker.model.attempt.Attempt;

import java.util.UUID;

public interface AttemptRepository extends JpaRepository<Attempt, UUID> {
}
