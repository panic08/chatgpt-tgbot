package ru.marthastudios.chatgptbot.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import ru.marthastudios.chatgptbot.entity.Subscription;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {
    @Modifying
    @Query("DELETE FROM Subscription s WHERE s.id = :id")
    void deleteByIdentify(long id);
}
