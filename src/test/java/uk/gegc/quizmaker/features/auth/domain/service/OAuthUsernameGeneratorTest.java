package uk.gegc.quizmaker.features.auth.domain.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthUsernameGeneratorTest {

    @Mock
    private UserRepository userRepository;

    private OAuthUsernameGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new OAuthUsernameGenerator(userRepository);
    }

    @Test
    @DisplayName("generate: when name has spaces and symbols then sanitizes correctly")
    void generate_UsesSanitizedName() {
        when(userRepository.existsByUsername("johndoe")).thenReturn(false);

        String result = generator.generate(null, "John Doe!");

        assertThat(result).isEqualTo("johndoe");
    }

    @Test
    @DisplayName("generate: when name is unusable then falls back to email prefix")
    void generate_FallsBackToEmailPrefix() {
        when(userRepository.existsByUsername("useremail")).thenReturn(false);

        String result = generator.generate("user.email@example.com", "😊😊😊");

        assertThat(result).isEqualTo("useremail");
    }

    @Test
    @DisplayName("generate: when username exists then appends numeric suffix")
    void generate_AppendsSuffixWhenNecessary() {
        when(userRepository.existsByUsername("johndoe")).thenReturn(true);
        when(userRepository.existsByUsername("johndoe1")).thenReturn(false);

        String result = generator.generate(null, "John Doe");

        assertThat(result).isEqualTo("johndoe1");
    }
}
