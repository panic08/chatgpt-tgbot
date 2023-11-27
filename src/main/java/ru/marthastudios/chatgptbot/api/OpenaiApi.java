package ru.marthastudios.chatgptbot.api;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import ru.marthastudios.chatgptbot.dto.openai.AudioResponseDto;
import ru.marthastudios.chatgptbot.dto.openai.ChatRequestDto;
import ru.marthastudios.chatgptbot.dto.openai.ChatResponseDto;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

@Component
@RequiredArgsConstructor
public class OpenaiApi {
    private final RestTemplate restTemplate;
    @Value("${openai.apiKey}")
    private String openaiApiKey;
    private static final String OPENAI_CHAT_COMPLETION_URL = "https://api.openai.com/v1/chat/completions";
    private static final String OPENAI_AUDIO_TRANSCRIPTION_URL = "https://api.openai.com/v1/audio/transcriptions";

    public ChatResponseDto createChatCompletion(ChatRequestDto chatRequestDto){
        HttpHeaders headers = new HttpHeaders();

        headers.set("Authorization", "Bearer " + openaiApiKey);

        System.out.println(chatRequestDto.getModel());

        HttpEntity<ChatRequestDto> requestEntity = new HttpEntity<>(chatRequestDto, headers);

        ResponseEntity<ChatResponseDto> chatResponseDtoResponseEntity = restTemplate.postForEntity(OPENAI_CHAT_COMPLETION_URL, requestEntity, ChatResponseDto.class);

        return chatResponseDtoResponseEntity.getBody();
    }

    public AudioResponseDto createAudioTranscription(File audioFile, String lang) {
        HttpHeaders headers = new HttpHeaders();

        headers.set("Authorization", "Bearer " + openaiApiKey);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

        body.add("model", "whisper-1");
        body.add("language", lang);

        try {
            body.add("file", new FileSystemResource(audioFile.toPath()));
        } catch (Exception e){
            e.printStackTrace();
        }

        HttpEntity<MultiValueMap<String, Object>> requestEntity
                = new HttpEntity<>(body, headers);

        ResponseEntity<AudioResponseDto> audioResponseDtoResponseEntity = restTemplate.postForEntity(OPENAI_AUDIO_TRANSCRIPTION_URL, requestEntity, AudioResponseDto.class);

        return audioResponseDtoResponseEntity.getBody();
    }
}
