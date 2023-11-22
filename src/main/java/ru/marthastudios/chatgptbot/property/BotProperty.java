package ru.marthastudios.chatgptbot.property;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
public class BotProperty {
    @Value("${telegram.botName}")
    private String botUserName;

    @Value("${telegram.botToken}")
    private String token;
}
