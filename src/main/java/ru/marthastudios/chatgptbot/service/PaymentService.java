package ru.marthastudios.chatgptbot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.marthastudios.chatgptbot.entity.Deposit;
import ru.marthastudios.chatgptbot.entity.Subscription;
import ru.marthastudios.chatgptbot.entity.User;
import ru.marthastudios.chatgptbot.enums.DepositCurrency;
import ru.marthastudios.chatgptbot.enums.UserSessionDataLanguage;
import ru.marthastudios.chatgptbot.pojo.UserSessionData;
import ru.marthastudios.chatgptbot.property.AaioProperty;
import ru.marthastudios.chatgptbot.util.HexConverterUtil;
import ru.marthastudios.chatgptbot.util.SHA256EncoderUtil;

import java.text.DecimalFormat;
import java.util.regex.Pattern;

import static ru.marthastudios.chatgptbot.service.TelegramBotService.usersSessionsDataMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {
    private final AaioProperty aaioProperty;
    private final DepositService depositService;
    private final UserService userService;
    private final TelegramBotService telegramBotService;

    public void handlePayment(String merchantId, double amount, String orderId, String currency, String sign){
        DecimalFormat df = new DecimalFormat("#.00");
        String formattedNumber = df.format(amount);

        String secSign = merchantId + ":" + formattedNumber + ":" + currency + ":" + aaioProperty.getSecondSecretKey() + ":" + orderId;
        secSign = SHA256EncoderUtil.encrypt(secSign);

        if (!secSign.equals(sign)){
            log.warn("Failed attempt to make a payment");
            return;
        }
        String[] strings = HexConverterUtil.fromHex(orderId).split(Pattern.quote("||"));

        User user = userService.getById(Long.parseLong(strings[0]));

        int count = Integer.parseInt(strings[3]);
        String currency1 = strings[2];

        int days = 0;

        switch (count){
            case 1 -> days += 7;
            case 2 -> days += 30;
            case 3 -> days += 90;
        }

        if (user.getUserData().getSubscription() == null){
            Subscription subscription = Subscription.builder()
                    .userData(user.getUserData())
                    .expiration(System.currentTimeMillis() + (days * 24 * 60 * 60 * 1000L))
                    .build();

            user.getUserData().setSubscription(subscription);

        } else {
            user.getUserData().getSubscription().setExpiration(user.getUserData().getSubscription().getExpiration() + (days * 24 * 60 * 60 * 1000L));
        }

        userService.create(user);


        DepositCurrency depositCurrency = null;

        if (currency1.equals("RUB")){
            depositCurrency = DepositCurrency.RUB;
        } else {
            depositCurrency = DepositCurrency.USD;
        }

        Deposit deposit = Deposit.builder()
                .telegramUserId(user.getTelegramUserId())
                .currency(depositCurrency)
                .amount(amount)
                .timestamp(System.currentTimeMillis())
                .build();

        depositService.create(deposit);

        UserSessionData userSessionData = usersSessionsDataMap.get(user.getTelegramChatId());
        UserSessionDataLanguage language;

        String messageText = null;

        if (userSessionData == null){
            language = UserSessionDataLanguage.RU;
        } else {
            language = userSessionData.getLanguage();
        }

        switch (language){
            case RU -> messageText = "✅ <b>Номер платежа:</b> <code>#" + orderId + "</code>. <b>Вы успешно оплатили подписку на</b> " + days + " <b>дней. Наслаждайтесь!</b>";
            case EN -> messageText = "✅ <b>Payment number:</b> <code>#" + orderId + "</code>. <b>You have successfully paid a subscription for</b> " + days + " <b>days. Enjoy!</b>";
            case UA -> messageText = "✅ <b>Номер платежу:</b> <code>#" + orderId + "</code>. <b>Ви успішно оплатили підписку на</b> " + days + " <b>днів. Насолоджуйтеся!</b>";
        }

        SendMessage message = SendMessage.builder()
                .chatId(user.getTelegramChatId())
                .text(messageText)
                .parseMode("html")
                .build();

        try {
            telegramBotService.execute(message);
        } catch (TelegramApiException e){
            e.printStackTrace();
        }
    }
}
