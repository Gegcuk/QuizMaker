package uk.gegc.quizmaker.features.attempt.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.navigation.FlatNavigation.Cursor;

@Schema(name = "AttemptNavigationPosition", description = "Saved SEQUENTIAL position, independent of answer/lifecycle version. Absent for FREE.")
public record NavigationPosition(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID currentFormItemId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1") long version) {
    public static NavigationPosition from(Cursor cursor) {
        return cursor == null ? null : new NavigationPosition(cursor.currentFormItemId(), cursor.version());
    }
}
