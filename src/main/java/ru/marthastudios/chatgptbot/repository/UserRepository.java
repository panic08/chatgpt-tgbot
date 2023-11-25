package ru.marthastudios.chatgptbot.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.marthastudios.chatgptbot.entity.User;

import java.util.List;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    boolean existsByTelegramUserId(long telegramUserId);
    User findByTelegramChatId(long telegramChatId);
    User findByTelegramUserId(long telegramUserId);
    @Query("SELECT u FROM User u JOIN FETCH u.userData ud JOIN FETCH ud.subscription WHERE ud.subscription IS NOT NULL")
    List<User> findAllWithSubscription();


}
