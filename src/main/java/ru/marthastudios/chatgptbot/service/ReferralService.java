package ru.marthastudios.chatgptbot.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.marthastudios.chatgptbot.entity.Referral;
import ru.marthastudios.chatgptbot.entity.User;
import ru.marthastudios.chatgptbot.repository.ReferralRepository;

@Service
@RequiredArgsConstructor
public class ReferralService {
    private final ReferralRepository referralRepository;
    private final UserService userService;

    public Referral create(Referral referral){
        return referralRepository.save(referral);
    }

    public boolean existsByTelegramUserId(long userId){
        return referralRepository.existsByTelegramUserId(userId);
    }

    @Transactional
    public void handleNewReferral(long userId, long referralUserId){
        if (userId == referralUserId){
            return;
        }

        if (referralRepository.existsByTelegramUserId(userId)){
            return;
        }

        if (userService.existsByTelegramUserId(userId)){
            return;
        }

        if (!userService.existsByTelegramUserId(referralUserId)){
            return;
        }

        Referral referral = Referral.builder()
                .telegramUserId(userId)
                .build();

        referralRepository.save(referral);

        User user1 = userService.getByTelegramUserId(referralUserId);

        user1.getUserData().setAvailableRequests(user1.getUserData().getAvailableRequests() + 5);
        user1.getUserData().setInvited(user1.getUserData().getInvited() + 1);

        userService.create(user1);
    }
}
