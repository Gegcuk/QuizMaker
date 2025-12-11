package uk.gegc.quizmaker.features.tag.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;

import java.util.List;
import java.util.UUID;

@Repository
public interface TagRepository extends JpaRepository<Tag, UUID> {
    @Query("SELECT t FROM Tag t WHERE LOWER(t.name) IN :lowerNames")
    List<Tag> findByNameInIgnoreCase(@Param("lowerNames") List<String> lowerNames);
}
