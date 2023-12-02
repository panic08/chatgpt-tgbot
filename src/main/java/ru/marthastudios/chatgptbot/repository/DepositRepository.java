package ru.marthastudios.chatgptbot.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import ru.marthastudios.chatgptbot.entity.Deposit;

import java.util.List;

@Repository
public interface DepositRepository extends JpaRepository<Deposit, Long> {
    List<Deposit> findByTimestampGreaterThan(Long timestamp);
    @Query(
            value = "SELECT d.telegramUserId FROM Deposit d " +
                    "GROUP BY d.telegramUserId " +
                    "ORDER BY COUNT(d.id) DESC")
    List<Long> findTop10ByCountDeposits(Pageable pageable);
    int countByTelegramUserId(long telegramUserId);
}
