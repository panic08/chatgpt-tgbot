package ru.marthastudios.chatgptbot.dto.openai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;
import ru.marthastudios.chatgptbot.dto.openai.parent.ChatRequest;

import java.util.List;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
public class ChatRequestWithImageDto extends ChatRequest {
    private String model;
    @JsonProperty("max_tokens")
    private Integer maxTokens;
    private List<Message> messages;

    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    @Setter
    @Builder
    public static class Message{
        private String role;
        @JsonProperty("content")
        private List<Content> contents;
    }
    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    @Setter
    @Builder
    public static class Content{
        private String type;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String text;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("image_url")
        private ImageUrl imageUrl;
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Getter
    @Setter
    @Builder
    public static class ImageUrl{
        private String url;
    }
}
