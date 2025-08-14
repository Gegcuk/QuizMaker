package uk.gegc.quizmaker.repository.quiz;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.model.quiz.ShareLinkUsage;

import java.util.UUID;

@Repository
public interface ShareLinkUsageRepository extends JpaRepository<ShareLinkUsage, UUID> {
}


