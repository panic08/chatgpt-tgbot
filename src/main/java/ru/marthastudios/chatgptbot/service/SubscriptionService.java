package ru.marthastudios.chatgptbot.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.marthastudios.chatgptbot.entity.Subscription;
import ru.marthastudios.chatgptbot.repository.SubscriptionRepository;

@Service
@RequiredArgsConstructor
public class SubscriptionService {
    private final SubscriptionRepository subscriptionRepository;

    @Transactional
    public void deleteById(Subscription subscription){
        subscriptionRepository.deleteByIdentify(subscription.getId());
    }
    public long getCount(){
        return subscriptionRepository.count();
    }
    @Transactional
    public Subscription create(Subscription subscription){
        return subscriptionRepository.save(subscription);
    }
}
