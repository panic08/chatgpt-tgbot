package ru.marthastudios.chatgptbot.property;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
public class AaioProperty {
    @Value("${aaio.secretKeyFirst}")
    private String firstSecretKey;

    @Value("${aaio.secretKeySecond}")
    private String secondSecretKey;

    @Value("${aaio.merchantId}")
    private String merchantId;
}
