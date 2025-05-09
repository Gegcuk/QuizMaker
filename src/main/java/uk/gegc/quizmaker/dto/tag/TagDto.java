package uk.gegc.quizmaker.dto.tag;

import java.util.UUID;

public record TagDto(UUID id, String name, String description) {
}
