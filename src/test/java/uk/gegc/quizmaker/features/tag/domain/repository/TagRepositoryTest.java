package uk.gegc.quizmaker.features.tag.domain.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import uk.gegc.quizmaker.BaseIntegrationTest;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TagRepositoryTest extends BaseIntegrationTest {

    @Autowired
    private TagRepository tagRepository;

    @Test
    @DisplayName("findByNameInIgnoreCase matches case-insensitively and dedupes input")
    void findByNameInIgnoreCase_matchesAndDedupes() {
        Tag alpha = new Tag();
        alpha.setName("Alpha");
        Tag beta = new Tag();
        beta.setName("Beta");
        tagRepository.saveAll(List.of(alpha, beta));

        List<Tag> found = tagRepository.findByNameInIgnoreCase(List.of("alpha", "ALPHA", "beta", "missing"));

        assertThat(found).extracting(Tag::getName)
                .containsExactlyInAnyOrder("Alpha", "Beta");
    }

    @Test
    @DisplayName("findByNameInIgnoreCase returns empty list when nothing matches")
    void findByNameInIgnoreCase_emptyWhenNoMatch() {
        List<Tag> found = tagRepository.findByNameInIgnoreCase(List.of("ghost"));
        assertThat(found).isEmpty();
    }
}
