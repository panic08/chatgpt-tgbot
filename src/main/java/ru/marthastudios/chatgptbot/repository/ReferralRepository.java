package ru.marthastudios.chatgptbot.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.marthastudios.chatgptbot.entity.Referral;

@Repository
public interface ReferralRepository extends JpaRepository<Referral, Long> {
    boolean existsByTelegramUserId(long userId);
}
