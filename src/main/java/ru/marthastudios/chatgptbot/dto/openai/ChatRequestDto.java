package ru.marthastudios.chatgptbot.dto.openai;

import lombok.*;

import java.util.List;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
public class ChatRequestDto {
    private String model;
    private List<Message> messages;

    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    @Setter
    @Builder
    public static class Message{
        private String role;
        private String content;
    }
}
