package uk.gegc.quizmaker.service.service;

import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.model.userManagment.User;

@Service
public interface UserService {
    public User createUser(User user);
}
