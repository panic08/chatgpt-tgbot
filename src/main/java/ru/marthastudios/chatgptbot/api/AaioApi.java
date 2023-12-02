package ru.marthastudios.chatgptbot.api;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.marthastudios.chatgptbot.property.AaioProperty;
import ru.marthastudios.chatgptbot.util.SHA256EncoderUtil;

@Component
@RequiredArgsConstructor
public class AaioApi {
    private final AaioProperty aaioProperty;
    private static final String AAIO_BASIC_REDIRECT_URL = "https://aaio.io/merchant/pay";
    public String getRedirectUrl(String orderId, double amount, String currency, String lang){
        String redirectUrl = AAIO_BASIC_REDIRECT_URL + "?merchant_id=" + aaioProperty.getMerchantId()
                + "&amount=" + amount
                + "&order_id=" + orderId
                + "&currency=" + currency
                + "&lang=" + lang;

        String encodedSign = SHA256EncoderUtil.encrypt(aaioProperty.getMerchantId() + ":" + amount + ":" + currency
        + ":" + aaioProperty.getFirstSecretKey() + ":" + orderId);

        redirectUrl += ("&sign=" + encodedSign);

        return redirectUrl;
    }
}
