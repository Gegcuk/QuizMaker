package uk.gegc.quizmaker.dto.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MeResponseValidationTest {

    @Test
    @DisplayName("MeResponse should hold provided values")
    void meResponse_HoldsValues() {
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        MeResponse r = new MeResponse(id, "alice", "alice@example.com", "Alice", "bio", "https://avatar",
                Map.of("theme", "dark"), now, true, List.of("USER"));

        assertEquals(id, r.id());
        assertEquals("alice", r.username());
        assertEquals("alice@example.com", r.email());
        assertEquals("Alice", r.displayName());
        assertEquals("bio", r.bio());
        assertEquals("https://avatar", r.avatarUrl());
        assertEquals("dark", r.preferences().get("theme"));
        assertEquals(now, r.joinedAt());
        assertTrue(r.verified());
        assertEquals(List.of("USER"), r.roles());
    }
}


