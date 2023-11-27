package ru.marthastudios.chatgptbot.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.marthastudios.chatgptbot.entity.User;
import ru.marthastudios.chatgptbot.enums.UserSessionDataLanguage;
import ru.marthastudios.chatgptbot.pojo.UserSessionData;
import ru.marthastudios.chatgptbot.service.SubscriptionService;
import ru.marthastudios.chatgptbot.service.TelegramBotService;
import ru.marthastudios.chatgptbot.service.UserService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static ru.marthastudios.chatgptbot.service.TelegramBotService.usersSessionsDataMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class BonusScheduler {
    private final UserService userService;
    private final SubscriptionService subscriptionService;
    private final TelegramBotService telegramBotService;

    @Scheduled(cron = "0 0 0 * * *", zone = "Europe/Moscow")
    public void handleRestoreRequests() {
        List<User> users = userService.getAll();

        users.forEach(user -> {
            user.getUserData().setAvailableRequests(user.getUserData().getAvailableRequests() + 5);

            userService.create(user);
        });
    }

    @Scheduled(fixedRate = 300000)
    public void handleCheckingSubscriptions(){
        log.info("Starting handleCheckingSubscriptions method");

        List<User> usersWithSubscription = userService.getAllWithSubscription();

        usersWithSubscription.forEach(user -> {
            if (System.currentTimeMillis() >= user.getUserData().getSubscription().getExpiration()){
                UserSessionData userSessionData = usersSessionsDataMap.get(user.getTelegramChatId());
                UserSessionDataLanguage language;

                if (userSessionData != null){
                    language = userSessionData.getLanguage();

                    userSessionData.setMessageHistory(new HashMap<>());
                    userSessionData.setModel("gpt-3.5-turbo");

                    usersSessionsDataMap.put(user.getTelegramChatId(), userSessionData);
                } else {
                    language = UserSessionDataLanguage.RU;
                }

                subscriptionService.deleteById(user.getUserData().getSubscription());

                String messageText = null;

                String sevenDaysButtonText = null;
                String thirtyButtonText = null;
                String ninetyButtonText = null;

                switch (language){
                    case RU -> {
                        messageText = "\uD83D\uDD1A <b>Ваша подписка закончилась. Если вы хотите продолжить пользоваться ботом, подпишитесь снова!</b>";

                        sevenDaysButtonText = "\uD83D\uDCC5 Безлимит на 7 дней - 199 ₽";
                        thirtyButtonText = "\uD83D\uDCC5 Безлимит на 30 дней - 449 ₽";
                        ninetyButtonText = "\uD83D\uDCC5 Безлимит на 90 дней - 1199 ₽";
                    }
                    case UA -> {
                        messageText = "\uD83D\uDD1A <b>Ваша підписка закінчилася. Якщо ви хочете продовжити користуватися ботом, підпишіться знову!</b>";

                        sevenDaysButtonText = "\uD83D\uDCC5 Безлiмiт на 7 днiв - 7 $";
                        thirtyButtonText = "\uD83D\uDCC5 Безлiмiт на 30 днiв - 20 $";
                        ninetyButtonText = "\uD83D\uDCC5 Безлiмiт на 90 днiв - 40 $";
                    }
                    case EN -> {
                        messageText = "\uD83D\uDD1A <b>Your subscription has expired. If you want to continue using the bot, subscribe again!</b>";

                        sevenDaysButtonText = "\uD83D\uDCC5 Unlimited for 7 days - 7 $";
                        thirtyButtonText = "\uD83D\uDCC5 Unlimited for 30 days - 20 $";
                        ninetyButtonText = "\uD83D\uDCC5 Unlimited for 90 days - 40 $";
                    }
                }

                InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();
                //todo
                InlineKeyboardButton sevenButton = InlineKeyboardButton.builder()
                        .callbackData("dd")
                        .text(sevenDaysButtonText)
                        .build();

                InlineKeyboardButton thirtyButton = InlineKeyboardButton.builder()
                        .callbackData("dd")
                        .text(thirtyButtonText)
                        .build();

                InlineKeyboardButton ninetyButton = InlineKeyboardButton.builder()
                        .callbackData("dd")
                        .text(ninetyButtonText)
                        .build();

                List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();
                List<InlineKeyboardButton> keyboardButtonsRow2 = new ArrayList<>();
                List<InlineKeyboardButton> keyboardButtonsRow3 = new ArrayList<>();

                keyboardButtonsRow1.add(sevenButton);
                keyboardButtonsRow2.add(thirtyButton);
                keyboardButtonsRow3.add(ninetyButton);

                List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                rowList.add(keyboardButtonsRow1);
                rowList.add(keyboardButtonsRow2);
                rowList.add(keyboardButtonsRow3);

                inlineKeyboardMarkup.setKeyboard(rowList);

                SendMessage message = SendMessage.builder()
                        .text(messageText)
                        .replyMarkup(inlineKeyboardMarkup)
                        .chatId(user.getTelegramChatId())
                        .parseMode("html")
                        .build();

                try {
                    telegramBotService.execute(message);
                } catch (TelegramApiException e){
                    e.printStackTrace();
                }
            }
        });
    }
}
