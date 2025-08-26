package uk.gegc.quizmaker.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.user.application.impl.UserServiceImpl;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

@Execution(ExecutionMode.CONCURRENT)
@ExtendWith(MockitoExtension.class)
public class UserServiceTest {

    private static final UUID DEFAULT_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    @Mock
    private UserRepository userRepository;
    @InjectMocks
    private UserServiceImpl userService;

    @Test
    public void testCreateUser() {
        User user = new User();
        user.setUsername("johnDoe");
        user.setEmail("john@example.com");
        user.setActive(true);
        user.setHashedPassword("password");

        User savedUser = new User();
        savedUser.setId(DEFAULT_USER_ID);
        savedUser.setUsername("johnDoe");
        savedUser.setEmail("john@example.com");
        savedUser.setActive(true);
        savedUser.setHashedPassword("password");

        when(userRepository.save(user)).thenReturn(savedUser);

        User resultUser = userService.createUser(user);

        assertNotNull(resultUser.getId());
        assertEquals("johnDoe", resultUser.getUsername());
        assertEquals("john@example.com", resultUser.getEmail());
    }

}
