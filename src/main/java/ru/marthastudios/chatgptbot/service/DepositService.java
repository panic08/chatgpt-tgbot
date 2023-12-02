package ru.marthastudios.chatgptbot.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import ru.marthastudios.chatgptbot.entity.Deposit;
import ru.marthastudios.chatgptbot.repository.DepositRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DepositService {
    private final DepositRepository depositRepository;

    @Transactional
    public Deposit create(Deposit deposit){
        return depositRepository.save(deposit);
    }

    public List<Deposit> getAll(){
        return depositRepository.findAll();
    }

    public List<Deposit> getAllGreaterThan(long milli){
        return depositRepository.findByTimestampGreaterThan(milli);
    }

    public List<Long> getTopUsersByDepositCount() {
        return depositRepository.findTop10ByCountDeposits(PageRequest.of(0, 10));
    }

    public int getCountByTelegramUserId(long telegramUserId){
        return depositRepository.countByTelegramUserId(telegramUserId);
    }
}
