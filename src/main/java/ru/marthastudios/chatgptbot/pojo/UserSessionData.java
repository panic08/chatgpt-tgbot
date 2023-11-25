package ru.marthastudios.chatgptbot.pojo;

import lombok.*;
import ru.marthastudios.chatgptbot.dto.openai.ChatRequestDto;
import ru.marthastudios.chatgptbot.enums.UserSessionDataLanguage;
import ru.marthastudios.chatgptbot.enums.UserSessionDataRole;

import java.util.Map;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UserSessionData {
    private UserSessionDataLanguage language;
    private UserSessionDataRole role;
    private Map<Integer, ChatRequestDto.Message> messageHistory;
}
