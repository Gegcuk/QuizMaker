package uk.gegc.quizmaker.features.quiz.infra.mapping;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.BaseUnitTest;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuizImportDto;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.UpsertStrategy;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.shared.exception.ValidationException;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("QuizImportAssembler Tests")
class QuizImportAssemblerTest extends BaseUnitTest {

    private QuizImportAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new QuizImportAssembler();
    }

    @Test
    @DisplayName("toEntity maps all DTO fields")
    void toEntity_mapsAllFields() {
        User creator = userWithId(UUID.randomUUID());
        Category category = categoryWithName("Science");
        Set<Tag> tags = tagsWithNames("biology", "advanced");
        QuizImportDto dto = new QuizImportDto(
                1,
                UUID.randomUUID(),
                "Cell Biology",
                "A quiz about cells",
                Visibility.PUBLIC,
                Difficulty.HARD,
                45,
                List.of("ignored"),
                "ignored",
                UUID.randomUUID(),
                null,
                Instant.now(),
                Instant.now()
        );

        Quiz quiz = assembler.toEntity(dto, creator, category, tags, UpsertStrategy.CREATE_ONLY);

        assertThat(quiz.getTitle()).isEqualTo("Cell Biology");
        assertThat(quiz.getDescription()).isEqualTo("A quiz about cells");
        assertThat(quiz.getVisibility()).isEqualTo(Visibility.PUBLIC);
        assertThat(quiz.getDifficulty()).isEqualTo(Difficulty.HARD);
        assertThat(quiz.getEstimatedTime()).isEqualTo(45);
    }

    @Test
    @DisplayName("toEntity sets creator to provided user")
    void toEntity_setsCreator() {
        User creator = userWithId(UUID.randomUUID());
        QuizImportDto dto = baseDto();

        Quiz quiz = assembler.toEntity(dto, creator, categoryWithName("Science"), tagsWithNames("tag"), UpsertStrategy.CREATE_ONLY);

        assertThat(quiz.getCreator()).isSameAs(creator);
    }

    @Test
    @DisplayName("toEntity ignores DTO creatorId")
    void toEntity_ignoresDtoCreatorId() {
        User creator = userWithId(UUID.randomUUID());
        UUID dtoCreatorId = UUID.randomUUID();
        QuizImportDto dto = dtoWithCreatorId(dtoCreatorId);

        Quiz quiz = assembler.toEntity(dto, creator, categoryWithName("Science"), tagsWithNames("tag"), UpsertStrategy.CREATE_ONLY);

        assertThat(quiz.getCreator()).isSameAs(creator);
        assertThat(quiz.getCreator().getId()).isNotEqualTo(dtoCreatorId);
    }

    @Test
    @DisplayName("toEntity sets category from resolved category")
    void toEntity_setsCategory() {
        Category category = categoryWithName("History");
        QuizImportDto dto = baseDto();

        Quiz quiz = assembler.toEntity(dto, userWithId(UUID.randomUUID()), category, tagsWithNames("tag"), UpsertStrategy.CREATE_ONLY);

        assertThat(quiz.getCategory()).isSameAs(category);
    }

    @Test
    @DisplayName("toEntity sets tags from resolved tags")
    void toEntity_setsTags() {
        Set<Tag> tags = tagsWithNames("math", "algebra");
        QuizImportDto dto = baseDto();

        Quiz quiz = assembler.toEntity(dto, userWithId(UUID.randomUUID()), categoryWithName("Science"), tags, UpsertStrategy.CREATE_ONLY);

        assertThat(quiz.getTags()).containsExactlyInAnyOrderElementsOf(tags);
        assertThat(quiz.getTags()).isNotSameAs(tags);
    }

    @Test
    @DisplayName("toEntity CREATE_ONLY ignores incoming ID")
    void toEntity_createOnly_ignoresId() {
        QuizImportDto dto = baseDto();

        Quiz quiz = assembler.toEntity(dto, userWithId(UUID.randomUUID()), categoryWithName("Science"), tagsWithNames("tag"), UpsertStrategy.CREATE_ONLY);

        assertThat(quiz.getId()).isNull();
    }

    @Test
    @DisplayName("toEntity UPSERT_BY_ID uses incoming ID")
    void toEntity_upsertById_withId_setsId() {
        UUID id = UUID.randomUUID();
        QuizImportDto dto = dtoWithId(id);

        Quiz quiz = assembler.toEntity(dto, userWithId(UUID.randomUUID()), categoryWithName("Science"), tagsWithNames("tag"), UpsertStrategy.UPSERT_BY_ID);

        assertThat(quiz.getId()).isEqualTo(id);
    }

    @Test
    @DisplayName("toEntity UPSERT_BY_ID without ID ignores")
    void toEntity_upsertById_withoutId_ignoresId() {
        QuizImportDto dto = dtoWithId(null);

        Quiz quiz = assembler.toEntity(dto, userWithId(UUID.randomUUID()), categoryWithName("Science"), tagsWithNames("tag"), UpsertStrategy.UPSERT_BY_ID);

        assertThat(quiz.getId()).isNull();
    }

    @Test
    @DisplayName("toEntity UPSERT_BY_CONTENT_HASH ignores incoming ID")
    void toEntity_upsertByHash_ignoresId() {
        QuizImportDto dto = baseDto();

        Quiz quiz = assembler.toEntity(dto, userWithId(UUID.randomUUID()), categoryWithName("Science"), tagsWithNames("tag"), UpsertStrategy.UPSERT_BY_CONTENT_HASH);

        assertThat(quiz.getId()).isNull();
    }

    @Test
    @DisplayName("toEntity defaults visibility to PRIVATE")
    void toEntity_nullVisibility_setsPrivate() {
        QuizImportDto dto = dtoWithVisibility(null);

        Quiz quiz = assembler.toEntity(dto, userWithId(UUID.randomUUID()), categoryWithName("Science"), tagsWithNames("tag"), UpsertStrategy.CREATE_ONLY);

        assertThat(quiz.getVisibility()).isEqualTo(Visibility.PRIVATE);
    }

    @Test
    @DisplayName("toEntity defaults difficulty to MEDIUM")
    void toEntity_nullDifficulty_setsMedium() {
        QuizImportDto dto = dtoWithDifficulty(null);

        Quiz quiz = assembler.toEntity(dto, userWithId(UUID.randomUUID()), categoryWithName("Science"), tagsWithNames("tag"), UpsertStrategy.CREATE_ONLY);

        assertThat(quiz.getDifficulty()).isEqualTo(Difficulty.MEDIUM);
    }

    @Test
    @DisplayName("toEntity rejects null estimated time")
    void toEntity_nullEstimatedTime_throwsException() {
        QuizImportDto dto = dtoWithEstimatedTime(null);

        assertThatThrownBy(() -> assembler.toEntity(dto, userWithId(UUID.randomUUID()), categoryWithName("Science"), tagsWithNames("tag"), UpsertStrategy.CREATE_ONLY))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Estimated time is required");
    }

    @Test
    @DisplayName("toEntity rejects null DTO")
    void toEntity_nullDto_throwsException() {
        assertThatThrownBy(() -> assembler.toEntity(null, userWithId(UUID.randomUUID()), categoryWithName("Science"), tagsWithNames("tag"), UpsertStrategy.CREATE_ONLY))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Quiz payload is required");
    }

    @Test
    @DisplayName("toEntity rejects null creator")
    void toEntity_nullCreator_throwsException() {
        QuizImportDto dto = baseDto();

        assertThatThrownBy(() -> assembler.toEntity(dto, null, categoryWithName("Science"), tagsWithNames("tag"), UpsertStrategy.CREATE_ONLY))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Creator is required");
    }

    @Test
    @DisplayName("toEntity rejects null category")
    void toEntity_nullCategory_throwsException() {
        QuizImportDto dto = baseDto();

        assertThatThrownBy(() -> assembler.toEntity(dto, userWithId(UUID.randomUUID()), null, tagsWithNames("tag"), UpsertStrategy.CREATE_ONLY))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Category is required");
    }

    private QuizImportDto baseDto() {
        return new QuizImportDto(
                1,
                UUID.randomUUID(),
                "Quiz Title",
                "Quiz Description",
                Visibility.PRIVATE,
                Difficulty.EASY,
                15,
                List.of("ignored"),
                "ignored",
                UUID.randomUUID(),
                null,
                Instant.now(),
                Instant.now()
        );
    }

    private QuizImportDto dtoWithCreatorId(UUID creatorId) {
        QuizImportDto base = baseDto();
        return new QuizImportDto(
                base.schemaVersion(),
                base.id(),
                base.title(),
                base.description(),
                base.visibility(),
                base.difficulty(),
                base.estimatedTime(),
                base.tags(),
                base.category(),
                creatorId,
                base.questions(),
                base.createdAt(),
                base.updatedAt()
        );
    }

    private QuizImportDto dtoWithId(UUID id) {
        QuizImportDto base = baseDto();
        return new QuizImportDto(
                base.schemaVersion(),
                id,
                base.title(),
                base.description(),
                base.visibility(),
                base.difficulty(),
                base.estimatedTime(),
                base.tags(),
                base.category(),
                base.creatorId(),
                base.questions(),
                base.createdAt(),
                base.updatedAt()
        );
    }

    private QuizImportDto dtoWithVisibility(Visibility visibility) {
        QuizImportDto base = baseDto();
        return new QuizImportDto(
                base.schemaVersion(),
                base.id(),
                base.title(),
                base.description(),
                visibility,
                base.difficulty(),
                base.estimatedTime(),
                base.tags(),
                base.category(),
                base.creatorId(),
                base.questions(),
                base.createdAt(),
                base.updatedAt()
        );
    }

    private QuizImportDto dtoWithDifficulty(Difficulty difficulty) {
        QuizImportDto base = baseDto();
        return new QuizImportDto(
                base.schemaVersion(),
                base.id(),
                base.title(),
                base.description(),
                base.visibility(),
                difficulty,
                base.estimatedTime(),
                base.tags(),
                base.category(),
                base.creatorId(),
                base.questions(),
                base.createdAt(),
                base.updatedAt()
        );
    }

    private QuizImportDto dtoWithEstimatedTime(Integer estimatedTime) {
        QuizImportDto base = baseDto();
        return new QuizImportDto(
                base.schemaVersion(),
                base.id(),
                base.title(),
                base.description(),
                base.visibility(),
                base.difficulty(),
                estimatedTime,
                base.tags(),
                base.category(),
                base.creatorId(),
                base.questions(),
                base.createdAt(),
                base.updatedAt()
        );
    }

    private QuizImportDto dtoWithDescription(String description) {
        QuizImportDto base = baseDto();
        return new QuizImportDto(
                base.schemaVersion(),
                base.id(),
                base.title(),
                description,
                base.visibility(),
                base.difficulty(),
                base.estimatedTime(),
                base.tags(),
                base.category(),
                base.creatorId(),
                base.questions(),
                base.createdAt(),
                base.updatedAt()
        );
    }

    private User userWithId(UUID id) {
        User user = new User();
        user.setId(id);
        user.setUsername("user");
        return user;
    }

    private Category categoryWithName(String name) {
        Category category = new Category();
        category.setName(name);
        return category;
    }

    private Set<Tag> tagsWithNames(String... names) {
        return List.of(names).stream()
                .map(name -> {
                    Tag tag = new Tag();
                    tag.setName(name);
                    return tag;
                })
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("toEntity creates empty set for null tags")
    void toEntity_nullTags_createsEmptySet() {
        QuizImportDto dto = baseDto();
        User creator = userWithId(UUID.randomUUID());
        Category category = categoryWithName("Category");

        Quiz quiz = assembler.toEntity(dto, creator, category, null, UpsertStrategy.CREATE_ONLY);

        assertThat(quiz.getTags()).isNotNull();
        assertThat(quiz.getTags()).isEmpty();
    }

    @Test
    @DisplayName("toEntity creates empty set for empty tags")
    void toEntity_emptyTags_createsEmptySet() {
        QuizImportDto dto = baseDto();
        User creator = userWithId(UUID.randomUUID());
        Category category = categoryWithName("Category");

        Quiz quiz = assembler.toEntity(dto, creator, category, new HashSet<>(), UpsertStrategy.CREATE_ONLY);

        assertThat(quiz.getTags()).isNotNull();
        assertThat(quiz.getTags()).isEmpty();
    }

    @Test
    @DisplayName("toEntity handles null description gracefully")
    void toEntity_nullDescription_handlesGracefully() {
        QuizImportDto dto = dtoWithDescription(null);
        User creator = userWithId(UUID.randomUUID());
        Category category = categoryWithName("Category");

        Quiz quiz = assembler.toEntity(dto, creator, category, null, UpsertStrategy.CREATE_ONLY);

        assertThat(quiz.getDescription()).isNull();
    }

    @Test
    @DisplayName("toEntity ignores ID for SKIP_ON_DUPLICATE strategy")
    void toEntity_skipOnDuplicate_ignoresId() {
        UUID quizId = UUID.randomUUID();
        QuizImportDto dto = dtoWithId(quizId);
        User creator = userWithId(UUID.randomUUID());
        Category category = categoryWithName("Category");

        Quiz quiz = assembler.toEntity(dto, creator, category, null, UpsertStrategy.SKIP_ON_DUPLICATE);

        assertThat(quiz.getId()).isNull();
    }
}
