package uk.gegc.quizmaker.features.quiz.domain.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("VersionCodeGenerator Tests")
class VersionCodeGeneratorTest {

    @Test
    @DisplayName("generateVersionCode: generates 6-character alphanumeric code")
    void generateVersionCode_returns6CharacterCode() {
        // Given
        UUID uuid = UUID.randomUUID();

        // When
        String versionCode = VersionCodeGenerator.generateVersionCode(uuid);

        // Then
        assertThat(versionCode).hasSize(6);
        assertThat(versionCode).matches("[0-9A-Z]{6}");
    }

    @Test
    @DisplayName("generateVersionCode: produces consistent output for same UUID")
    void generateVersionCode_consistentForSameUuid() {
        // Given
        UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

        // When
        String code1 = VersionCodeGenerator.generateVersionCode(uuid);
        String code2 = VersionCodeGenerator.generateVersionCode(uuid);

        // Then
        assertThat(code1).isEqualTo(code2);
    }

    @Test
    @DisplayName("generateVersionCode: produces different codes for different UUIDs")
    void generateVersionCode_differentForDifferentUuids() {
        // Given
        UUID uuid1 = UUID.randomUUID();
        UUID uuid2 = UUID.randomUUID();

        // When
        String code1 = VersionCodeGenerator.generateVersionCode(uuid1);
        String code2 = VersionCodeGenerator.generateVersionCode(uuid2);

        // Then
        assertThat(code1).isNotEqualTo(code2);
    }

    @Test
    @DisplayName("generateVersionCode: generates unique codes for multiple UUIDs")
    void generateVersionCode_uniqueCodesForMultipleUuids() {
        // Given
        int count = 1000;
        Set<String> codes = new HashSet<>();

        // When
        for (int i = 0; i < count; i++) {
            String code = VersionCodeGenerator.generateVersionCode(UUID.randomUUID());
            codes.add(code);
        }

        // Then
        // Allow for some collisions (birthday paradox), but should be rare
        assertThat(codes).hasSizeGreaterThan(count * 99 / 100); // >99% unique
    }

    @Test
    @DisplayName("generateVersionCode: throws exception for null UUID")
    void generateVersionCode_throwsExceptionForNull() {
        // When/Then
        assertThatThrownBy(() -> VersionCodeGenerator.generateVersionCode(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("UUID cannot be null");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "00000000-0000-0000-0000-000000000000",
            "ffffffff-ffff-ffff-ffff-ffffffffffff",
            "12345678-1234-1234-1234-123456789012",
            "abcdef12-3456-7890-abcd-ef1234567890"
    })
    @DisplayName("generateVersionCode: handles edge case UUIDs")
    void generateVersionCode_handlesEdgeCaseUuids(String uuidString) {
        // Given
        UUID uuid = UUID.fromString(uuidString);

        // When
        String versionCode = VersionCodeGenerator.generateVersionCode(uuid);

        // Then
        assertThat(versionCode).hasSize(6);
        assertThat(versionCode).matches("[0-9A-Z]{6}");
    }

    @Test
    @DisplayName("generateVersionCode: generates all uppercase code")
    void generateVersionCode_generatesUppercaseCode() {
        // Given
        UUID uuid = UUID.randomUUID();

        // When
        String versionCode = VersionCodeGenerator.generateVersionCode(uuid);

        // Then
        assertThat(versionCode).isEqualTo(versionCode.toUpperCase());
    }

    @Test
    @DisplayName("generateVersionCode: code is different from UUID string")
    void generateVersionCode_differentFromUuidString() {
        // Given
        UUID uuid = UUID.randomUUID();

        // When
        String versionCode = VersionCodeGenerator.generateVersionCode(uuid);

        // Then
        assertThat(versionCode).isNotEqualTo(uuid.toString());
        assertThat(versionCode).isNotEqualTo(uuid.toString().substring(0, 6));
    }
}

