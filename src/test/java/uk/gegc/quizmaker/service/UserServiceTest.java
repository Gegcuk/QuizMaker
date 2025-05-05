package uk.gegc.quizmaker.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.user.UserRepository;
import uk.gegc.quizmaker.service.user.impl.UserServiceImpl;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserServiceImpl userService;

    private static final UUID DEFAULT_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");


    @Test
    public void testCreateUser(){
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
