package ru.marthastudios.chatgptbot.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.marthastudios.chatgptbot.entity.User;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    boolean existsByTelegramUserId(long telegramUserId);
    User findByTelegramUserId(long telegramUserId);
}
