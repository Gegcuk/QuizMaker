package uk.gegc.quizmaker.features.quiz.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.features.quiz.domain.model.ShareLink;

import java.util.UUID;

@Repository
public interface ShareLinkRepository extends JpaRepository<ShareLink, UUID> {
    java.util.Optional<ShareLink> findByTokenHash(String tokenHash);
    java.util.Optional<ShareLink> findByTokenHashAndRevokedAtIsNull(String tokenHash);
    java.util.List<ShareLink> findAllByCreatedBy_IdOrderByCreatedAtDesc(UUID userId);
    java.util.List<ShareLink> findAllByQuiz_IdAndRevokedAtIsNull(UUID quizId);

    @Modifying
    @Query("UPDATE ShareLink l SET l.revokedAt = :now WHERE l.tokenHash = :hash AND l.revokedAt IS NULL AND l.oneTime = true")
    int consumeOneTime(@Param("hash") String tokenHash, @Param("now") java.time.Instant now);
}


