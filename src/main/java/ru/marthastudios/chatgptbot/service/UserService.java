package ru.marthastudios.chatgptbot.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.marthastudios.chatgptbot.entity.User;
import ru.marthastudios.chatgptbot.repository.UserRepository;

import java.util.List;

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

    @Transactional
    public List<User> createAll(List<User> users){
        return userRepository.saveAll(users);
    }

    public User getByTelegramChatId(long telegramChatId){
        return userRepository.findByTelegramChatId(telegramChatId);
    }

    public User getByTelegramUserId(long telegramUserId){
        return userRepository.findByTelegramUserId(telegramUserId);
    }
    public User getById(long id){
        return userRepository.findById(id).orElseThrow();
    }

    public List<User> getAll(){
        return userRepository.findAll();
    }

    public long getCount(){
        return userRepository.count();
    }

    public List<User> getAllWithSubscription(){
        return userRepository.findAllWithSubscription();
    }
}
