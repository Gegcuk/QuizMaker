package uk.gegc.quizmaker.dto;

import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.dto.user.AvatarUploadResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AvatarUploadResponseTest {
    @Test
    void recordHoldsValues() {
        AvatarUploadResponse r = new AvatarUploadResponse("http://u", "ok");
        assertEquals("http://u", r.avatarUrl());
        assertEquals("ok", r.message());
    }
}


