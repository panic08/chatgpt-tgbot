package ru.marthastudios.chatgptbot.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.marthastudios.chatgptbot.entity.User;
import ru.marthastudios.chatgptbot.repository.UserRepository;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;

    public boolean existsByTelegramUserId(long telegramUserId){
        return userRepository.existsByTelegramUserId(telegramUserId);
    }

    @Transactional
    public User create(User user){
        return userRepository.save(user);
    }

    public User getByTelegramUserId(long telegramUserId){
        return userRepository.findByTelegramUserId(telegramUserId);
    }
}
