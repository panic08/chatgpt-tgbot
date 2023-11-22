package ru.marthastudios.chatgptbot.pojo;

import lombok.*;
import ru.marthastudios.chatgptbot.enums.UserSessionDataLanguage;
import ru.marthastudios.chatgptbot.enums.UserSessionDataRole;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UserSessionData {
    private UserSessionDataLanguage language;
    private UserSessionDataRole role;
}
