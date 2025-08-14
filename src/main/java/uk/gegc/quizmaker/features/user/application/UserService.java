package uk.gegc.quizmaker.service.user;

import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.user.domain.model.User;

@Service
public interface UserService {
    public User createUser(User user);
}
