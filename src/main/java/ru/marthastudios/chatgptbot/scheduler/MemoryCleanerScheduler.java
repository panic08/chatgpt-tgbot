package ru.marthastudios.chatgptbot.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;

import static ru.marthastudios.chatgptbot.service.TelegramBotService.usersCooldownsMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class MemoryCleanerScheduler {

    @Scheduled(fixedRate = 15 * 60 * 60 * 1000)
    public void handleMemoryClean(){
        log.info("Starting handleMemoryClean method");

        usersCooldownsMap = new HashMap<>();
    }
}
