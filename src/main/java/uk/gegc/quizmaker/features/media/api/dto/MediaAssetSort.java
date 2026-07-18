package uk.gegc.quizmaker.features.media.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Supported sort fields for media-library search")
public enum MediaAssetSort {
    CREATED_AT("createdAt"),
    UPDATED_AT("updatedAt"),
    ORIGINAL_FILENAME("originalFilename"),
    SIZE_BYTES("sizeBytes");

    private final String property;

    MediaAssetSort(String property) {
        this.property = property;
    }

    public String property() {
        return property;
    }
}
