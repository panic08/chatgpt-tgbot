package ru.marthastudios.chatgptbot.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.marthastudios.chatgptbot.entity.Subscription;
import ru.marthastudios.chatgptbot.entity.User;
import ru.marthastudios.chatgptbot.repository.SubscriptionRepository;
import ru.marthastudios.chatgptbot.service.SubscriptionService;
import ru.marthastudios.chatgptbot.service.UserService;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class BonusScheduler {
    private final UserService userService;
    private final SubscriptionService subscriptionService;

    @Scheduled(cron = "0 0 0 * * *", zone = "Europe/Moscow")
    public void handleRestoreRequests() {
        List<User> users = userService.getAll();

        users.forEach(user -> {
            user.getUserData().setAvailableRequests(user.getUserData().getAvailableRequests() + 5);

            userService.create(user);
        });
    }

    @Scheduled(fixedRate = 300000)
    public void handleCheckingSubscriptions(){
        log.info("Starting handleCheckingSubscriptions method");

        List<User> usersWithSubscription = userService.getAllWithSubscription();

        usersWithSubscription.forEach(user -> {
            if (System.currentTimeMillis() >= user.getUserData().getSubscription().getExpiration()){
                subscriptionService.deleteById(user.getUserData().getSubscription());
            }
        });
    }
}
