package uk.gegc.quizmaker.dto.quiz;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.model.question.Difficulty;

import java.util.List;

@Schema(name = "QuizSearchCriteria", description = "Filter criteria for listing quizzes")
public record QuizSearchCriteria(

        @Parameter(
                in = ParameterIn.QUERY,
                name = "category",
                description = "Filter by category names (comma‐delimited)"
        )
        @ArraySchema(schema = @Schema(type = "string"))
        List<String> category,

        @Parameter(
                in = ParameterIn.QUERY,
                name = "tag",
                description = "Filter by tag names (comma‐delimited)"
        )
        @ArraySchema(schema = @Schema(type = "string"))
        List<String> tag,

        @Parameter(
                in = ParameterIn.QUERY,
                name = "authorName",
                description = "Filter by author username"
        )
        String authorName,

        @Parameter(
                in = ParameterIn.QUERY,
                name = "search",
                description = "Full‐text search on title/description"
        )
        String search,

        @Parameter(
                in = ParameterIn.QUERY,
                name = "difficulty",
                description = "Filter by quiz difficulty"
        )
        Difficulty difficulty

) {
}
