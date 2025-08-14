package uk.gegc.quizmaker.features.quiz.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.features.quiz.domain.model.ShareLinkUsage;

import java.util.UUID;

@Repository
public interface ShareLinkUsageRepository extends JpaRepository<ShareLinkUsage, UUID> {
}


