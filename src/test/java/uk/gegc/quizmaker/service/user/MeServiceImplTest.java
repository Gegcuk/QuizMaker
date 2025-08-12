package uk.gegc.quizmaker.service.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import uk.gegc.quizmaker.dto.user.MeResponse;
import uk.gegc.quizmaker.model.user.Role;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.user.UserRepository;
import uk.gegc.quizmaker.service.user.impl.MeServiceImpl;

import java.util.List;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class MeServiceImplTest {

    @Test
    @DisplayName("Throws 401 when not authenticated")
    void notAuthenticated_Throws401() {
        UserRepository repo = Mockito.mock(UserRepository.class);
        MeServiceImpl service = new MeServiceImpl(repo);

        Authentication auth = null;
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getCurrentUserProfile(auth));
        assertEquals(401, ex.getStatusCode().value());
    }

    @Test
    @DisplayName("Throws 401 when user not found (stale token)")
    void notFound_Throws404() {
        UserRepository repo = Mockito.mock(UserRepository.class);
        when(repo.findByUsernameWithRoles(anyString())).thenReturn(Optional.empty());
        when(repo.findByEmailWithRoles(anyString())).thenReturn(Optional.empty());
        MeServiceImpl service = new MeServiceImpl(repo);

        Authentication auth = new UsernamePasswordAuthenticationToken("missing@example.com", "N/A", List.of());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getCurrentUserProfile(auth));
        assertEquals(401, ex.getStatusCode().value());
    }

    @Test
    @DisplayName("Returns profile for existing user")
    void returnsProfile() {
        UserRepository repo = Mockito.mock(UserRepository.class);
        User u = new User();
        u.setUsername("alice");
        u.setEmail("alice@example.com");
        u.setActive(true);
        u.setDeleted(false);
        u.setRoles(Set.of(new Role()));
        when(repo.findByUsernameWithRoles(anyString())).thenReturn(Optional.of(u));
        MeServiceImpl service = new MeServiceImpl(repo);

        Authentication auth = new UsernamePasswordAuthenticationToken("alice", "N/A", List.of());
        MeResponse r = service.getCurrentUserProfile(auth);
        assertEquals("alice", r.username());
        assertEquals("alice@example.com", r.email());
    }
}


