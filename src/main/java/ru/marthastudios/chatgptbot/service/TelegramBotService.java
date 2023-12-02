package ru.marthastudios.chatgptbot.service;

import com.google.common.util.concurrent.AtomicDouble;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.ActionType;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.methods.send.*;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.marthastudios.chatgptbot.api.AaioApi;
import ru.marthastudios.chatgptbot.api.OpenaiApi;
import ru.marthastudios.chatgptbot.callback.*;
import ru.marthastudios.chatgptbot.dto.openai.ChatRequestDto;
import ru.marthastudios.chatgptbot.dto.openai.ChatRequestWithImageDto;
import ru.marthastudios.chatgptbot.dto.openai.ChatResponseDto;
import ru.marthastudios.chatgptbot.entity.Deposit;
import ru.marthastudios.chatgptbot.entity.Subscription;
import ru.marthastudios.chatgptbot.entity.User;
import ru.marthastudios.chatgptbot.entity.UserData;
import ru.marthastudios.chatgptbot.enums.DepositCurrency;
import ru.marthastudios.chatgptbot.enums.UserRole;
import ru.marthastudios.chatgptbot.enums.UserSessionDataLanguage;
import ru.marthastudios.chatgptbot.enums.UserSessionDataRole;
import ru.marthastudios.chatgptbot.pojo.CustomPair;
import ru.marthastudios.chatgptbot.pojo.UserSessionData;
import ru.marthastudios.chatgptbot.property.BotProperty;
import ru.marthastudios.chatgptbot.util.*;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class TelegramBotService extends TelegramLongPollingBot {
    @Autowired
    private BotProperty botProperty;
    @Autowired
    private UserService userService;
    @Autowired
    private AaioApi aaioApi;
    @Autowired
    private ReferralService referralService;
    @Autowired
    private SubscriptionService subscriptionService;
    @Autowired
    private DepositService  depositService;
    @Autowired
    private OpenaiApi openaiApi;
    public static final Map<Long, UserSessionData> usersSessionsDataMap = new HashMap<>();
    public static Map<Long, Long> usersCooldownsMap = new HashMap<>();
    private static final Map<Long, CustomPair<Integer, Long>> giveSubscriptionSteps = new HashMap<>();
    private static final Set<Long> giveAllSubscriptionSteps = new HashSet<>();
    private static final Set<Long> takeSubscriptionSteps = new HashSet<>();
    private static final Set<Long> banUserSteps = new HashSet<>();
    private static final Set<Long> unbanUserSteps = new HashSet<>();
    private static final Set<Long> giveAdminSteps = new HashSet<>();
    private static final Set<Long> takeAdminSteps = new HashSet<>();
    private static final Set<Long> resendAdminSteps = new HashSet<>();


    public TelegramBotService(BotProperty botProperty) {
        this.botProperty = botProperty;
        List<BotCommand> listOfCommands = new ArrayList<>();

        listOfCommands.add(new BotCommand("/start", "Перезапустить | Restart"));
        listOfCommands.add(new BotCommand("/profile", "Профиль | Profile"));
        listOfCommands.add(new BotCommand("/pay", "Купить подписку | Buy subscription"));
        listOfCommands.add(new BotCommand("/support", "Поддержка | Support"));
        listOfCommands.add(new BotCommand("/reset", "Сбросить контекст | Drop context"));

        try {
            this.execute(new SetMyCommands(listOfCommands, new BotCommandScopeDefault(), null));
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    @Override
    public String getBotUsername() {
        return botProperty.getBotUserName();
    }

    @Override
    public String getBotToken() {
        return botProperty.getToken();
    }

    @Override
    public void onUpdateReceived(Update update) {
        new Thread(() -> {
            if (update.hasMessage() && update.getMessage().hasText()) {
                long chatId = update.getMessage().getChatId();
                String text = update.getMessage().getText();

                if (text.contains("/start") && text.split(" ").length > 1) {
                    String[] referralTelegramUserIdSplit = text.split(" ");


                    if (referralTelegramUserIdSplit[0].equals("/start")) {
                        referralService.handleNewReferral(update.getMessage().getFrom().getId(), Long.parseLong(referralTelegramUserIdSplit[1]));

                        GetChatMember getChatMember = new GetChatMember(botProperty.getChannelChatId(), update.getMessage().getFrom().getId());

                        ChatMember chatMember = null;

                        try {
                            chatMember = execute(getChatMember);
                        } catch (TelegramApiException ignored) {
                        }

                        if (chatMember.getStatus().equals("left")) {
                            sendUnsubscribedMessage(chatId);
                            return;
                        }
                        return;
                    }

                }

                GetChatMember getChatMember = new GetChatMember(botProperty.getChannelChatId(), update.getMessage().getFrom().getId());

                ChatMember chatMember = null;

                try {
                    chatMember = execute(getChatMember);
                } catch (TelegramApiException ignored) {
                }

                if (chatMember.getStatus().equals("left")) {
                    sendUnsubscribedMessage(chatId);
                    return;
                }

                User user = userService.getByTelegramChatId(chatId);

                if (user == null) {
                    user = User.builder()
                            .telegramUserId(update.getMessage().getFrom().getId())
                            .telegramChatId(chatId)
                            .role(UserRole.DEFAULT)
                            .isAccountNonLocked(true)
                            .timestamp(System.currentTimeMillis())
                            .build();

                    UserData userData = UserData.builder()
                            .user(user)
                            .availableRequests(5)
                            .invited(0)
                            .build();

                    user.setUserData(userData);

                    userService.create(user);
                }

                if (!user.getIsAccountNonLocked()) {
                    UserSessionData userSessionData = usersSessionsDataMap.get(chatId);
                    UserSessionDataLanguage language = null;

                    if (userSessionData == null) {
                        language = UserSessionDataLanguage.RU;
                    } else {
                        language = userSessionData.getLanguage();
                    }

                    String banMessageText = null;

                    switch (language) {
                        case RU -> {
                            banMessageText = "\uD83D\uDEAB <b>Вы были заблокированы в этом боте</b>";
                        }
                        case UA -> {
                            banMessageText = "\uD83D\uDEAB <b>Ви були заблоковані в цьому боті</b>";
                        }
                        case EN -> {
                            banMessageText = "\uD83D\uDEAB <b>You have been blocked from this bot</b>";
                        }
                    }

                    SendMessage banMessage = SendMessage.builder()
                            .chatId(chatId)
                            .text(banMessageText)
                            .parseMode("html")
                            .build();

                    try {
                        execute(banMessage);
                    } catch (TelegramApiException e) {
                        e.printStackTrace();
                    }

                    return;
                }

                switch (text) {
                    case "/start" -> {
                        handleStartMessage(chatId);
                        return;
                    }

                    case "/profile", "\uD83D\uDC64 Профиль", "\uD83D\uDC64 Profile", "\uD83D\uDC64 Профіль" -> {

                        handleProfileMessage(user, chatId);
                        return;
                    }

                    case "/pay", "\uD83D\uDCB3 Пiдписка", "\uD83D\uDCB3 Подписка", "\uD83D\uDCB3 Subscription" -> {
                        handlePayMessage(chatId);
                        return;
                    }

                    case "/support" -> {
                        handleSupportMessage(update.getMessage().getFrom().getId(), chatId);
                        return;
                    }

                    case "/reset", "\uD83D\uDD04 Сбросить контекст", "\uD83D\uDD04 Скинути контекст", "\uD83D\uDD04 Drop chat context" -> {
                        handleResetMessage(chatId);
                        return;
                    }

                    case "\uD83C\uDFAD Choose a role", "\uD83C\uDFAD Выбрать роль", "\uD83C\uDFAD Вибрати роль" -> {
                        handleChooseRole(chatId);
                        return;
                    }
                    case "\uD83D\uDD04 Поменять модель", "\uD83D\uDD04 Змінити модель", "\uD83D\uDD04 Change model" -> {
                        handleChangeModel(user, chatId);
                        return;
                    }
                    case "\uD83D\uDED1 Админ-панель" -> {
                        handleAdminMessage(user, chatId);
                        return;
                    }
                }

                if (giveSubscriptionSteps.get(chatId) != null) {
                    CustomPair<Integer, Long> pair = giveSubscriptionSteps.get(chatId);

                    int step = pair.getFirstValue();

                    switch (step) {
                        case 0 -> {
                            long telegramUserId = Long.parseLong(text);

                            User user1 = userService.getByTelegramUserId(telegramUserId);

                            if (user1 == null) {
                                InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                                InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                                        .callbackData(BackCallback.BACK_ADMIN_CALLBACK_DATA)
                                        .text("\uD83D\uDD19 Назад")
                                        .build();

                                List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                                keyboardButtonsRow1.add(backButton);

                                List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                                rowList.add(keyboardButtonsRow1);

                                inlineKeyboardMarkup.setKeyboard(rowList);

                                SendMessage message = SendMessage.builder()
                                        .text("\uD83D\uDEAB <b>Пользователя с таким идентификатором не существует</b>")
                                        .chatId(chatId)
                                        .replyMarkup(inlineKeyboardMarkup)
                                        .parseMode("html")
                                        .build();

                                giveSubscriptionSteps.remove(chatId);

                                try {
                                    execute(message);
                                } catch (TelegramApiException e) {
                                    e.printStackTrace();
                                }
                                return;
                            }

                            if (user1.getUserData().getSubscription() != null) {
                                InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                                InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                                        .callbackData(BackCallback.BACK_ADMIN_CALLBACK_DATA)
                                        .text("\uD83D\uDD19 Назад")
                                        .build();

                                List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                                keyboardButtonsRow1.add(backButton);

                                List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                                rowList.add(keyboardButtonsRow1);

                                inlineKeyboardMarkup.setKeyboard(rowList);

                                SendMessage message = SendMessage.builder()
                                        .text("\uD83D\uDCB3 <b>Этот пользователь уже имеет подписку</b>")
                                        .chatId(chatId)
                                        .replyMarkup(inlineKeyboardMarkup)
                                        .parseMode("html")
                                        .build();

                                giveSubscriptionSteps.remove(chatId);

                                try {
                                    execute(message);
                                } catch (TelegramApiException e) {
                                    e.printStackTrace();
                                }
                                return;
                            }

                            pair.setSecondValue(telegramUserId);

                            pair.setFirstValue(1);

                            giveSubscriptionSteps.put(chatId, pair);

                            InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                            InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                                    .callbackData(BackCallback.BACK_ADMIN_GIVE_SUBSCRIPTION_CALLBACK_DATA)
                                    .text("\uD83D\uDD19 Назад")
                                    .build();

                            List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                            keyboardButtonsRow1.add(backButton);

                            List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                            rowList.add(keyboardButtonsRow1);

                            inlineKeyboardMarkup.setKeyboard(rowList);

                            SendMessage message = SendMessage.builder()
                                    .text("\uD83D\uDD22 <b>Введите количество дней, на сколько вы хотите выдать подписку</b>")
                                    .chatId(chatId)
                                    .replyMarkup(inlineKeyboardMarkup)
                                    .parseMode("html")
                                    .build();

                            try {
                                execute(message);
                            } catch (TelegramApiException e) {
                                e.printStackTrace();
                            }
                        }
                        case 1 -> {
                            User user1 = userService.getByTelegramUserId(pair.getSecondValue());

                            Subscription subscription = Subscription.builder()
                                    .userData(user1.getUserData())
                                    .expiration(System.currentTimeMillis() + (Long.parseLong(text) * 24 * 60 * 60 * 1000))
                                    .build();

                            subscriptionService.create(subscription);

                            user1.getUserData().setSubscription(subscription);

                            giveSubscriptionSteps.remove(chatId);

                            InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                            InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                                    .callbackData(BackCallback.BACK_ADMIN_CALLBACK_DATA)
                                    .text("\uD83D\uDD19 Назад")
                                    .build();

                            List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                            keyboardButtonsRow1.add(backButton);

                            List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                            rowList.add(keyboardButtonsRow1);

                            inlineKeyboardMarkup.setKeyboard(rowList);

                            SendMessage message = SendMessage.builder()
                                    .text("✅ <b>Вы успешно добавили пользователю c идентификатором </b><code>" + pair.getSecondValue() + "</code> <b>подписку на</b> " + text + " <b>дней</b>")
                                    .chatId(chatId)
                                    .parseMode("html")
                                    .replyMarkup(inlineKeyboardMarkup)
                                    .build();

                            try {
                                execute(message);
                            } catch (TelegramApiException e) {
                                e.printStackTrace();
                            }

                            UserSessionData userSessionData = usersSessionsDataMap.get(pair.getSecondValue());

                            if (userSessionData == null){
                                userSessionData = getDefUserSessionData();
                            }

                            String getSubMessageText = null;

                            switch (userSessionData.getLanguage()){
                                case RU -> getSubMessageText = "✅ <b>Вы получили подписку от Администратора на</b> " + text + " <b>дней</b>";
                                case EN -> getSubMessageText = "✅ <b>You have received a subscription from the Administrator for</b> " + text + " <b>days</b>";
                                case UA -> getSubMessageText = "✅ <b>Ви отримали передплату від Адміністратора на</b> " + text + " <b>днів</b>";
                            }

                            SendMessage getSubMessage = SendMessage.builder()
                                    .text(getSubMessageText)
                                    .replyMarkup(getMainReplyKeyboardMarkup(userSessionData.getLanguage(), user1))
                                    .parseMode("html")
                                    .chatId(pair.getSecondValue())
                                    .build();

                            try {
                                execute(getSubMessage);
                            } catch (TelegramApiException e) {
                                e.printStackTrace();
                            }

                        }
                    }
                    return;
                }

                if (takeSubscriptionSteps.contains(chatId)) {
                    long telegramUserId = Long.parseLong(text);

                    User user1 = userService.getByTelegramUserId(telegramUserId);

                    if (user1 == null) {
                        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                        InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                                .callbackData(BackCallback.BACK_ADMIN_CALLBACK_DATA)
                                .text("\uD83D\uDD19 Назад")
                                .build();

                        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                        keyboardButtonsRow1.add(backButton);

                        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                        rowList.add(keyboardButtonsRow1);

                        inlineKeyboardMarkup.setKeyboard(rowList);

                        SendMessage message = SendMessage.builder()
                                .text("\uD83D\uDEAB <b>Пользователя с таким идентификатором не существует</b>")
                                .chatId(chatId)
                                .parseMode("html")
                                .replyMarkup(inlineKeyboardMarkup)
                                .build();

                        try {
                            execute(message);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }

                        takeSubscriptionSteps.remove(chatId);
                        return;
                    }

                    UserSessionData userSessionData = usersSessionsDataMap.get(user1.getTelegramChatId());

                    if (userSessionData == null){
                        userSessionData = getDefUserSessionData();
                    }

                    userSessionData.setModel("gpt-3.5-turbo");
                    userSessionData.setMessageHistory(new HashMap<>());

                    usersSessionsDataMap.put(user1.getTelegramChatId(), userSessionData);

                    subscriptionService.deleteById(user1.getUserData().getSubscription());

                    takeSubscriptionSteps.remove(chatId);

                    InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                    InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                            .callbackData(BackCallback.BACK_ADMIN_CALLBACK_DATA)
                            .text("\uD83D\uDD19 Назад")
                            .build();

                    List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                    keyboardButtonsRow1.add(backButton);

                    List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                    rowList.add(keyboardButtonsRow1);

                    inlineKeyboardMarkup.setKeyboard(rowList);

                    SendMessage message = SendMessage.builder()
                            .text("❎ <b>Вы успешно забрали подписку у пользователя с идентификатором</b> <code>" + telegramUserId + "</code>")
                            .chatId(chatId)
                            .parseMode("html")
                            .replyMarkup(inlineKeyboardMarkup)
                            .build();

                    try {
                        execute(message);
                    } catch (TelegramApiException e) {
                        e.printStackTrace();
                    }
                    return;
                }

                if (banUserSteps.contains(chatId)) {
                    long telegramUserId = Long.parseLong(text);

                    User user1 = userService.getByTelegramUserId(telegramUserId);

                    if (user1 == null) {
                        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                        InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                                .callbackData(BackCallback.BACK_ADMIN_CALLBACK_DATA)
                                .text("\uD83D\uDD19 Назад")
                                .build();

                        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                        keyboardButtonsRow1.add(backButton);

                        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                        rowList.add(keyboardButtonsRow1);

                        inlineKeyboardMarkup.setKeyboard(rowList);

                        SendMessage message = SendMessage.builder()
                                .text("\uD83D\uDEAB <b>Пользователя с таким идентификатором не существует</b>")
                                .chatId(chatId)
                                .parseMode("html")
                                .replyMarkup(inlineKeyboardMarkup)
                                .build();

                        try {
                            execute(message);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }

                        banUserSteps.remove(chatId);
                        return;
                    }

                    user1.setIsAccountNonLocked(false);

                    userService.create(user1);
                    banUserSteps.remove(chatId);

                    InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                    InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                            .callbackData(BackCallback.BACK_ADMIN_CALLBACK_DATA)
                            .text("\uD83D\uDD19 Назад")
                            .build();

                    List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                    keyboardButtonsRow1.add(backButton);

                    List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                    rowList.add(keyboardButtonsRow1);

                    inlineKeyboardMarkup.setKeyboard(rowList);

                    SendMessage message = SendMessage.builder()
                            .text("❎ <b>Вы успешно заблокировали пользователя с идентификатором</b> <code>" + telegramUserId + "</code>")
                            .chatId(chatId)
                            .parseMode("html")
                            .replyMarkup(inlineKeyboardMarkup)
                            .build();

                    try {
                        execute(message);
                    } catch (TelegramApiException e) {
                        e.printStackTrace();
                    }
                    return;
                }
                if (unbanUserSteps.contains(chatId)) {
                    long telegramUserId = Long.parseLong(text);

                    User user1 = userService.getByTelegramUserId(telegramUserId);

                    if (user1 == null) {
                        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                        InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                                .callbackData(BackCallback.BACK_ADMIN_CALLBACK_DATA)
                                .text("\uD83D\uDD19 Назад")
                                .build();

                        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                        keyboardButtonsRow1.add(backButton);

                        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                        rowList.add(keyboardButtonsRow1);

                        inlineKeyboardMarkup.setKeyboard(rowList);

                        SendMessage message = SendMessage.builder()
                                .text("\uD83D\uDEAB <b>Пользователя с таким идентификатором не существует</b>")
                                .chatId(chatId)
                                .parseMode("html")
                                .replyMarkup(inlineKeyboardMarkup)
                                .build();

                        try {
                            execute(message);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }

                        unbanUserSteps.remove(chatId);
                        return;
                    }

                    user1.setIsAccountNonLocked(true);

                    userService.create(user1);
                    unbanUserSteps.remove(chatId);

                    InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                    InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                            .callbackData(BackCallback.BACK_ADMIN_CALLBACK_DATA)
                            .text("\uD83D\uDD19 Назад")
                            .build();

                    List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                    keyboardButtonsRow1.add(backButton);

                    List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                    rowList.add(keyboardButtonsRow1);

                    inlineKeyboardMarkup.setKeyboard(rowList);

                    SendMessage message = SendMessage.builder()
                            .text("✅ <b>Вы успешно разблокировали пользователя с идентификатором</b> <code>" + telegramUserId + "</code>")
                            .chatId(chatId)
                            .parseMode("html")
                            .replyMarkup(inlineKeyboardMarkup)
                            .build();

                    try {
                        execute(message);
                    } catch (TelegramApiException e) {
                        e.printStackTrace();
                    }
                    return;
                }

                if (giveAdminSteps.contains(chatId)){

                    long telegramUserId = Long.parseLong(text);

                    User user1 = userService.getByTelegramUserId(telegramUserId);

                    if (user1 == null) {
                        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                        InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                                .callbackData(BackCallback.BACK_ADMIN_CALLBACK_DATA)
                                .text("\uD83D\uDD19 Назад")
                                .build();

                        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                        keyboardButtonsRow1.add(backButton);

                        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                        rowList.add(keyboardButtonsRow1);

                        inlineKeyboardMarkup.setKeyboard(rowList);

                        SendMessage message = SendMessage.builder()
                                .text("\uD83D\uDEAB <b>Пользователя с таким идентификатором не существует</b>")
                                .chatId(chatId)
                                .parseMode("html")
                                .replyMarkup(inlineKeyboardMarkup)
                                .build();

                        try {
                            execute(message);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }

                        giveAdminSteps.remove(chatId);
                        return;
                    }

                    user1.setRole(UserRole.ADMIN);

                    userService.create(user1);
                    giveAdminSteps.remove(chatId);

                    InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                    InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                            .callbackData(BackCallback.BACK_ADMIN_CALLBACK_DATA)
                            .text("\uD83D\uDD19 Назад")
                            .build();

                    List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                    keyboardButtonsRow1.add(backButton);

                    List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                    rowList.add(keyboardButtonsRow1);

                    inlineKeyboardMarkup.setKeyboard(rowList);

                    SendMessage message = SendMessage.builder()
                            .text("✅ <b>Вы успешно выдали права администратора пользователю с идентификатором</b> <code>" + text + "</code>")
                            .chatId(chatId)
                            .parseMode("html")
                            .replyMarkup(inlineKeyboardMarkup)
                            .build();

                    try {
                        execute(message);
                    } catch (TelegramApiException e) {
                        e.printStackTrace();
                    }
                    return;
                }

                if (takeAdminSteps.contains(chatId)){

                    long telegramUserId = Long.parseLong(text);

                    User user1 = userService.getByTelegramUserId(telegramUserId);

                    if (user1 == null) {
                        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                        InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                                .callbackData(BackCallback.BACK_ADMIN_CALLBACK_DATA)
                                .text("\uD83D\uDD19 Назад")
                                .build();

                        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                        keyboardButtonsRow1.add(backButton);

                        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                        rowList.add(keyboardButtonsRow1);

                        inlineKeyboardMarkup.setKeyboard(rowList);

                        SendMessage message = SendMessage.builder()
                                .text("\uD83D\uDEAB <b>Пользователя с таким идентификатором не существует</b>")
                                .chatId(chatId)
                                .parseMode("html")
                                .replyMarkup(inlineKeyboardMarkup)
                                .build();

                        try {
                            execute(message);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }

                        takeAdminSteps.remove(chatId);
                        return;
                    }

                    user1.setRole(UserRole.DEFAULT);

                    userService.create(user1);
                    takeAdminSteps.remove(chatId);

                    InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                    InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                            .callbackData(BackCallback.BACK_ADMIN_CALLBACK_DATA)
                            .text("\uD83D\uDD19 Назад")
                            .build();

                    List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                    keyboardButtonsRow1.add(backButton);

                    List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                    rowList.add(keyboardButtonsRow1);

                    inlineKeyboardMarkup.setKeyboard(rowList);

                    SendMessage message = SendMessage.builder()
                            .text("✅ <b>Вы успешно забрали права администратора у пользователя с идентификатором</b> <code>" + text + "</code>")
                            .chatId(chatId)
                            .parseMode("html")
                            .replyMarkup(inlineKeyboardMarkup)
                            .build();

                    try {
                        execute(message);
                    } catch (TelegramApiException e) {
                        e.printStackTrace();
                    }
                    return;
                }

                if (giveAllSubscriptionSteps.contains(chatId)){
                    long daysCountInMillis = Integer.parseInt(text) * 24 * 60 * 60 * 1000L;

                    giveAllSubscriptionSteps.remove(chatId);

                    InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                    InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                            .callbackData(BackCallback.BACK_ADMIN_CALLBACK_DATA)
                            .text("\uD83D\uDD19 Назад")
                            .build();

                    List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                    keyboardButtonsRow1.add(backButton);

                    List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                    rowList.add(keyboardButtonsRow1);

                    inlineKeyboardMarkup.setKeyboard(rowList);

                    SendMessage message = SendMessage.builder()
                            .text("✅ <b>Вы успешно выдали всем пользователям подписку на</b> " + text + " <b>дней</b>")
                            .chatId(chatId)
                            .parseMode("html")
                            .replyMarkup(inlineKeyboardMarkup)
                            .build();

                    try {
                        execute(message);
                    } catch (TelegramApiException e) {
                        e.printStackTrace();
                    }

                    List<User> users = userService.getAll();

                    for(User key : users){
                        if (key.getUserData().getSubscription() == null){
                            key.getUserData().setSubscription(Subscription.builder().userData(key.getUserData()).expiration(System.currentTimeMillis() + daysCountInMillis).build());
                        } else {
                            key.getUserData().getSubscription().setExpiration(key.getUserData().getSubscription().getExpiration() + daysCountInMillis);
                        }
                    }

                    userService.createAll(users);

                    for(User key : users){
                        UserSessionData userSessionData = usersSessionsDataMap.get(key.getTelegramChatId());
                        UserSessionDataLanguage language;

                        if (userSessionData == null){
                            language = UserSessionDataLanguage.RU;
                        } else {
                            language = userSessionData.getLanguage();
                        }

                        String giftMessage = null;

                        switch (language){
                            case RU -> giftMessage = "\uD83C\uDF81 <b>Поздравляю! Администрация выдала вам пробную подписку на</b> " + text + " <b>дней</b>";
                            case UA -> giftMessage = "\uD83C\uDF81 <b>Congratulations! The administration has given you a trial subscription for</b> " + text + " <b>days</b>";
                            case EN -> giftMessage = "\uD83C\uDF81 <b>Вітаю! Адміністрація видала вам пробну підписку на</b> " + text + " <b>днів</b>";
                        }

                        SendMessage message1 = SendMessage.builder()
                                .text(giftMessage)
                                .chatId(key.getTelegramChatId())
                                .replyMarkup(getMainReplyKeyboardMarkup(language, key))
                                .parseMode("html")
                                .build();

                        try {
                            execute(message1);
                        } catch (TelegramApiException e){
                            log.warn(e.getMessage());
                        }
                    }
                    return;
                }

                if (resendAdminSteps.contains(chatId)){
                    resendAdminSteps.remove(chatId);

                    InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                    InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                            .callbackData(BackCallback.BACK_ADMIN_CALLBACK_DATA)
                            .text("\uD83D\uDD19 Назад")
                            .build();

                    List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                    keyboardButtonsRow1.add(backButton);

                    List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                    rowList.add(keyboardButtonsRow1);

                    inlineKeyboardMarkup.setKeyboard(rowList);

                    String messageText = null;

                    if (!update.getMessage().hasText() && !update.getMessage().hasVideo() && !update.getMessage().hasPhoto()){
                        messageText = "\uD83D\uDEAB <b>Вы не можете разослать такой тип сообщения</b>";
                    } else {
                        messageText = "✅ <b>Вы успешно разослали сообщение всем пользователям</b>";
                    }
                    SendMessage message = SendMessage.builder()
                            .text(messageText)
                            .chatId(chatId)
                            .parseMode("html")
                            .replyMarkup(inlineKeyboardMarkup)
                            .build();

                    try {
                        execute(message);
                    } catch (TelegramApiException e) {
                        e.printStackTrace();
                    }

                    List<User> users = userService.getAll();

                    for (User key : users){
                        if (update.getMessage().hasText()) {
                            SendMessage sendMessage = SendMessage.builder()
                                    .chatId(key.getTelegramChatId())
                                    .text(text)
                                    .parseMode("html")
                                    .build();

                            try {
                                execute(sendMessage);
                            } catch (TelegramApiException ignored) {
                            }

                            return;

                        }
                    }

                    return;
                }

                Long timestamp = usersCooldownsMap.get(chatId);

                if (timestamp != null && timestamp > System.currentTimeMillis()) {
                    UserSessionData userSessionData = usersSessionsDataMap.get(chatId);

                    UserSessionDataLanguage language = null;

                    if (userSessionData == null) {
                        language = UserSessionDataLanguage.RU;
                    } else {
                        language = userSessionData.getLanguage();
                    }

                    int secCooldown = (int) ((timestamp - System.currentTimeMillis()) / 1000);

                    String cooldownWarningMessageText = null;

                    switch (language) {
                        case RU -> {
                            cooldownWarningMessageText = "Вы пишите слишком часто!\n" +
                                    "Повторите запрос через " + secCooldown + " сек.";
                        }
                        case UA -> {
                            cooldownWarningMessageText = "Ви пишете занадто часто!\n" +
                                    "Повторіть запит через " + secCooldown + " сек.";
                        }
                        case EN -> {
                            cooldownWarningMessageText = "You write too often!\n" +
                                    "Repeat the request through " + secCooldown + " sec.";
                        }
                    }

                    SendMessage cooldownMessage = SendMessage.builder()
                            .text(cooldownWarningMessageText)
                            .chatId(chatId)
                            .build();

                    try {
                        execute(cooldownMessage);
                    } catch (TelegramApiException e) {
                        e.printStackTrace();
                    }

                    return;
                }

                usersCooldownsMap.put(chatId, System.currentTimeMillis() + 4000);
                createChatCompletion(userService.getByTelegramChatId(chatId), chatId, text, null);


            } else if (update.hasMessage() && update.getMessage().hasVoice()) {
                long chatId = update.getMessage().getChatId();
                Voice voice = update.getMessage().getVoice();

                Long timestamp = usersCooldownsMap.get(chatId);

                UserSessionData userSessionData = usersSessionsDataMap.get(chatId);

                UserSessionDataLanguage language = null;

                if (userSessionData == null) {
                    language = UserSessionDataLanguage.RU;
                } else {
                    language = userSessionData.getLanguage();
                }

                if (timestamp != null && timestamp > System.currentTimeMillis()) {
                    int secCooldown = (int) ((timestamp - System.currentTimeMillis()) / 1000);

                    String cooldownWarningMessageText = null;

                    switch (language) {
                        case RU -> {
                            cooldownWarningMessageText = "Вы пишите слишком часто!\n" +
                                    "Повторите запрос через " + secCooldown + " сек.";
                        }
                        case UA -> {
                            cooldownWarningMessageText = "Ви пишете занадто часто!\n" +
                                    "Повторіть запит через " + secCooldown + " сек.";
                        }
                        case EN -> {
                            cooldownWarningMessageText = "You write too often!\n" +
                                    "Repeat the request through " + secCooldown + " sec.";
                        }
                    }

                    SendMessage cooldownMessage = SendMessage.builder()
                            .text(cooldownWarningMessageText)
                            .chatId(chatId)
                            .build();

                    try {
                        execute(cooldownMessage);
                    } catch (TelegramApiException e) {
                        e.printStackTrace();
                    }

                    return;
                }

                usersCooldownsMap.put(chatId, System.currentTimeMillis() + 4000);

                GetFile getFile = GetFile.builder().fileId(voice.getFileId()).build();
                String URL = null;

                try {
                    URL = execute(getFile).getFileUrl(botProperty.getToken());
                } catch (TelegramApiException e) {
                    e.printStackTrace();
                }

                File tempFile = null;

                try {
                    tempFile = UrlFileDownloader.downloadFile(URL, "voice" + System.currentTimeMillis(), ".oga");
                } catch (Exception e) {
                    e.printStackTrace();
                }

                String lang = null;

                switch (language) {
                    case RU -> lang = "ru";
                    case UA -> lang = "uk";
                    case EN -> lang = "en";
                }

                String transcriptionText = openaiApi.createAudioTranscription(tempFile, lang).getText();

                boolean isDeleted = tempFile.delete();

                createChatCompletion(userService.getByTelegramChatId(chatId), chatId, transcriptionText, null);

            }else if (update.hasMessage() && update.getMessage().hasPhoto()) {
                long chatId = update.getMessage().getChatId();
                PhotoSize photo = update.getMessage().getPhoto().get(2);
                String caption = update.getMessage().getCaption();

                if (caption == null) {
                    caption = "";
                }

                if (resendAdminSteps.contains(chatId)) {
                    resendAdminSteps.remove(chatId);

                    InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                    InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                            .callbackData(BackCallback.BACK_ADMIN_CALLBACK_DATA)
                            .text("\uD83D\uDD19 Назад")
                            .build();

                    List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                    keyboardButtonsRow1.add(backButton);

                    List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                    rowList.add(keyboardButtonsRow1);

                    inlineKeyboardMarkup.setKeyboard(rowList);

                    String messageText = null;

                    if (!update.getMessage().hasText() && !update.getMessage().hasVideo() && !update.getMessage().hasPhoto()) {
                        messageText = "\uD83D\uDEAB <b>Вы не можете разослать такой тип сообщения</b>";
                    } else {
                        messageText = "✅ <b>Вы успешно разослали сообщение всем пользователям</b>";
                    }
                    SendMessage message = SendMessage.builder()
                            .text(messageText)
                            .chatId(chatId)
                            .parseMode("html")
                            .replyMarkup(inlineKeyboardMarkup)
                            .build();

                    try {
                        execute(message);
                    } catch (TelegramApiException e) {
                        e.printStackTrace();
                    }

                    List<User> users = userService.getAll();

                    GetFile getFile = GetFile.builder()
                            .fileId(update.getMessage().getPhoto().get(2).getFileId())
                            .build();

                    String URL = null;

                    try {
                        URL = execute(getFile).getFileUrl(botProperty.getToken());
                    } catch (TelegramApiException e) {
                        e.printStackTrace();
                    }

                    File file = null;

                    try {
                        file = UrlFileDownloader.downloadFile(URL, "imageResend", ".img");
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    for (User key : users) {
                        if (update.getMessage().hasPhoto()) {
                            SendPhoto sendPhoto = SendPhoto.builder()
                                    .photo(new InputFile(file))
                                    .caption(update.getMessage().getCaption())
                                    .parseMode("html")
                                    .chatId(key.getTelegramChatId())
                                    .build();

                            try {
                                execute(sendPhoto);
                            } catch (TelegramApiException ignored) {
                            }

                            return;
                        }
                    }

                    file.delete();

                    return;
                }

                Long timestamp = usersCooldownsMap.get(chatId);

                UserSessionData userSessionData = usersSessionsDataMap.get(chatId);

                UserSessionDataLanguage language = null;

                if (userSessionData == null) {
                    language = UserSessionDataLanguage.RU;
                } else {
                    language = userSessionData.getLanguage();
                }

                if (timestamp != null && timestamp > System.currentTimeMillis()) {
                    int secCooldown = (int) ((timestamp - System.currentTimeMillis()) / 1000);

                    String cooldownWarningMessageText = null;

                    switch (language) {
                        case RU -> {
                            cooldownWarningMessageText = "Вы пишите слишком часто!\n" +
                                    "Повторите запрос через " + secCooldown + " сек.";
                        }
                        case UA -> {
                            cooldownWarningMessageText = "Ви пишете занадто часто!\n" +
                                    "Повторіть запит через " + secCooldown + " сек.";
                        }
                        case EN -> {
                            cooldownWarningMessageText = "You write too often!\n" +
                                    "Repeat the request through " + secCooldown + " sec.";
                        }
                    }

                    SendMessage cooldownMessage = SendMessage.builder()
                            .text(cooldownWarningMessageText)
                            .chatId(chatId)
                            .build();

                    try {
                        execute(cooldownMessage);
                    } catch (TelegramApiException e) {
                        e.printStackTrace();
                    }
                    return;
                }

                usersCooldownsMap.put(chatId, System.currentTimeMillis() + 4000);

                GetFile getFile = GetFile.builder().fileId(photo.getFileId()).build();
                String URL = null;

                try {
                    URL = execute(getFile).getFileUrl(botProperty.getToken());
                } catch (TelegramApiException e) {
                    e.printStackTrace();
                }

                createChatCompletion(userService.getByTelegramChatId(chatId), chatId, caption, URL);


            } else if (update.hasMessage() && update.getMessage().hasVideo()){
                long chatId = update.getMessage().getChatId();

                if (resendAdminSteps.contains(chatId)) {
                    resendAdminSteps.remove(chatId);

                    InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                    InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                            .callbackData(BackCallback.BACK_ADMIN_CALLBACK_DATA)
                            .text("\uD83D\uDD19 Назад")
                            .build();

                    List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                    keyboardButtonsRow1.add(backButton);

                    List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                    rowList.add(keyboardButtonsRow1);

                    inlineKeyboardMarkup.setKeyboard(rowList);

                    String messageText = null;

                    if (!update.getMessage().hasText() && !update.getMessage().hasVideo() && !update.getMessage().hasPhoto()) {
                        messageText = "\uD83D\uDEAB <b>Вы не можете разослать такой тип сообщения</b>";
                    } else {
                        messageText = "✅ <b>Вы успешно разослали сообщение всем пользователям</b>";
                    }
                    SendMessage message = SendMessage.builder()
                            .text(messageText)
                            .chatId(chatId)
                            .parseMode("html")
                            .replyMarkup(inlineKeyboardMarkup)
                            .build();

                    try {
                        execute(message);
                    } catch (TelegramApiException e) {
                        e.printStackTrace();
                    }

                    List<User> users = userService.getAll();

                    GetFile getFile = GetFile.builder()
                            .fileId(update.getMessage().getVideo().getFileId())
                            .build();

                    String URL = null;

                    try {
                        URL = execute(getFile).getFileUrl(botProperty.getToken());
                    } catch (TelegramApiException e) {
                        e.printStackTrace();
                    }

                    File file = null;

                    try {
                        file = UrlFileDownloader.downloadFile(URL, "videoResend", ".mp4");
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    for (User key : users) {
                        if (update.getMessage().hasVideo()) {

                            SendVideo sendVideo = SendVideo.builder()
                                    .video(new InputFile(file))
                                    .caption(update.getMessage().getCaption())
                                    .parseMode("html")
                                    .chatId(key.getTelegramChatId())
                                    .build();

                            try {
                                execute(sendVideo);
                            } catch (TelegramApiException ignored) {
                            }

                            return;
                        }
                    }

                    file.delete();

                    return;
                }
            } else if (update.hasCallbackQuery() && update.getCallbackQuery().getData() != null) {
                long chatId = update.getCallbackQuery().getMessage().getChatId();
                int messageId = update.getCallbackQuery().getMessage().getMessageId();

                switch (update.getCallbackQuery().getData()) {
                    case ProfileCallback.READY_SUBSCRIBED_CALLBACK_DATA -> {
                        GetChatMember getChatMember = new GetChatMember(botProperty.getChannelChatId(), update.getCallbackQuery().getFrom().getId());

                        ChatMember chatMember = null;

                        try {
                            chatMember = execute(getChatMember);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }

                        if (chatMember.getStatus().equals("left")) {
                            deleteMessage(messageId, chatId);
                            sendUnsubscribedMessage(chatId);
                        } else {
                            boolean existsByTelegramUserId = userService.existsByTelegramUserId(chatId);

                            if (!existsByTelegramUserId) {
                                User user = User.builder()
                                        .telegramUserId(update.getCallbackQuery().getFrom().getId())
                                        .telegramChatId(chatId)
                                        .role(UserRole.DEFAULT)
                                        .isAccountNonLocked(true)
                                        .timestamp(System.currentTimeMillis())
                                        .build();

                                UserData userData = UserData.builder()
                                        .user(user)
                                        .availableRequests(5)
                                        .invited(0)
                                        .build();

                                user.setUserData(userData);

                                userService.create(user);
                            }
                            deleteMessage(messageId, chatId);
                            handleStartMessage(chatId);
                        }
                    }
                    case SelectLanguageCallback.SELECT_RU_LANGUAGE_CALLBACK_DATA -> {
                        User user = userService.getByTelegramChatId(chatId);

                        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);

                        if (userSessionData == null) {
                            userSessionData = getDefUserSessionData();
                        }

                        userSessionData.setLanguage(UserSessionDataLanguage.RU);
                        usersSessionsDataMap.put(chatId, userSessionData);

                        deleteMessage(messageId, chatId);
                        sendSuccessSubscribeMessage(user, chatId);
                    }
                    case SelectLanguageCallback.SELECT_UA_LANGUAGE_CALLBACK_DATA -> {
                        User user = userService.getByTelegramChatId(chatId);

                        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);

                        if (userSessionData == null) {
                            userSessionData = getDefUserSessionData();
                        }
                        userSessionData.setLanguage(UserSessionDataLanguage.UA);
                        usersSessionsDataMap.put(chatId, userSessionData);

                        deleteMessage(messageId, chatId);
                        sendSuccessSubscribeMessage(user, chatId);
                    }
                    case SelectLanguageCallback.SELECT_EN_LANGUAGE_CALLBACK_DATA -> {
                        User user = userService.getByTelegramChatId(chatId);

                        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);

                        if (userSessionData == null) {
                            userSessionData = getDefUserSessionData();
                        }

                        userSessionData.setLanguage(UserSessionDataLanguage.EN);
                        usersSessionsDataMap.put(chatId, userSessionData);

                        deleteMessage(messageId, chatId);
                        sendSuccessSubscribeMessage(user, chatId);
                    }
                    case ProfileCallback.BUY_SUBSCRIPTION_CALLBACK_DATA -> {
                        deleteMessage(messageId, chatId);
                        handlePayMessage(chatId);
                    }
                    case ProfileCallback.CHANGE_LANGUAGE_CALLBACK_DATA -> {
                        EditMessageText editMessage = EditMessageText.builder()
                                .chatId(chatId)
                                .messageId(messageId)
                                .text("Выберите язык / Виберіть мову / Select a language")
                                .build();

                        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                        InlineKeyboardButton ruButton = InlineKeyboardButton.builder()
                                .callbackData(SelectLanguageCallback.SELECT_RU_LANGUAGE_CALLBACK_DATA)
                                .text("\uD83C\uDDF7\uD83C\uDDFA RU")
                                .build();

                        InlineKeyboardButton uaButton = InlineKeyboardButton.builder()
                                .callbackData(SelectLanguageCallback.SELECT_UA_LANGUAGE_CALLBACK_DATA)
                                .text("\uD83C\uDDFA\uD83C\uDDE6 UA")
                                .build();

                        InlineKeyboardButton enButton = InlineKeyboardButton.builder()
                                .callbackData(SelectLanguageCallback.SELECT_EN_LANGUAGE_CALLBACK_DATA)
                                .text("\uD83C\uDDEC\uD83C\uDDE7 EN")
                                .build();

                        List<InlineKeyboardButton> keyboardButtonsRow = new ArrayList<>();

                        keyboardButtonsRow.add(ruButton);
                        keyboardButtonsRow.add(uaButton);
                        keyboardButtonsRow.add(enButton);

                        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                        rowList.add(keyboardButtonsRow);

                        inlineKeyboardMarkup.setKeyboard(rowList);

                        editMessage.setReplyMarkup(inlineKeyboardMarkup);

                        try {
                            execute(editMessage);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }
                    }
                    case BackCallback.BACK_PROFILE_MESSAGE_CALLBACK_DATA -> {
                        User user = userService.getByTelegramChatId(chatId);

                        editProfileMessage(user, messageId, chatId);
                    }
                    case ProfileCallback.INVITE_FRIEND_CALLBACK_DATA -> {
                        User user = userService.getByTelegramChatId(chatId);

                        editInviteFriendMessage(user, messageId, chatId, update.getCallbackQuery().getFrom().getId());
                    }
                    case ChooseCallback.CHOOSE_CHAT_GPT_ROLE_CALLBACK_DATA -> {
                        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);

                        if (userSessionData == null) {
                            userSessionData = getDefUserSessionData();
                        }

                        userSessionData.setRole(UserSessionDataRole.CHAT_GPT);
                        userSessionData.setMessageHistory(new HashMap<>());

                        usersSessionsDataMap.put(chatId, userSessionData);

                        String messageText = null;

                        switch (userSessionData.getLanguage()) {
                            case RU -> messageText = "\uD83E\uDD16 Привет! Я <b>ChatGPT.</b> Чем я могу тебе помочь?";
                            case UA -> messageText = "\uD83E\uDD16 Привіт! Я <b>ChatGPT.</b> Чим я можу тобі допомогти?";
                            case EN -> messageText = "\uD83E\uDD16 Hello! I am <b>ChatGPT.</b> How can I help you?";
                        }

                        EditMessageText editMessage = EditMessageText.builder()
                                .text(messageText)
                                .messageId(messageId)
                                .parseMode("html")
                                .chatId(chatId)
                                .build();

                        try {
                            execute(editMessage);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }
                    }
                    case ChooseCallback.CHOOSE_PROGRAMMER_ROLE_CALLBACK_DATA -> {
                        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);

                        if (userSessionData == null) {
                            userSessionData = getDefUserSessionData();
                        }

                        userSessionData.setRole(UserSessionDataRole.PROGRAMMER);
                        userSessionData.setMessageHistory(new HashMap<>());

                        usersSessionsDataMap.put(chatId, userSessionData);

                        String messageText = null;

                        switch (userSessionData.getLanguage()) {
                            case RU -> messageText = "\uD83D\uDC68\u200D\uD83D\uDCBB Привет! Я <b>Программист-ассистент.</b> Чем я могу тебе помочь?";
                            case UA -> messageText = "\uD83D\uDC68\u200D\uD83D\uDCBB Привіт! Я <b>Програміст-асистент.</b> Чим я можу тобі допомогти?";
                            case EN -> messageText = "\uD83D\uDC68\u200D\uD83D\uDCBB Hello! I am <b>Programmer-assistant.</b> How can I help you?";
                        }

                        EditMessageText editMessage = EditMessageText.builder()
                                .text(messageText)
                                .messageId(messageId)
                                .parseMode("html")
                                .chatId(chatId)
                                .build();

                        try {
                            execute(editMessage);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }
                    }
                    case ChooseCallback.CHOOSE_PSYCOLOGIST_ROLE_CALLBACK_DATA -> {
                        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);

                        if (userSessionData == null) {
                            userSessionData = getDefUserSessionData();
                        }

                        userSessionData.setRole(UserSessionDataRole.PSYCOLOGIST);
                        userSessionData.setMessageHistory(new HashMap<>());

                        usersSessionsDataMap.put(chatId, userSessionData);

                        String messageText = null;

                        switch (userSessionData.getLanguage()) {
                            case RU -> messageText = "\uD83D\uDC68\u200D⚕ Привет! Я <b>Психолог.</b> Чем я могу тебе помочь?";
                            case UA -> messageText = "\uD83D\uDC68\u200D⚕ Привіт! Я <b>Психолог.</b> Чим я можу тобі допомогти?";
                            case EN -> messageText = "\uD83D\uDC68\u200D⚕ Hello! I am <b>Psychologist.</b> How can I help you?";
                        }

                        EditMessageText editMessage = EditMessageText.builder()
                                .text(messageText)
                                .messageId(messageId)
                                .parseMode("html")
                                .chatId(chatId)
                                .build();

                        try {
                            execute(editMessage);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }
                    }
                    case ChooseCallback.CHOOSE_JOKER_ROLE_CALLBACK_DATA -> {
                        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);

                        if (userSessionData == null) {
                            userSessionData = getDefUserSessionData();
                        }

                        userSessionData.setRole(UserSessionDataRole.JOKER);
                        userSessionData.setMessageHistory(new HashMap<>());

                        usersSessionsDataMap.put(chatId, userSessionData);

                        String messageText = null;

                        switch (userSessionData.getLanguage()) {
                            case RU -> messageText = "\uD83E\uDD39 Привет! Я <b>Шутник.</b> Чем я могу тебе помочь?";
                            case UA -> messageText = "\uD83E\uDD39 Привіт! Я <b>Жартівник.</b> Чим я можу тобі допомогти?";
                            case EN -> messageText = "\uD83E\uDD39 Hello! I am <b>Joker.</b> How can I help you?";
                        }

                        EditMessageText editMessage = EditMessageText.builder()
                                .text(messageText)
                                .messageId(messageId)
                                .parseMode("html")
                                .chatId(chatId)
                                .build();

                        try {
                            execute(editMessage);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }
                    }
                    case ChooseCallback.CHOOSE_LINGUIST_ROLE_CALLBACK_DATA -> {
                        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);

                        if (userSessionData == null) {
                            userSessionData = getDefUserSessionData();
                        }

                        userSessionData.setRole(UserSessionDataRole.LINGUIST);
                        userSessionData.setMessageHistory(new HashMap<>());

                        usersSessionsDataMap.put(chatId, userSessionData);

                        String messageText = null;

                        switch (userSessionData.getLanguage()) {
                            case RU -> messageText = "\uD83D\uDC68\u200D\uD83D\uDD2C Привет! Я <b>Лингвист.</b> Чем я могу тебе помочь?";
                            case UA -> messageText = "\uD83D\uDC68\u200D\uD83D\uDD2C Привіт! Я <b>Лінгвіст.</b> Чим я можу тобі допомогти?";
                            case EN -> messageText = "\uD83D\uDC68\u200D\uD83D\uDD2C Hello! I am <b>Linguist.</b> How can I help you?";
                        }

                        EditMessageText editMessage = EditMessageText.builder()
                                .text(messageText)
                                .messageId(messageId)
                                .parseMode("html")
                                .chatId(chatId)
                                .build();

                        try {
                            execute(editMessage);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }
                    }
                    case ChooseCallback.CHOOSE_POET_ROLE_CALLBACK_DATA -> {
                        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);

                        if (userSessionData == null) {
                            userSessionData = getDefUserSessionData();
                        }

                        userSessionData.setRole(UserSessionDataRole.POET);
                        userSessionData.setMessageHistory(new HashMap<>());

                        usersSessionsDataMap.put(chatId, userSessionData);

                        String messageText = null;

                        switch (userSessionData.getLanguage()) {
                            case RU -> messageText = "\uD83D\uDCDD Привет! Я <b>Поэт.</b> Чем я могу тебе помочь?";
                            case UA -> messageText = "\uD83D\uDCDD Привіт! Я <b>Поет.</b> Чим я можу тобі допомогти?";
                            case EN -> messageText = "\uD83D\uDCDD Hello! I am <b>Poet.</b> How can I help you?";
                        }

                        EditMessageText editMessage = EditMessageText.builder()
                                .text(messageText)
                                .messageId(messageId)
                                .parseMode("html")
                                .chatId(chatId)
                                .build();

                        try {
                            execute(editMessage);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }
                    }
                    case ChooseCallback.CHOOSE_MONA_LISA_ROLE_CALLBACK_DATA -> {
                        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);

                        if (userSessionData == null) {
                            userSessionData = getDefUserSessionData();
                        }

                        userSessionData.setRole(UserSessionDataRole.MONA_LISA);
                        userSessionData.setMessageHistory(new HashMap<>());

                        usersSessionsDataMap.put(chatId, userSessionData);

                        String messageText = null;

                        switch (userSessionData.getLanguage()) {
                            case RU -> messageText = "\uD83D\uDDBC\uFE0F Привет! Я <b>Мона Лиза.</b> Чем я могу тебе помочь?";
                            case UA -> messageText = "\uD83D\uDDBC\uFE0F Привіт! Я <b>Мона Лиза.</b> Чим я можу тобі допомогти?";
                            case EN -> messageText = "\uD83D\uDDBC\uFE0F Hello! I am <b>Mona Liza.</b> How can I help you?";
                        }

                        EditMessageText editMessage = EditMessageText.builder()
                                .text(messageText)
                                .messageId(messageId)
                                .parseMode("html")
                                .chatId(chatId)
                                .build();

                        try {
                            execute(editMessage);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }
                    }
                    case ChooseCallback.CHOOSE_EINSTEIN_ROLE_CALLBACK_DATA -> {
                        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);

                        if (userSessionData == null) {
                            userSessionData = getDefUserSessionData();
                        }

                        userSessionData.setRole(UserSessionDataRole.EINSTEIN);
                        userSessionData.setMessageHistory(new HashMap<>());

                        usersSessionsDataMap.put(chatId, userSessionData);

                        String messageText = null;

                        switch (userSessionData.getLanguage()) {
                            case RU -> messageText = "\uD83E\uDDE0 Привет! Я <b>Альберт Эйнштейн.</b> Чем я могу тебе помочь?";
                            case UA -> messageText = "\uD83E\uDDE0 Привіт! Я <b>Альберт Ейнштейн.</b> Чим я можу тобі допомогти?";
                            case EN -> messageText = "\uD83E\uDDE0 Hello! I am <b>Albert Einstein.</b> How can I help you?";
                        }

                        EditMessageText editMessage = EditMessageText.builder()
                                .text(messageText)
                                .messageId(messageId)
                                .parseMode("html")
                                .chatId(chatId)
                                .build();

                        try {
                            execute(editMessage);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }
                    }
                    case ChooseCallback.CHOOSE_BULLY_ROLE_CALLBACK_DATA -> {
                        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);

                        if (userSessionData == null) {
                            userSessionData = getDefUserSessionData();
                        }

                        userSessionData.setRole(UserSessionDataRole.BULLY);
                        userSessionData.setMessageHistory(new HashMap<>());

                        usersSessionsDataMap.put(chatId, userSessionData);

                        String messageText = null;

                        switch (userSessionData.getLanguage()) {
                            case RU -> messageText = "\uD83E\uDD2C Чё каво нахуй! Я <b>Гопник.</b> Задавать вопросы будешь блять?";
                            case UA -> messageText = "\uD83E\uDD2C Че каво нахуй! Я <b>Гопник.</b> Задавати питання будеш блять?";
                            case EN -> messageText = "\uD83E\uDD2C What the fuck! I am <b>Bully.</b> Are you going to fucking ask questions?";
                        }

                        EditMessageText editMessage = EditMessageText.builder()
                                .text(messageText)
                                .messageId(messageId)
                                .parseMode("html")
                                .chatId(chatId)
                                .build();

                        try {
                            execute(editMessage);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }
                    }

                    case AdminCallback.STAT_CALLBACK_DATA -> {
                        User user = userService.getByTelegramChatId(chatId);

                        editStatMessage(user, chatId, messageId);
                    }

                    case BackCallback.BACK_ADMIN_CALLBACK_DATA -> {
                        User user = userService.getByTelegramChatId(chatId);

                        editAdminMessage(user, chatId, messageId);
                    }
                    case AdminCallback.GIVE_SUBSCRIPTION_CALLBACK_DATA -> {
                        User user = userService.getByTelegramChatId(chatId);

                        if (!user.getRole().equals(UserRole.ADMIN)) {
                            EditMessageText editMessage = EditMessageText.builder()
                                    .chatId(chatId)
                                    .messageId(messageId)
                                    .text("\uD83D\uDEAB <b>У вас не хватает прав</b>")
                                    .parseMode("html")
                                    .build();

                            try {
                                execute(editMessage);
                            } catch (TelegramApiException e) {
                                e.printStackTrace();
                            }
                            return;
                        }

                        giveSubscriptionSteps.put(chatId, new CustomPair<>(0, null));

                        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                        InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                                .callbackData(BackCallback.BACK_ADMIN_GIVE_SUBSCRIPTION_CALLBACK_DATA)
                                .text("\uD83D\uDD19 Назад")
                                .build();

                        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                        keyboardButtonsRow1.add(backButton);

                        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                        rowList.add(keyboardButtonsRow1);

                        inlineKeyboardMarkup.setKeyboard(rowList);

                        EditMessageText editMessage = EditMessageText.builder()
                                .chatId(chatId)
                                .messageId(messageId)
                                .replyMarkup(inlineKeyboardMarkup)
                                .text("\uD83D\uDC71\u200D♂\uFE0F <b>Введите идентификатор пользователя для того, чтобы добавить ему подписку</b>")
                                .parseMode("html")
                                .build();

                        try {
                            execute(editMessage);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }
                    }
                    case AdminCallback.TAKE_SUBSCRIPTION_CALLBACK_DATA -> {
                        User user = userService.getByTelegramChatId(chatId);

                        if (!user.getRole().equals(UserRole.ADMIN)) {
                            EditMessageText editMessage = EditMessageText.builder()
                                    .chatId(chatId)
                                    .messageId(messageId)
                                    .text("\uD83D\uDEAB <b>У вас не хватает прав</b>")
                                    .parseMode("html")
                                    .build();

                            try {
                                execute(editMessage);
                            } catch (TelegramApiException e) {
                                e.printStackTrace();
                            }
                            return;
                        }

                        takeSubscriptionSteps.add(chatId);

                        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                        InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                                .callbackData(BackCallback.BACK_ADMIN_TAKE_SUBSCRIPTION_CALLBACK_DATA)
                                .text("\uD83D\uDD19 Назад")
                                .build();

                        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                        keyboardButtonsRow1.add(backButton);

                        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                        rowList.add(keyboardButtonsRow1);

                        inlineKeyboardMarkup.setKeyboard(rowList);

                        EditMessageText editMessage = EditMessageText.builder()
                                .chatId(chatId)
                                .messageId(messageId)
                                .replyMarkup(inlineKeyboardMarkup)
                                .text("\uD83D\uDC71\u200D♂\uFE0F <b>Введите идентификатор пользователя для того, чтобы забрать у него подписку</b>")
                                .parseMode("html")
                                .build();

                        try {
                            execute(editMessage);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }
                    }
                    case AdminCallback.TOP_DEPOSITS_CALLBACK_DATA -> {
                        User user = userService.getByTelegramChatId(chatId);

                        editTopDepositsMessage(user, chatId, messageId);
                    }
                    case AdminCallback.BAN_USER_CALLBACK_DATA -> {
                        User user = userService.getByTelegramChatId(chatId);

                        if (!user.getRole().equals(UserRole.ADMIN)) {
                            EditMessageText editMessage = EditMessageText.builder()
                                    .chatId(chatId)
                                    .messageId(messageId)
                                    .text("\uD83D\uDEAB <b>У вас не хватает прав</b>")
                                    .parseMode("html")
                                    .build();

                            try {
                                execute(editMessage);
                            } catch (TelegramApiException e) {
                                e.printStackTrace();
                            }
                            return;
                        }
                        banUserSteps.add(chatId);

                        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                        InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                                .callbackData(BackCallback.BACK_ADMIN_BAN_USER_CALLBACK_DATA)
                                .text("\uD83D\uDD19 Назад")
                                .build();

                        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                        keyboardButtonsRow1.add(backButton);

                        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                        rowList.add(keyboardButtonsRow1);

                        inlineKeyboardMarkup.setKeyboard(rowList);

                        EditMessageText editMessage = EditMessageText.builder()
                                .chatId(chatId)
                                .messageId(messageId)
                                .replyMarkup(inlineKeyboardMarkup)
                                .text("\uD83D\uDC71\u200D♂\uFE0F <b>Введите идентификатор пользователя для того, чтобы заблокировать его</b>")
                                .parseMode("html")
                                .build();

                        try {
                            execute(editMessage);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }
                    }

                    case AdminCallback.UNBAN_USER_CALLBACK_DATA -> {
                        User user = userService.getByTelegramChatId(chatId);

                        if (!user.getRole().equals(UserRole.ADMIN)) {
                            EditMessageText editMessage = EditMessageText.builder()
                                    .chatId(chatId)
                                    .messageId(messageId)
                                    .text("\uD83D\uDEAB <b>У вас не хватает прав</b>")
                                    .parseMode("html")
                                    .build();

                            try {
                                execute(editMessage);
                            } catch (TelegramApiException e) {
                                e.printStackTrace();
                            }
                            return;
                        }

                        unbanUserSteps.add(chatId);

                        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                        InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                                .callbackData(BackCallback.BACK_ADMIN_UNBAN_USER_CALLBACK_DATA)
                                .text("\uD83D\uDD19 Назад")
                                .build();

                        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                        keyboardButtonsRow1.add(backButton);

                        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                        rowList.add(keyboardButtonsRow1);

                        inlineKeyboardMarkup.setKeyboard(rowList);

                        EditMessageText editMessage = EditMessageText.builder()
                                .chatId(chatId)
                                .messageId(messageId)
                                .replyMarkup(inlineKeyboardMarkup)
                                .text("\uD83D\uDC71\u200D♂\uFE0F <b>Введите идентификатор пользователя для того, чтобы разблокировать его</b>")
                                .parseMode("html")
                                .build();

                        try {
                            execute(editMessage);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }
                    }
                    case AdminCallback.ALL_USERS_DATA_CALLBACK_DATA -> {
                        User user = userService.getByTelegramChatId(chatId);

                        handleAllUserDataMessage(user, chatId, messageId);
                    }

                    case BackCallback.BACK_ADMIN_GIVE_SUBSCRIPTION_CALLBACK_DATA -> {
                        User user = userService.getByTelegramChatId(chatId);

                        giveSubscriptionSteps.remove(chatId);

                        editAdminMessage(user, chatId, messageId);
                    }
                    case BackCallback.BACK_ADMIN_TAKE_SUBSCRIPTION_CALLBACK_DATA -> {
                        User user = userService.getByTelegramChatId(chatId);

                        takeSubscriptionSteps.remove(chatId);

                        editAdminMessage(user, chatId, messageId);
                    }
                    case BackCallback.BACK_ADMIN_BAN_USER_CALLBACK_DATA -> {
                        User user = userService.getByTelegramChatId(chatId);

                        banUserSteps.remove(chatId);

                        editAdminMessage(user, chatId, messageId);
                    }
                    case BackCallback.BACK_ADMIN_UNBAN_USER_CALLBACK_DATA -> {
                        User user = userService.getByTelegramChatId(chatId);

                        unbanUserSteps.remove(chatId);

                        editAdminMessage(user, chatId, messageId);
                    }
                    case BackCallback.BACK_ADMIN_GIVE_ADMIN_CALLBACK_DATA -> {
                        User user = userService.getByTelegramChatId(chatId);

                        giveAdminSteps.remove(chatId);

                        editAdminMessage(user, chatId, messageId);
                    }
                    case BackCallback.BACK_ADMIN_TAKE_ADMIN_CALLBACK_DATA -> {
                        User user = userService.getByTelegramChatId(chatId);

                        takeAdminSteps.remove(chatId);

                        editAdminMessage(user, chatId, messageId);
                    }
                    case BackCallback.BACK_ADMIN_GIVE_ALL_SUBSCRIPTION_CALLBACK_DATA -> {
                        User user = userService.getByTelegramChatId(chatId);

                        giveAllSubscriptionSteps.remove(chatId);

                        editAdminMessage(user, chatId, messageId);
                    }
                    case BackCallback.BACK_ADMIN_RESEND_CALLBACK_DATA -> {
                        User user = userService.getByTelegramChatId(chatId);

                        resendAdminSteps.remove(chatId);

                        editAdminMessage(user, chatId, messageId);
                    }
                    case ChangeModelCallback.CHANGE_MODEL_CHAT_GPT_THREE_FIVE_CALLBACK_DATA -> {
                        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);

                        if (userSessionData == null) {
                            userSessionData = getDefUserSessionData();
                        }

                        userSessionData.setModel("gpt-3.5-turbo");
                        userSessionData.setMessageHistory(new HashMap<>());

                        usersSessionsDataMap.put(chatId, userSessionData);

                        String editMessageText = null;

                        switch (userSessionData.getLanguage()) {
                            case RU -> editMessageText = "\uD83D\uDC7D Вы успешно изменили модель на <b>ChatGPT 3.5 Turbo</b>";
                            case EN -> editMessageText = "\uD83D\uDC7D You have successfully changed the model to <b>ChatGPT 3.5 Turbo</b>";
                            case UA -> editMessageText = "\uD83D\uDC7D Ви успішно змінили модель на <b>ChatGPT 3.5 Turbo</b>";
                        }

                        EditMessageText editMessage = EditMessageText.builder()
                                .text(editMessageText)
                                .messageId(messageId)
                                .chatId(chatId)
                                .parseMode("html")
                                .build();

                        try {
                            execute(editMessage);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }
                    }
                    case ChangeModelCallback.CHANGE_MODEL_CHAT_GPT_FOUR_CALLBACK_DATA -> {
                        User user = userService.getByTelegramChatId(chatId);

                        if (user.getUserData().getSubscription() == null) {
                            deleteMessage(messageId, chatId);
                            handlePayMessage(chatId);
                            return;
                        }

                        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);

                        if (userSessionData == null) {
                            userSessionData = getDefUserSessionData();
                        }

                        userSessionData.setModel("gpt-4");
                        userSessionData.setMessageHistory(new HashMap<>());

                        usersSessionsDataMap.put(chatId, userSessionData);

                        String editMessageText = null;

                        switch (userSessionData.getLanguage()) {
                            case RU -> editMessageText = "\uD83E\uDD16 Вы успешно изменили модель на <b>ChatGPT 4</b>";
                            case EN -> editMessageText = "\uD83E\uDD16 You have successfully changed the model to <b>ChatGPT 4</b>";
                            case UA -> editMessageText = "\uD83E\uDD16 Ви успішно змінили модель на <b>ChatGPT 4</b>";
                        }

                        EditMessageText editMessage = EditMessageText.builder()
                                .text(editMessageText)
                                .messageId(messageId)
                                .chatId(chatId)
                                .parseMode("html")
                                .build();

                        try {
                            execute(editMessage);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }
                    }
                    case ChangeModelCallback.CHANGE_MODEL_CHAT_GPT_FOUR_TURBO_CALLBACK_DATA -> {
                        User user = userService.getByTelegramChatId(chatId);

                        if (user.getUserData().getSubscription() == null) {
                            deleteMessage(messageId, chatId);
                            handlePayMessage(chatId);
                            return;
                        }

                        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);

                        if (userSessionData == null) {
                            userSessionData = getDefUserSessionData();
                        }

                        userSessionData.setModel("gpt-4-1106-preview");
                        userSessionData.setMessageHistory(new HashMap<>());

                        usersSessionsDataMap.put(chatId, userSessionData);

                        String editMessageText = null;

                        switch (userSessionData.getLanguage()) {
                            case RU -> editMessageText = "\uD83D\uDC7E Вы успешно изменили модель на <b>ChatGPT 4 Turbo</b>";
                            case EN -> editMessageText = "\uD83D\uDC7E You have successfully changed the model to <b>ChatGPT 4 Turbo</b>";
                            case UA -> editMessageText = "\uD83D\uDC7E Ви успішно змінили модель на <b>ChatGPT 4 Turbo</b>";
                        }

                        EditMessageText editMessage = EditMessageText.builder()
                                .text(editMessageText)
                                .messageId(messageId)
                                .chatId(chatId)
                                .parseMode("html")
                                .build();

                        try {
                            execute(editMessage);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }
                    }
                    case ChangeModelCallback.CHANGE_MODEL_CHAT_GPT_FOUR_VISION_CALLBACK_DATA -> {
                        User user = userService.getByTelegramChatId(chatId);

                        if (user.getUserData().getSubscription() == null) {
                            deleteMessage(messageId, chatId);
                            handlePayMessage(chatId);
                            return;
                        }

                        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);

                        if (userSessionData == null) {
                            userSessionData = getDefUserSessionData();
                        }

                        userSessionData.setModel("gpt-4-vision-preview");
                        userSessionData.setMessageHistory(new HashMap<>());

                        usersSessionsDataMap.put(chatId, userSessionData);

                        String editMessageText = null;

                        switch (userSessionData.getLanguage()) {
                            case RU -> editMessageText = "\uD83D\uDCF7 Вы успешно изменили модель на <b>ChatGPT 4 Vision</b>";
                            case EN -> editMessageText = "\uD83D\uDCF7 You have successfully changed the model to <b>ChatGPT 4 Vision</b>";
                            case UA -> editMessageText = "\uD83D\uDCF7 Ви успішно змінили модель на <b>ChatGPT 4 Vision</b>";
                        }

                        EditMessageText editMessage = EditMessageText.builder()
                                .text(editMessageText)
                                .messageId(messageId)
                                .chatId(chatId)
                                .parseMode("html")
                                .build();

                        try {
                            execute(editMessage);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }
                    }
                    case AdminCallback.GIVE_ADMIN_CALLBACK_DATA -> {
                        User user = userService.getByTelegramChatId(chatId);

                        if (!user.getRole().equals(UserRole.ADMIN)) {
                            EditMessageText editMessage = EditMessageText.builder()
                                    .chatId(chatId)
                                    .messageId(messageId)
                                    .text("\uD83D\uDEAB <b>У вас не хватает прав</b>")
                                    .parseMode("html")
                                    .build();

                            try {
                                execute(editMessage);
                            } catch (TelegramApiException e) {
                                e.printStackTrace();
                            }
                            return;
                        }

                        giveAdminSteps.add(chatId);

                        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                        InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                                .callbackData(BackCallback.BACK_ADMIN_GIVE_ADMIN_CALLBACK_DATA)
                                .text("\uD83D\uDD19 Назад")
                                .build();

                        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                        keyboardButtonsRow1.add(backButton);

                        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                        rowList.add(keyboardButtonsRow1);

                        inlineKeyboardMarkup.setKeyboard(rowList);

                        EditMessageText editMessage = EditMessageText.builder()
                                .chatId(chatId)
                                .messageId(messageId)
                                .replyMarkup(inlineKeyboardMarkup)
                                .text("\uD83D\uDC71\u200D♂\uFE0F <b>Введите идентификатор пользователя для того, чтобы выдать права администратора</b>")
                                .parseMode("html")
                                .build();

                        try {
                            execute(editMessage);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }
                    }
                    case AdminCallback.TAKE_ADMIN_CALLBACK_DATA -> {
                        User user = userService.getByTelegramChatId(chatId);

                        if (!user.getRole().equals(UserRole.ADMIN)) {
                            EditMessageText editMessage = EditMessageText.builder()
                                    .chatId(chatId)
                                    .messageId(messageId)
                                    .text("\uD83D\uDEAB <b>У вас не хватает прав</b>")
                                    .parseMode("html")
                                    .build();

                            try {
                                execute(editMessage);
                            } catch (TelegramApiException e) {
                                e.printStackTrace();
                            }
                            return;
                        }

                        takeAdminSteps.add(chatId);

                        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                        InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                                .callbackData(BackCallback.BACK_ADMIN_TAKE_ADMIN_CALLBACK_DATA)
                                .text("\uD83D\uDD19 Назад")
                                .build();

                        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                        keyboardButtonsRow1.add(backButton);

                        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                        rowList.add(keyboardButtonsRow1);

                        inlineKeyboardMarkup.setKeyboard(rowList);

                        EditMessageText editMessage = EditMessageText.builder()
                                .chatId(chatId)
                                .messageId(messageId)
                                .replyMarkup(inlineKeyboardMarkup)
                                .text("\uD83D\uDC71\u200D♂\uFE0F <b>Введите идентификатор пользователя для того, чтобы забрать права администратора</b>")
                                .parseMode("html")
                                .build();

                        try {
                            execute(editMessage);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }
                    }
                    case AdminCallback.GIVE_ALL_SUBSCRIPTION_CALLBACK_DATA -> {
                        User user = userService.getByTelegramChatId(chatId);

                        if (!user.getRole().equals(UserRole.ADMIN)) {
                            EditMessageText editMessage = EditMessageText.builder()
                                    .chatId(chatId)
                                    .messageId(messageId)
                                    .text("\uD83D\uDEAB <b>У вас не хватает прав</b>")
                                    .parseMode("html")
                                    .build();

                            try {
                                execute(editMessage);
                            } catch (TelegramApiException e) {
                                e.printStackTrace();
                            }
                            return;
                        }

                        giveAllSubscriptionSteps.add(chatId);

                        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                        InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                                .callbackData(BackCallback.BACK_ADMIN_GIVE_ALL_SUBSCRIPTION_CALLBACK_DATA)
                                .text("\uD83D\uDD19 Назад")
                                .build();

                        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                        keyboardButtonsRow1.add(backButton);

                        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                        rowList.add(keyboardButtonsRow1);

                        inlineKeyboardMarkup.setKeyboard(rowList);

                        EditMessageText editMessage = EditMessageText.builder()
                                .chatId(chatId)
                                .messageId(messageId)
                                .replyMarkup(inlineKeyboardMarkup)
                                .text("\uD83D\uDD22 <b>Введите количество дней, на сколько вы хотите выдать подписку</b>")
                                .parseMode("html")
                                .build();

                        try {
                            execute(editMessage);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }
                    }
                    case PaymentCallback.PAYMENT_THIRTY_CALLBACK_DATA -> {
                        deleteMessage(messageId, chatId);
                        User user = userService.getByTelegramChatId(chatId);

                        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);
                        UserSessionDataLanguage language;

                        String messageText = null;

                        String currency = null;
                        double amount = 0;
                        String lang = null;

                        if (userSessionData == null){
                            language = UserSessionDataLanguage.RU;
                        } else {
                            language = userSessionData.getLanguage();
                        }

                        switch (language){
                            case RU -> {
                                currency = "RUB";
                                amount = 399.0;

                                lang = "ru";

                                messageText = "Нажимая кнопку ниже я даю согласие на обработку персональных данных и принимаю условия <a href=\"https://telegra.ph/Publichnyj-dogovor-oferta-11-27-2\">публичной оферты.</a>";
                            }
                            case EN -> {
                                currency = "USD";
                                amount = 4.5;

                                lang = "en";

                                messageText = "By clicking the button below I consent to the processing of personal data and accept the terms of the <a href=\"https://telegra.ph/Public-Contract-Offer-11-27\">public offer.</a>";
                            }
                            case UA -> {
                                currency = "USD";
                                amount = 4.5;

                                lang = "ru";

                                messageText = "Натискаючи кнопку нижче я даю згоду на обробку персональних даних і приймаю умови <a href=\"https://telegra.ph/Publichnyj-dogovor-oferta-11-27-2\">публічної оферти.</a>";
                            }
                        }

                        String orderId = HexConverterUtil.toHex(user.getId() +  "||" + amount + "||" + currency + "||" + 2 + "||" + RandomCharacterGeneratorUtil.generateRandomCharacters(4));

                        String redirectUrl = aaioApi.getRedirectUrl(orderId, amount, currency, lang);

                        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                        InlineKeyboardButton groupButton = InlineKeyboardButton.builder()
                                .text("Aaio.io")
                                .url(redirectUrl)
                                .build();

                        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                        keyboardButtonsRow1.add(groupButton);

                        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                        rowList.add(keyboardButtonsRow1);

                        inlineKeyboardMarkup.setKeyboard(rowList);

                        SendMessage message = SendMessage.builder()
                                .text(messageText)
                                .chatId(chatId)
                                .replyMarkup(inlineKeyboardMarkup)
                                .parseMode("html")
                                .disableWebPagePreview(true)
                                .build();

                        try {
                            execute(message);
                        } catch (TelegramApiException e){
                            e.printStackTrace();
                        }

                    }
                    case PaymentCallback.PAYMENT_SEVEN_CALLBACK_DATA -> {
                        deleteMessage(messageId, chatId);
                        User user = userService.getByTelegramChatId(chatId);

                        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);
                        UserSessionDataLanguage language;

                        String messageText = null;

                        String currency = null;
                        double amount = 0;
                        String lang = null;

                        if (userSessionData == null){
                            language = UserSessionDataLanguage.RU;
                        } else {
                            language = userSessionData.getLanguage();
                        }

                        switch (language){
                            case RU -> {
                                currency = "RUB";
                                amount = 199;

                                lang = "ru";

                                messageText = "Нажимая кнопку ниже я даю согласие на обработку персональных данных и принимаю условия <a href=\"https://telegra.ph/Publichnyj-dogovor-oferta-11-27-2\">публичной оферты.</a>";
                            }
                            case EN -> {
                                currency = "USD";
                                amount = 2.2;

                                lang = "en";

                                messageText = "By clicking the button below I consent to the processing of personal data and accept the terms of the <a href=\"https://telegra.ph/Public-Contract-Offer-11-27\">public offer.</a>";
                            }
                            case UA -> {
                                currency = "USD";
                                amount = 2.2;

                                lang = "ru";

                                messageText = "Натискаючи кнопку нижче я даю згоду на обробку персональних даних і приймаю умови <a href=\"https://telegra.ph/Publichnyj-dogovor-oferta-11-27-2\">публічної оферти.</a>";
                            }
                        }

                        String orderId = HexConverterUtil.toHex(user.getId() + "||" + amount + "||" + currency + "||" + 1 + "||" + RandomCharacterGeneratorUtil.generateRandomCharacters(4));

                        String redirectUrl = aaioApi.getRedirectUrl(orderId, amount, currency, lang);

                        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                        InlineKeyboardButton groupButton = InlineKeyboardButton.builder()
                                .text("Aaio.io")
                                .url(redirectUrl)
                                .build();

                        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                        keyboardButtonsRow1.add(groupButton);

                        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                        rowList.add(keyboardButtonsRow1);

                        inlineKeyboardMarkup.setKeyboard(rowList);

                        SendMessage message = SendMessage.builder()
                                .text(messageText)
                                .chatId(chatId)
                                .replyMarkup(inlineKeyboardMarkup)
                                .disableWebPagePreview(true)
                                .parseMode("html")
                                .build();

                        try {
                            execute(message);
                        } catch (TelegramApiException e){
                            e.printStackTrace();
                        }
                    }
                    case PaymentCallback.PAYMENT_NINETY_CALLBACK_DATA -> {
                        deleteMessage(messageId, chatId);
                        User user = userService.getByTelegramChatId(chatId);

                        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);
                        UserSessionDataLanguage language;

                        String messageText = null;

                        String currency = null;
                        double amount = 0;
                        String lang = null;

                        if (userSessionData == null){
                            language = UserSessionDataLanguage.RU;
                        } else {
                            language = userSessionData.getLanguage();
                        }

                        switch (language){
                            case RU -> {
                                currency = "RUB";
                                amount = 899.0;

                                lang = "ru";

                                messageText = "Нажимая кнопку ниже я даю согласие на обработку персональных данных и принимаю условия <a href=\"https://telegra.ph/Publichnyj-dogovor-oferta-11-27-2\">публичной оферты.</a>";
                            }
                            case EN -> {
                                currency = "USD";
                                amount = 10.0;

                                lang = "en";

                                messageText = "By clicking the button below I consent to the processing of personal data and accept the terms of the <a href=\"https://telegra.ph/Public-Contract-Offer-11-27\">public offer.</a>";
                            }
                            case UA -> {
                                currency = "USD";
                                amount = 10.0;

                                lang = "ru";

                                messageText = "Натискаючи кнопку нижче я даю згоду на обробку персональних даних і приймаю умови <a href=\"https://telegra.ph/Publichnyj-dogovor-oferta-11-27-2\">публічної оферти.</a>";
                            }
                        }

                        String orderId = HexConverterUtil.toHex(user.getId() +  "||" + amount + "||" + currency + "||" + 3 + "||" + RandomCharacterGeneratorUtil.generateRandomCharacters(4));

                        String redirectUrl = aaioApi.getRedirectUrl(orderId, amount, currency, lang);

                        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                        InlineKeyboardButton groupButton = InlineKeyboardButton.builder()
                                .text("Aaio.io")
                                .url(redirectUrl)
                                .build();

                        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                        keyboardButtonsRow1.add(groupButton);

                        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                        rowList.add(keyboardButtonsRow1);

                        inlineKeyboardMarkup.setKeyboard(rowList);

                        SendMessage message = SendMessage.builder()
                                .text(messageText)
                                .chatId(chatId)
                                .replyMarkup(inlineKeyboardMarkup)
                                .parseMode("html")
                                .disableWebPagePreview(true)
                                .build();

                        try {
                            execute(message);
                        } catch (TelegramApiException e){
                            e.printStackTrace();
                        }
                    }
                    case AdminCallback.RESEND_ADMIN_CALLBACK_DATA -> {
                        User user = userService.getByTelegramChatId(chatId);

                        if (!user.getRole().equals(UserRole.ADMIN)) {
                            EditMessageText editMessage = EditMessageText.builder()
                                    .chatId(chatId)
                                    .messageId(messageId)
                                    .text("\uD83D\uDEAB <b>У вас не хватает прав</b>")
                                    .parseMode("html")
                                    .build();

                            try {
                                execute(editMessage);
                            } catch (TelegramApiException e) {
                                e.printStackTrace();
                            }
                            return;
                        }

                        resendAdminSteps.add(chatId);

                        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                        InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                                .callbackData(BackCallback.BACK_ADMIN_RESEND_CALLBACK_DATA)
                                .text("\uD83D\uDD19 Назад")
                                .build();

                        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

                        keyboardButtonsRow1.add(backButton);

                        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

                        rowList.add(keyboardButtonsRow1);

                        inlineKeyboardMarkup.setKeyboard(rowList);

                        EditMessageText editMessage = EditMessageText.builder()
                                .chatId(chatId)
                                .messageId(messageId)
                                .replyMarkup(inlineKeyboardMarkup)
                                .text("\uD83D\uDCAC <b>Отправьте сообщение, которые вы хотите разослать всем пользователям</b>")
                                .parseMode("html")
                                .build();

                        try {
                            execute(editMessage);
                        } catch (TelegramApiException e) {
                            e.printStackTrace();
                        }

                    }

                }
            }
        }).start();
    }

    private void sendUnsubscribedMessage(long chatId) {
        SendMessage sendMessage = new SendMessage();

        sendMessage.setChatId(chatId);
        sendMessage.setParseMode("html");

        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);
        UserSessionDataLanguage userSessionDataLanguage;

        if (userSessionData == null) {
            userSessionDataLanguage = UserSessionDataLanguage.RU;
        } else {
            userSessionDataLanguage = userSessionData.getLanguage();
        }

        String messageText = null;
        String readyButtonText = null;

        switch (userSessionDataLanguage) {
            case RU -> {
                messageText = "<b>Дорогой пользователь</b>\uD83E\uDDBE \n\nДля использования бота подпишись, пожалуйста, на наш канал:";
                readyButtonText = "Готово ✓";
            }
            case EN -> {
                messageText = "<b>Dear User</b>\uD83E\uDDBE \n\nTo use the bot, please subscribe to our channel:";
                readyButtonText = "Ready ✓";
            }
            case UA -> {
                messageText = "<b>Дорогий користувач</b>\uD83E\uDDBE \n\nДля використання бота підпишись, будь ласка, на наш канал:";
                readyButtonText = "Готово ✓";
            }
        }
        sendMessage.setText(messageText);

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton groupButton = InlineKeyboardButton.builder()
                .text(botProperty.getChannelName())
                .url(botProperty.getChannelLink())
                .build();

        InlineKeyboardButton readyButton = InlineKeyboardButton.builder()
                .text(readyButtonText)
                .callbackData(ProfileCallback.READY_SUBSCRIBED_CALLBACK_DATA)
                .build();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow2 = new ArrayList<>();

        keyboardButtonsRow1.add(groupButton);
        keyboardButtonsRow2.add(readyButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);
        rowList.add(keyboardButtonsRow2);

        inlineKeyboardMarkup.setKeyboard(rowList);

        sendMessage.setReplyMarkup(inlineKeyboardMarkup);

        try {
            execute(sendMessage);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void deleteMessage(int messageId, long chatId) {
        DeleteMessage deleteMessage = DeleteMessage.builder()
                .messageId(messageId)
                .chatId(chatId)
                .build();

        try {
            execute(deleteMessage);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void sendSuccessSubscribeMessage(User user, long chatId) {
        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);
        UserSessionDataLanguage userSessionDataLanguage;

        if (userSessionData == null) {
            userSessionDataLanguage = UserSessionDataLanguage.RU;
        } else {
            userSessionDataLanguage = userSessionData.getLanguage();
        }

        String messageText = null;

        switch (userSessionDataLanguage) {
            case RU -> {
                messageText = "<b>Задай свой вопрос или отправьте мне голосовое сообщение.</b>\n\n<i>Больше информации о ChatGPT в разделе \"\uD83D\uDC64 Профиль\".</i>";
            }
            case EN -> {
                messageText = "<b>Ask your question or send me a voicemail.</b>\n\n<i>More information about ChatGPT in the \"\uD83D\uDC64 Profile\" section.</i>";
            }
            case UA -> messageText = "<b>Постав своє запитання або надішліть мені голосове повідомлення.</b>\n\n<i>Більше інформації про ChatGPT у розділі \"\uD83D\uDC64 Профіль\".</i>";
        }

        SendMessage sendMessage = SendMessage.builder()
                .text(messageText)
                .chatId(chatId)
                .replyMarkup(getMainReplyKeyboardMarkup(userSessionDataLanguage, user))
                .parseMode("html")
                .build();

        try {
            execute(sendMessage);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    public void handleStartMessage(long chatId) {
        InputStream animationStream = getClass().getClassLoader().getResourceAsStream("start.gif");

        InputFile animationInputFile = new InputFile(animationStream, "start.gif");

        SendAnimation sendAnimation = SendAnimation.builder()
                .chatId(chatId)
                .animation(animationInputFile)
                .caption("Выберите язык / Виберіть мову / Select a language")
                .build();

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton ruButton = InlineKeyboardButton.builder()
                .callbackData(SelectLanguageCallback.SELECT_RU_LANGUAGE_CALLBACK_DATA)
                .text("\uD83C\uDDF7\uD83C\uDDFA RU")
                .build();

        InlineKeyboardButton uaButton = InlineKeyboardButton.builder()
                .callbackData(SelectLanguageCallback.SELECT_UA_LANGUAGE_CALLBACK_DATA)
                .text("\uD83C\uDDFA\uD83C\uDDE6 UA")
                .build();

        InlineKeyboardButton enButton = InlineKeyboardButton.builder()
                .callbackData(SelectLanguageCallback.SELECT_EN_LANGUAGE_CALLBACK_DATA)
                .text("\uD83C\uDDEC\uD83C\uDDE7 EN")
                .build();

        List<InlineKeyboardButton> keyboardButtonsRow = new ArrayList<>();

        keyboardButtonsRow.add(ruButton);
        keyboardButtonsRow.add(uaButton);
        keyboardButtonsRow.add(enButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow);

        inlineKeyboardMarkup.setKeyboard(rowList);

        sendAnimation.setReplyMarkup(inlineKeyboardMarkup);

        try {
            execute(sendAnimation);
            animationStream.close();
        } catch (TelegramApiException | IOException e) {
            e.printStackTrace();
        }
    }

    public void handleProfileMessage(User user, long chatId) {
        boolean hasSubscribe = user.getUserData().getSubscription() != null;
        String subscribeDateString = null;
        String timestampDateString = null;

        if (hasSubscribe) {
            Date subscribeDate = new Date(user.getUserData().getSubscription().getExpiration());
            Date timestampDate = new Date(user.getTimestamp());

            SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy");

            subscribeDateString = dateFormat.format(subscribeDate);
            timestampDateString = dateFormat.format(timestampDate);
        }

        Date timestampDate = new Date(user.getTimestamp());

        SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy");

        timestampDateString = dateFormat.format(timestampDate);

        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);
        UserSessionDataLanguage userSessionDataLanguage;

        if (userSessionData == null) {
            userSessionDataLanguage = UserSessionDataLanguage.RU;
        } else {
            userSessionDataLanguage = userSessionData.getLanguage();
        }

        String messageText = null;

        String buySubscriptionButtonText = null;
        String inviteFriendButtonText = null;
        String changeLanguageButtonText = null;

        switch (userSessionDataLanguage) {
            case RU -> {
                String preMessage = hasSubscribe ?
                        "\uD83D\uDCC5 <b>Подписка истекает:</b> " + subscribeDateString + "\n"
                        : "\n";

                messageText = "<b>GevaGPT</b>\n\n"
                        + "\uD83C\uDD94 <b>User ID:</b> <code>" + user.getTelegramUserId() + "</code>\n"
                        + preMessage
                        + "\uD83D\uDD0D <b>Запросов:</b> " + user.getUserData().getAvailableRequests() + "\n"
                        + "\uD83D\uDC40 <b>Зарегистрирован:</b> " + timestampDateString + "\n\n"
                        + "<b>Недостаточно запросов ChatGPT?</b>\n\n"
                        + "▫\uFE0F Вы можете приобрести подписку ChatGPT и не беспокоиться о лимитах;\n" +
                        "▫\uFE0F Пригласите друга и получите за него 5 запросов ChatGPT.\n\n"
                        + "<b><i>Как правильно общаться с ChatGPT –</i></b> https://telegra.ph/Gajd-GevaGPT-Kak-sostavit-horoshij-zapros-v-ChatGPT-11-27";

                buySubscriptionButtonText = "\uD83D\uDE80 Купить подписку";
                inviteFriendButtonText = "\uD83D\uDC65 Пригласить друга";
                changeLanguageButtonText = "\uD83C\uDF0D Сменить язык бота";
            }

            case UA -> {
                String preMessage = hasSubscribe ?
                        "\uD83D\uDCC5 <b>Передплата закінчується:</b> " + subscribeDateString + "\n"
                        : "\n";

                messageText = "<b>GevaGPT</b>\n\n"
                        + "\uD83C\uDD94 <b>User ID:</b> <code>" + user.getTelegramUserId() + "</code>\n"
                        + preMessage
                        + "\uD83D\uDD0D <b>Запитів:</b> " + user.getUserData().getAvailableRequests() + "\n"
                        + "\uD83D\uDC40 <b>Зареєстровано:</b> " + user.getTimestamp() + "\n\n"
                        + "<b>Недостатньо запитів ChatGPT?</b>\n\n"
                        + "▫\uFE0F Ви можете придбати передплату ChatGPT і не турбуватися про ліміти;\n" +
                        "▫\uFE0F Запросіть друга та отримайте за нього 5 запитів ChatGPT.\n\n"
                        + "<b><i>Як правильно спілкуватися з ChatGPT –</i></b> https://telegra.ph/Gajd-GevaGPT-Kak-sostavit-horoshij-zapros-v-ChatGPT-11-27";

                buySubscriptionButtonText = "\uD83D\uDE80 Купити пiдписку";
                inviteFriendButtonText = "\uD83D\uDC65 Запросiть друга";
                changeLanguageButtonText = "\uD83C\uDF0D Змiнити мову";
            }

            case EN -> {
                String preMessage = hasSubscribe ?
                        "\uD83D\uDCC5 <b>Subscription expires:</b> " + subscribeDateString + "\n"
                        : "\n";

                messageText = "<b>GevaGPT</b>\n\n"
                        + "\uD83C\uDD94 <b>User ID:</b> <code>" + user.getTelegramUserId() + "</code>\n"
                        + preMessage
                        + "\uD83D\uDD0D <b>Requests:</b> " + user.getUserData().getAvailableRequests() + "\n"
                        + "\uD83D\uDC40 <b>Registered:</b> " + timestampDateString + "\n\n"
                        + "<b>Not enough ChatGPT requests?</b>\n\n"
                        + "▫\uFE0F You can purchase a ChatGPT subscription and not worry about limits;\n" +
                        "▫\uFE0F Invite a friend and get 5 ChatGPT requests for him.\n\n"
                        + "<b><i>How to properly communicate with ChatGPT –</i></b> https://telegra.ph/GevaGPT-Guide-How-to-write-a-ChatGPT-application-correctly-11-27";

                buySubscriptionButtonText = "\uD83D\uDE80 Buy a subscription";
                inviteFriendButtonText = "\uD83D\uDC65 Invite a friend";
                changeLanguageButtonText = "\uD83C\uDF0D Change language";
            }
        }

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton buySubscriptionButton = InlineKeyboardButton.builder()
                .callbackData(ProfileCallback.BUY_SUBSCRIPTION_CALLBACK_DATA)
                .text(buySubscriptionButtonText)
                .build();

        InlineKeyboardButton inviteFriendButton = InlineKeyboardButton.builder()
                .callbackData(ProfileCallback.INVITE_FRIEND_CALLBACK_DATA)
                .text(inviteFriendButtonText)
                .build();

        InlineKeyboardButton changeLanguageButton = InlineKeyboardButton.builder()
                .callbackData(ProfileCallback.CHANGE_LANGUAGE_CALLBACK_DATA)
                .text(changeLanguageButtonText)
                .build();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow2 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow3 = new ArrayList<>();

        keyboardButtonsRow1.add(buySubscriptionButton);
        keyboardButtonsRow2.add(inviteFriendButton);
        keyboardButtonsRow3.add(changeLanguageButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);
        rowList.add(keyboardButtonsRow2);
        rowList.add(keyboardButtonsRow3);

        inlineKeyboardMarkup.setKeyboard(rowList);

        SendMessage sendMessage = SendMessage.builder()
                .chatId(chatId)
                .text(messageText)
                .parseMode("html")
                .replyMarkup(inlineKeyboardMarkup)
                .disableWebPagePreview(true)
                .build();

        try {
            execute(sendMessage);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    public void editProfileMessage(User user, int messageId, long chatId) {
        int availableRequests = user.getUserData().getAvailableRequests();
        boolean hasSubscribe = user.getUserData().getSubscription() != null;
        String subscribeDateString = null;
        String timestampDateString = null;

        if (hasSubscribe) {
            Date subscribeDate = new Date(user.getUserData().getSubscription().getExpiration());

            SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy");

            subscribeDateString = dateFormat.format(subscribeDate);
        }

        Date timestampDate = new Date(user.getTimestamp());

        SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy");

        timestampDateString = dateFormat.format(timestampDate);

        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);
        UserSessionDataLanguage userSessionDataLanguage;

        if (userSessionData == null) {
            userSessionDataLanguage = UserSessionDataLanguage.RU;
        } else {
            userSessionDataLanguage = userSessionData.getLanguage();
        }

        String messageText = null;

        String buySubscriptionButtonText = null;
        String inviteFriendButtonText = null;
        String changeLanguageButtonText = null;

        switch (userSessionDataLanguage) {
            case RU -> {
                String preMessage = hasSubscribe ?
                        "\uD83D\uDCC5 <b>Подписка истекает:</b> " + subscribeDateString + "\n"
                        : "\n";

                messageText = "<b>GevaGPT</b>\n\n"
                        + "\uD83C\uDD94 <b>User ID:</b> <code>" + user.getTelegramUserId() + "</code>\n"
                        + preMessage
                        + "\uD83D\uDD0D <b>Запросов:</b> " + user.getUserData().getAvailableRequests() + "\n"
                        + "\uD83D\uDC40 <b>Зарегистрирован:</b> " + timestampDateString + "\n\n"
                        + "<b>Недостаточно запросов ChatGPT?</b>\n\n"
                        + "▫\uFE0F Вы можете приобрести подписку ChatGPT и не беспокоиться о лимитах;\n" +
                        "▫\uFE0F Пригласите друга и получите за него 5 запросов ChatGPT.\n\n"
                        + "<b><i>Как правильно общаться с ChatGPT –</i></b> https://telegra.ph/Gajd-GevaGPT-Kak-sostavit-horoshij-zapros-v-ChatGPT-11-27";

                buySubscriptionButtonText = "\uD83D\uDE80 Купить подписку";
                inviteFriendButtonText = "\uD83D\uDC65 Пригласить друга";
                changeLanguageButtonText = "\uD83C\uDF0D Сменить язык бота";
            }

            case UA -> {
                String preMessage = hasSubscribe ?
                        "\uD83D\uDCC5 <b>Передплата закінчується:</b> " + subscribeDateString + "\n"
                        : "\n";

                messageText = "<b>GevaGPT</b>\n\n"
                        + "\uD83C\uDD94 <b>User ID:</b> <code>" + user.getTelegramUserId() + "</code>\n"
                        + preMessage
                        + "\uD83D\uDD0D <b>Запитів:</b> " + user.getUserData().getAvailableRequests() + "\n"
                        + "\uD83D\uDC40 <b>Зареєстровано:</b> " + user.getTimestamp() + "\n\n"
                        + "<b>Недостатньо запитів ChatGPT?</b>\n\n"
                        + "▫\uFE0F Ви можете придбати передплату ChatGPT і не турбуватися про ліміти;\n" +
                        "▫\uFE0F Запросіть друга та отримайте за нього 5 запитів ChatGPT.\n\n"
                        + "<b><i>Як правильно спілкуватися з ChatGPT –</i></b> https://telegra.ph/Gajd-GevaGPT-Kak-sostavit-horoshij-zapros-v-ChatGPT-11-27";

                buySubscriptionButtonText = "\uD83D\uDE80 Купити пiдписку";
                inviteFriendButtonText = "\uD83D\uDC65 Запросiть друга";
                changeLanguageButtonText = "\uD83C\uDF0D Змiнити мову";
            }

            case EN -> {
                String preMessage = hasSubscribe ?
                        "\uD83D\uDCC5 <b>Subscription expires:</b> " + subscribeDateString + "\n"
                        : "\n";

                messageText = "<b>GevaGPT</b>\n\n"
                        + "\uD83C\uDD94 <b>User ID:</b> <code>" + user.getTelegramUserId() + "</code>\n"
                        + preMessage
                        + "\uD83D\uDD0D <b>Requests:</b> " + user.getUserData().getAvailableRequests() + "\n"
                        + "\uD83D\uDC40 <b>Registered:</b> " + timestampDateString + "\n\n"
                        + "<b>Not enough ChatGPT requests?</b>\n\n"
                        + "▫\uFE0F You can purchase a ChatGPT subscription and not worry about limits;\n" +
                        "▫\uFE0F Invite a friend and get 5 ChatGPT requests for him.\n\n"
                        + "<b><i>How to properly communicate with ChatGPT –</i></b> https://telegra.ph/GevaGPT-Guide-How-to-write-a-ChatGPT-application-correctly-11-27";

                buySubscriptionButtonText = "\uD83D\uDE80 Buy a subscription";
                inviteFriendButtonText = "\uD83D\uDC65 Invite a friend";
                changeLanguageButtonText = "\uD83C\uDF0D Change language";
            }
        }

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton buySubscriptionButton = InlineKeyboardButton.builder()
                .callbackData(ProfileCallback.BUY_SUBSCRIPTION_CALLBACK_DATA)
                .text(buySubscriptionButtonText)
                .build();

        InlineKeyboardButton inviteFriendButton = InlineKeyboardButton.builder()
                .callbackData(ProfileCallback.INVITE_FRIEND_CALLBACK_DATA)
                .text(inviteFriendButtonText)
                .build();

        InlineKeyboardButton changeLanguageButton = InlineKeyboardButton.builder()
                .callbackData(ProfileCallback.CHANGE_LANGUAGE_CALLBACK_DATA)
                .text(changeLanguageButtonText)
                .build();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow2 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow3 = new ArrayList<>();

        keyboardButtonsRow1.add(buySubscriptionButton);
        keyboardButtonsRow2.add(inviteFriendButton);
        keyboardButtonsRow3.add(changeLanguageButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);
        rowList.add(keyboardButtonsRow2);
        rowList.add(keyboardButtonsRow3);

        inlineKeyboardMarkup.setKeyboard(rowList);

        EditMessageText editMessage = EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text(messageText)
                .parseMode("html")
                .replyMarkup(inlineKeyboardMarkup)
                .disableWebPagePreview(true)
                .build();

        try {
            execute(editMessage);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void editInviteFriendMessage(User user, int messageId, long chatId, long userId) {
        int invited = user.getUserData().getInvited();

        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);
        UserSessionDataLanguage userSessionDataLanguage;

        if (userSessionData == null) {
            userSessionDataLanguage = UserSessionDataLanguage.RU;
        } else {
            userSessionDataLanguage = userSessionData.getLanguage();
        }

        String messageText = null;
        String backButtonText = null;

        switch (userSessionDataLanguage) {
            case RU -> {
                messageText = "\uD83E\uDD1D <b>Зарабатывайте запросы с нашей реферальной системой</b>\n\n"
                        + "С каждого приглашенного пользователя вы получаете: 5 запросов ChatGPT\n\n"
                        + "Приглашено за все время: " + invited + " \n\n"
                        + "<code>https://t.me/gevagpt_bot?start=" + userId + "</code>";

                backButtonText = "\uD83D\uDD19 Назад";
            }
            case UA -> {
                messageText = "\uD83E\uDD1D <b>Заробляйте запити за допомогою нашої реферальної системи</b>\n\n"
                        + "З кожного запрошеного Користувача Ви отримуєте: 5 запитів ChatGPT\n\n"
                        + "Запрошено за весь час: " + invited + " \n\n"
                        + "<code>https://t.me/gevagpt_bot?start=" + userId + "</code>";

                backButtonText = "\uD83D\uDD19 Заднiй";
            }
            case EN -> {
                messageText = "\uD83E\uDD1D <b>Earn requests with our referral system</b>\n\n"
                        + "From each invited user you receive: 5 ChatGPT requests\n\n"
                        + "Invited for all time: " + invited + " \n\n"
                        + "<code>https://t.me/gevagpt_bot?start=" + userId + "</code>";

                backButtonText = "\uD83D\uDD19 Back";
            }
        }

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton backProfileButton = InlineKeyboardButton.builder()
                .callbackData(BackCallback.BACK_PROFILE_MESSAGE_CALLBACK_DATA)
                .text(backButtonText)
                .build();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

        keyboardButtonsRow1.add(backProfileButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);

        inlineKeyboardMarkup.setKeyboard(rowList);

        EditMessageText editMessageText = EditMessageText.builder()
                .messageId(messageId)
                .chatId(chatId)
                .text(messageText)
                .parseMode("html")
                .disableWebPagePreview(true)
                .replyMarkup(inlineKeyboardMarkup)
                .build();

        try {
            execute(editMessageText);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void handlePayMessage(long chatId) {
        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);
        UserSessionDataLanguage userSessionDataLanguage;

        if (userSessionData == null) {
            userSessionDataLanguage = UserSessionDataLanguage.RU;
        } else {
            userSessionDataLanguage = userSessionData.getLanguage();
        }

        String messageText = null;

        String sevenDaysButtonText = null;
        String thirtyButtonText = null;
        String ninetyButtonText = null;

        switch (userSessionDataLanguage) {
            case RU -> {
                messageText = "<b>Что дает подписка:</b>\n\n"
                        + "▫\uFE0F Отсутствие рекламы;\n"
                        + "▫\uFE0F GPT-3.5 — безлимитное количество запросов;\n"
                        + "▫\uFE0F GPT-4 — безлимитное количество запросов;\n"
                        + "▫\uFE0F GPT-4 Turbo — безлимитное количество запросов;\n"
                        + "▫\uFE0F GPT-4 Vision — безлимитное количество запросов;\n"
                        + "▫\uFE0F Приоритетная обработка запросов;\n"
                        + "▫\uFE0F Доступ к новым версиям СhatGPT.";

                sevenDaysButtonText = "\uD83D\uDCC5 Безлимит на 7 дней - 199 ₽";
                thirtyButtonText = "\uD83D\uDCC5 Безлимит на 30 дней - 399 ₽";
                ninetyButtonText = "\uD83D\uDCC5 Безлимит на 90 дней - 899 ₽";
            }

            case UA -> {
                messageText = "<b>Що дає підписка:</b>\n\n"
                        + "▫\uFE0F Відсутність реклами;\n"
                        + "▫\uFE0F GPT-3.5 — безлімітна кількість запитів;\n"
                        + "▫\uFE0F GPT-4 — безлімітна кількість запитів;\n"
                        + "▫\uFE0F GPT-4 Turbo — безлімітна кількість запитів;\n"
                        + "▫\uFE0F GPT-4 Vision — безлімітна кількість запитів;\n"
                        + "▫\uFE0F Пріоритетна обробка запитів;\n"
                        + "▫\uFE0F Доступ до нових версій СhatGPT.";

                sevenDaysButtonText = "\uD83D\uDCC5 Безлiмiт на 7 днiв - 2.2 $";
                thirtyButtonText = "\uD83D\uDCC5 Безлiмiт на 30 днiв - 4.5 $";
                ninetyButtonText = "\uD83D\uDCC5 Безлiмiт на 90 днiв - 10 $";
            }

            case EN -> {
                messageText = "<b>What does a subscription give:</b>\n\n"
                        + "▫\uFE0F No advertising;\n"
                        + "▫\uFE0F GPT-3.5 — unlimited number of requests;\n"
                        + "▫\uFE0F GPT-4 — unlimited number of requests;\n"
                        + "▫\uFE0F GPT-4 Turbo — unlimited number of requests;\n"
                        + "▫\uFE0F GPT-4 Vision — unlimited number of requests;\n"
                        + "▫\uFE0F Priority processing of requests;\n"
                        + "▫\uFE0F Access to new versions of ChatGPT.";

                sevenDaysButtonText = "\uD83D\uDCC5 Unlimited for 7 days - 2.2 $";
                thirtyButtonText = "\uD83D\uDCC5 Unlimited for 30 days - 4.5 $";
                ninetyButtonText = "\uD83D\uDCC5 Unlimited for 90 days - 10 $";
            }
        }

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton sevenButton = InlineKeyboardButton.builder()
                .callbackData(PaymentCallback.PAYMENT_SEVEN_CALLBACK_DATA)
                .text(sevenDaysButtonText)
                .build();

        InlineKeyboardButton thirtyButton = InlineKeyboardButton.builder()
                .callbackData(PaymentCallback.PAYMENT_THIRTY_CALLBACK_DATA)
                .text(thirtyButtonText)
                .build();

        InlineKeyboardButton ninetyButton = InlineKeyboardButton.builder()
                .callbackData(PaymentCallback.PAYMENT_NINETY_CALLBACK_DATA)
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

        InputStream animationStream = getClass().getClassLoader().getResourceAsStream("subs.gif");

        InputFile animationInputFile = new InputFile(animationStream, "subs.gif");

        SendAnimation sendAnimation = SendAnimation.builder()
                .chatId(chatId)
                .caption(messageText)
                .replyMarkup(inlineKeyboardMarkup)
                .animation(animationInputFile)
                .parseMode("html")
                .build();

        try {
            execute(sendAnimation);
            animationStream.close();
        } catch (TelegramApiException | IOException e) {
            e.printStackTrace();
        }
    }

    private void handleSupportMessage(long userId, long chatId) {
        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);
        UserSessionDataLanguage userSessionDataLanguage;

        if (userSessionData == null) {
            userSessionDataLanguage = UserSessionDataLanguage.RU;
        } else {
            userSessionDataLanguage = userSessionData.getLanguage();
        }

        String messageText = null;

        String techSupportButtonText = null;
        String adSupportButtonText = null;

        switch (userSessionDataLanguage) {
            case RU -> {
                messageText = "\uD83D\uDEE0 При обращении в техническую поддержку, обязательно сообщите этот код: <code>#" + userId + "</code>";

                techSupportButtonText = "\uD83D\uDD27 Техническая поддержка";
                adSupportButtonText = "\uD83E\uDD1D Реклама и прочие вопросы";
            }
            case UA -> {
                messageText = "\uD83D\uDEE0 При зверненні в технічну підтримку, обов'язково повідомте цей код: <code>#" + userId + "</code>";

                techSupportButtonText = "\uD83D\uDD27 Технічна підтримка";
                adSupportButtonText = "\uD83E\uDD1D Реклама та інші питання";
            }
            case EN -> {
                messageText = "\uD83D\uDEE0 When contacting technical support, be sure to provide this code: <code>#" + userId + "</code>";

                techSupportButtonText = "\uD83D\uDD27 Technical support";
                adSupportButtonText = "\uD83E\uDD1D Advertising and other matters";
            }
        }
        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton techSupportButton = InlineKeyboardButton.builder()
                .text(techSupportButtonText)
                .url("https://t.me/blockchainseed")
                .build();

        InlineKeyboardButton adSupportButton = InlineKeyboardButton.builder()
                .text(adSupportButtonText)
                .url("https://t.me/blockchainseed")
                .build();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow2 = new ArrayList<>();

        keyboardButtonsRow1.add(techSupportButton);
        keyboardButtonsRow2.add(adSupportButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);
        rowList.add(keyboardButtonsRow2);

        inlineKeyboardMarkup.setKeyboard(rowList);

        SendMessage sendMessage = SendMessage.builder()
                .chatId(chatId)
                .text(messageText)
                .replyMarkup(inlineKeyboardMarkup)
                .parseMode("html")
                .build();

        try {
            execute(sendMessage);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    public void createChatCompletion(User user, long chatId, String text, String imageUrl) {
        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);

        String userSessionDataRoleText = null;

        if (userSessionData == null) {
            userSessionData = getDefUserSessionData();
        }

        String typingText = null;
        String insufficientBalanceImgPath = null;

        String errorText = null;

        String sevenDaysButtonText = null;
        String thirtyButtonText = null;
        String ninetyButtonText = null;

        switch (userSessionData.getLanguage()) {
            case RU -> {
                insufficientBalanceImgPath = "ruinsuff.gif";
                typingText = "Печатает...";

                sevenDaysButtonText = "\uD83D\uDCC5 Безлимит на 7 дней - 199 ₽";
                thirtyButtonText = "\uD83D\uDCC5 Безлимит на 30 дней - 399 ₽";
                ninetyButtonText = "\uD83D\uDCC5 Безлимит на 90 дней - 899 ₽";

                errorText = "❌ Для использования <b>ChatGPT 4 Vision</b> нужно приложить фотографию к сообщению";

                switch (userSessionData.getRole()) {
                    case POET -> userSessionDataRoleText = "Общайся в этом диалоге в роли - Поэта";
                    case BULLY -> userSessionDataRoleText = "Общайся в этом диалоге в роли - Русского Гопника";
                    case JOKER -> userSessionDataRoleText = "Общайся в этом диалоге в роли - Шутника";
                    case CHAT_GPT -> userSessionDataRoleText = "Общайся в этом диалоге в роли - ChatGPT";
                    case EINSTEIN -> userSessionDataRoleText = "Общайся в этом диалоге в роли - Профессора Энштейна";
                    case LINGUIST -> userSessionDataRoleText = "Общайся в этом диалоге в роли - Лучшего Лингвиста";
                    case MONA_LISA -> userSessionDataRoleText = "Общайся в этом диалоге в роли - Мона Лизы";
                    case PROGRAMMER -> userSessionDataRoleText = "Общайся в этом диалоге в роли - Программиста";
                    case PSYCOLOGIST -> userSessionDataRoleText = "Общайся в этом диалоге в роли - Психолога";
                }
            }
            case EN -> {
                insufficientBalanceImgPath = "eninsuff.gif";
                typingText = "Typing...";

                sevenDaysButtonText = "\uD83D\uDCC5 Unlimited for 7 days - 2.2 $";
                thirtyButtonText = "\uD83D\uDCC5 Unlimited for 30 days - 4.5 $";
                ninetyButtonText = "\uD83D\uDCC5 Unlimited for 90 days - 10 $";

                errorText = "❌ To use <b>ChatGPT 4 Vision</b> you must attach a photo to your message";

                switch (userSessionData.getRole()) {
                    case POET -> userSessionDataRoleText = "Communicate in this dialogue as - Poet";
                    case BULLY -> userSessionDataRoleText = "Communicate in this dialogue as - Bully";
                    case JOKER -> userSessionDataRoleText = "Communicate in this dialogue as - Joker";
                    case CHAT_GPT -> userSessionDataRoleText = "Communicate in this dialogue as - ChatGPT";
                    case EINSTEIN -> userSessionDataRoleText = "Communicate in this dialogue as - Professor Einstein";
                    case LINGUIST -> userSessionDataRoleText = "Communicate in this dialogue as - Linguist";
                    case MONA_LISA -> userSessionDataRoleText = "Communicate in this dialogue as - Mona Lisa";
                    case PROGRAMMER -> userSessionDataRoleText = "Communicate in this dialogue as - Programmer";
                    case PSYCOLOGIST -> userSessionDataRoleText = "Communicate in this dialogue as - Psycologist";
                }
            }
            case UA -> {
                insufficientBalanceImgPath = "uainsuff.gif";
                typingText = "Друкує...";

                sevenDaysButtonText = "\uD83D\uDCC5 Unlimited for 7 days - 2.2 $";
                thirtyButtonText = "\uD83D\uDCC5 Unlimited for 30 days - 4.5 $";
                ninetyButtonText = "\uD83D\uDCC5 Unlimited for 90 days - 10 $";

                errorText = "❌ Для використання <b>ChatGPT 4 Vision</b> потрібно додати фотографію до повідомлення";

                switch (userSessionData.getRole()) {
                    case POET -> userSessionDataRoleText = "Спілкуйся в цьому діалозі в ролі - Поета";
                    case BULLY -> userSessionDataRoleText = "Спілкуйся в цьому діалозі в ролі - Російського Гопника";
                    case JOKER -> userSessionDataRoleText = "Спілкуйся в цьому діалозі в ролі - Жартівника";
                    case CHAT_GPT -> userSessionDataRoleText = "Спілкуйся в цьому діалозі в ролі - ChatGPT";
                    case EINSTEIN -> userSessionDataRoleText = "Спілкуйся в цьому діалозі в ролі - Професора Енштейна";
                    case LINGUIST -> userSessionDataRoleText = "Спілкуйся в цьому діалозі в ролі - Найкращого лінгвіста";
                    case MONA_LISA -> userSessionDataRoleText = "Спілкуйся в цьому діалозі в ролі - Мона Лізи";
                    case PROGRAMMER -> userSessionDataRoleText = "Спілкуйся в цьому діалозі в ролі - Програміста";
                    case PSYCOLOGIST -> userSessionDataRoleText = "Спілкуйся в цьому діалозі в ролі - Психолога";
                }
            }
        }

        if (user.getUserData().getSubscription() == null && user.getUserData().getAvailableRequests() < 1) {
            InputStream videoStream = getClass().getClassLoader().getResourceAsStream(insufficientBalanceImgPath);

            InputFile animationInputFile = new InputFile(videoStream, insufficientBalanceImgPath);

            InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

            InlineKeyboardButton sevenButton = InlineKeyboardButton.builder()
                    .callbackData(PaymentCallback.PAYMENT_SEVEN_CALLBACK_DATA)
                    .text(sevenDaysButtonText)
                    .build();

            InlineKeyboardButton thirtyButton = InlineKeyboardButton.builder()
                    .callbackData(PaymentCallback.PAYMENT_THIRTY_CALLBACK_DATA)
                    .text(thirtyButtonText)
                    .build();

            InlineKeyboardButton ninetyButton = InlineKeyboardButton.builder()
                    .callbackData(PaymentCallback.PAYMENT_NINETY_CALLBACK_DATA)
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

            SendAnimation sendAnimation = SendAnimation.builder()
                    .animation(animationInputFile)
                    .chatId(chatId)
                    .replyMarkup(inlineKeyboardMarkup)
                    .build();

            try {
                execute(sendAnimation);
                videoStream.close();
            } catch (TelegramApiException | IOException e) {
                e.printStackTrace();
            }

            return;
        }

        SendMessage sendMessage = SendMessage.builder()
                .chatId(chatId)
                .text(typingText)
                .build();

        SendChatAction sendChatAction = new SendChatAction();

        sendChatAction.setChatId(chatId);
        sendChatAction.setAction(ActionType.TYPING);

        Message message = null;

        try {
            message = execute(sendMessage);
            execute(sendChatAction);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }

        Map<Integer, ChatRequestDto.Message> messageHistory = userSessionData.getMessageHistory();

        messageHistory.forEach((c1, c2) -> {
            System.out.println(c2.getContent());
        });

        int lastKey = 0;

        for (Map.Entry<Integer, ChatRequestDto.Message> key : messageHistory.entrySet()) {
            if (key.getKey() > lastKey) {
                lastKey = key.getKey();
            }
        }

        lastKey += 1;

        if (userSessionData.getModel().equals("gpt-4-vision-preview")){
            if (imageUrl == null){
                EditMessageText editMessageText = EditMessageText.builder()
                        .text(errorText)
                        .messageId(message.getMessageId())
                        .chatId(message.getChatId())
                        .parseMode("html")
                        .build();

                try {
                    execute(editMessageText);
                } catch (TelegramApiException e){
                    e.printStackTrace();
                }
                return;
            }
            List<ChatRequestWithImageDto.Message> chatRequestWithImageDtoMessages = new ArrayList<>();

            ChatRequestWithImageDto.Content content = ChatRequestWithImageDto.Content.builder()
                    .type("text")
                    .text(userSessionDataRoleText)
                    .build();

            ChatRequestWithImageDto.Message initialChatRueqestDtoMessage = ChatRequestWithImageDto.Message.builder()
                    .role("system")
                    .contents(List.of(content))
                    .build();

            chatRequestWithImageDtoMessages.add(initialChatRueqestDtoMessage);

            for (Map.Entry<Integer, ChatRequestDto.Message> key : messageHistory.entrySet()) {
                String role = key.getValue().getRole();
                String content1 = key.getValue().getContent();

                ChatRequestWithImageDto.Content content2 = ChatRequestWithImageDto.Content.builder()
                        .type("text")
                        .text(content1)
                        .build();

                chatRequestWithImageDtoMessages.add(ChatRequestWithImageDto.Message.builder().role(role).contents(List.of(content2)).build());
            }

            List<ChatRequestWithImageDto.Content> contents = List.of(
                    ChatRequestWithImageDto.Content.builder()
                            .type("text")
                            .text(text)
                            .build(),
                    ChatRequestWithImageDto.Content.builder()
                            .type("image_url")
                            .imageUrl(new ChatRequestWithImageDto.ImageUrl(imageUrl))
                            .build()
            );

            chatRequestWithImageDtoMessages.add(ChatRequestWithImageDto.Message.builder()
                    .role("user")
                    .contents(contents)
                    .build());

            messageHistory.put(lastKey, ChatRequestDto.Message.builder().role("user").content(text).build());

            ChatRequestWithImageDto chatRequestWithImageDto = ChatRequestWithImageDto.builder()
                    .maxTokens(300)
                    .model(userSessionData.getModel())
                    .messages(chatRequestWithImageDtoMessages)
                    .build();

            ChatResponseDto chatResponseDtoResponseEntity = openaiApi.createChatCompletionWithImage(chatRequestWithImageDto);

            lastKey += 1;

            messageHistory.put(lastKey, ChatRequestDto.Message.builder().role("assistant")
                    .content(chatResponseDtoResponseEntity.getChoices()[0].getMessage().getContent()).build());

            if (messageHistory.size() > 25) {
                userSessionData.setMessageHistory(new HashMap<>());
            } else {
                userSessionData.setMessageHistory(messageHistory);
            }

            usersSessionsDataMap.put(chatId, userSessionData);

            String completionText = TelegramTextFormatterUtil.replaceCodeTags(chatResponseDtoResponseEntity.getChoices()[0].getMessage().getContent());

            EditMessageText editMessageText = EditMessageText.builder()
                    .messageId(message.getMessageId())
                    .chatId(chatId)
                    .parseMode("html")
                    .text(completionText)
                    .build();

            try {
                execute(editMessageText);
            } catch (TelegramApiException e) {
                e.printStackTrace();
            }

        } else {
            messageHistory.put(lastKey, ChatRequestDto.Message.builder().role("user").content(text).build());

            List<ChatRequestDto.Message> chatRequestDtoMessages = new ArrayList<>();

            ChatRequestDto.Message initialChatRueqestDtoMessage = ChatRequestDto.Message.builder()
                    .role("system")
                    .content(userSessionDataRoleText)
                    .build();

            chatRequestDtoMessages.add(initialChatRueqestDtoMessage);

            for (Map.Entry<Integer, ChatRequestDto.Message> key : messageHistory.entrySet()) {
                String role = key.getValue().getRole();
                String content = key.getValue().getContent();

                chatRequestDtoMessages.add(ChatRequestDto.Message.builder().role(role).content(content).build());
            }

            ChatRequestDto chatRequestDto = ChatRequestDto.builder()
                    .model(userSessionData.getModel())
                    .messages(chatRequestDtoMessages)
                    .build();

            ChatResponseDto chatResponseDtoResponseEntity = openaiApi.createChatCompletion(chatRequestDto);

            lastKey += 1;

            messageHistory.put(lastKey, ChatRequestDto.Message.builder().role("assistant")
                    .content(chatResponseDtoResponseEntity.getChoices()[0].getMessage().getContent()).build());

            if (messageHistory.size() > 25) {
                userSessionData.setMessageHistory(new HashMap<>());
            } else {
                userSessionData.setMessageHistory(messageHistory);
            }

            usersSessionsDataMap.put(chatId, userSessionData);

            String completionText = TelegramTextFormatterUtil.replaceCodeTags(chatResponseDtoResponseEntity.getChoices()[0].getMessage().getContent());

            EditMessageText editMessageText = EditMessageText.builder()
                    .messageId(message.getMessageId())
                    .chatId(chatId)
                    .parseMode("html")
                    .text(completionText)
                    .build();

            try {
                execute(editMessageText);
            } catch (TelegramApiException e) {
                e.printStackTrace();
            }

        }
        if (user.getUserData().getSubscription() == null) {
            user.getUserData().setAvailableRequests(user.getUserData().getAvailableRequests() - 1);
        }
        userService.create(user);
    }

    private void handleResetMessage(long chatId) {
        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);

        if (userSessionData == null) {
            userSessionData = getDefUserSessionData();
        }

        String successReset = null;

        switch (userSessionData.getLanguage()) {
            case RU -> successReset = "✅ <b>Контекст сброшен</b>";
            case UA -> successReset = "✅ <b>Контекст скинуто</b>";
            case EN -> successReset = "✅ <b>Context resetted</b>";
        }
        userSessionData.setMessageHistory(new HashMap<>());

        usersSessionsDataMap.put(chatId, userSessionData);

        SendMessage sendMessage = SendMessage.builder()
                .chatId(chatId)
                .text(successReset)
                .parseMode("html")
                .build();

        try {
            execute(sendMessage);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void handleChooseRole(long chatId) {
        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);

        if (userSessionData == null) {
            userSessionData = getDefUserSessionData();
        }

        String chooseRoleMessageText = null;

        String roleChatGptButtonText = null;
        String roleProgrammerButtonText = null;
        String rolePsycologistButtonText = null;
        String roleJokerButtonText = null;
        String roleLinguistButtonText = null;
        String rolePoetButtonText = null;
        String roleMonaLisaButtonText = null;
        String roleEinsteinButtonText = null;
        String roleBullyButtonText = null;

        roleChatGptButtonText = "ChatGPT \uD83E\uDD16";

        switch (userSessionData.getLanguage()) {
            case RU -> {
                chooseRoleMessageText = "⚙\uFE0F <b>Выберите режим для чата из предложенного списка. После выбора предыдущий диалог будет завершен:</b>";

                roleProgrammerButtonText = "Программист \uD83D\uDC68\u200D\uD83D\uDCBB";
                rolePsycologistButtonText = "Психолог \uD83E\uDDD1\u200D⚕\uFE0F";
                roleJokerButtonText = "Шутник \uD83E\uDD39";
                roleLinguistButtonText = "Лингвист \uD83D\uDC69\u200D\uD83C\uDFEB";
                rolePoetButtonText = "Поэт \uD83D\uDCDD";
                roleMonaLisaButtonText = "Мона Лиза \uD83D\uDDBC";
                roleEinsteinButtonText = "Эйнштейн \uD83E\uDDE0";
                roleBullyButtonText = "Гопник \uD83E\uDD2C";

                switch (userSessionData.getRole()) {
                    case CHAT_GPT -> roleChatGptButtonText = roleChatGptButtonText + " [ВЫБРАНО]";
                    case PROGRAMMER -> roleProgrammerButtonText = roleProgrammerButtonText + " [ВЫБРАНО]";
                    case PSYCOLOGIST -> rolePsycologistButtonText = rolePsycologistButtonText + " [ВЫБРАНО]";
                    case JOKER -> roleJokerButtonText = roleJokerButtonText + " [ВЫБРАНО]";
                    case LINGUIST -> roleLinguistButtonText = roleLinguistButtonText + " [ВЫБРАНО]";
                    case POET -> rolePoetButtonText = rolePoetButtonText + " [ВЫБРАНО]";
                    case MONA_LISA -> roleMonaLisaButtonText = roleMonaLisaButtonText + " [ВЫБРАНО]";
                    case EINSTEIN -> roleEinsteinButtonText = roleEinsteinButtonText + " [ВЫБРАНО]";
                    case BULLY -> roleBullyButtonText = roleBullyButtonText + " [ВЫБРАНО]";
                }

            }
            case UA -> {
                chooseRoleMessageText = "⚙\uFE0F <b>Виберіть режим для чату із запропонованого списку. Після вибору попередній діалог буде завершено:</b>";

                roleProgrammerButtonText = "Програмiст \uD83D\uDC68\u200D\uD83D\uDCBB";
                rolePsycologistButtonText = "Психолог \uD83E\uDDD1\u200D⚕\uFE0F";
                roleJokerButtonText = "Жартiвник \uD83E\uDD39";
                roleLinguistButtonText = "Лiнгвiст \uD83D\uDC69\u200D\uD83C\uDFEB";
                rolePoetButtonText = "Поет \uD83D\uDCDD";
                roleMonaLisaButtonText = "Мона Лiза \uD83D\uDDBC";
                roleEinsteinButtonText = "Ейнштейн \uD83E\uDDE0";
                roleBullyButtonText = "Гопник \uD83E\uDD2C";

                switch (userSessionData.getRole()) {
                    case CHAT_GPT -> roleChatGptButtonText = roleChatGptButtonText + " [ВИБРАНО]";
                    case PROGRAMMER -> roleProgrammerButtonText = roleProgrammerButtonText + " [ВИБРАНО]";
                    case PSYCOLOGIST -> rolePsycologistButtonText = rolePsycologistButtonText + " [ВИБРАНО]";
                    case JOKER -> roleJokerButtonText = roleJokerButtonText + " [ВИБРАНО]";
                    case LINGUIST -> roleLinguistButtonText = roleLinguistButtonText + " [ВИБРАНО]";
                    case POET -> rolePoetButtonText = rolePoetButtonText + " [ВИБРАНО]";
                    case MONA_LISA -> roleMonaLisaButtonText = roleMonaLisaButtonText + " [ВИБРАНО]";
                    case EINSTEIN -> roleEinsteinButtonText = roleEinsteinButtonText + " [ВИБРАНО]";
                    case BULLY -> roleBullyButtonText = roleBullyButtonText + " [ВИБРАНО]";
                }
            }
            case EN -> {
                chooseRoleMessageText = "⚙\uFE0F <b>Select the chat mode from the suggested list. After selecting the previous dialog will be dropped:</b>";

                roleProgrammerButtonText = "Programmer \uD83D\uDC68\u200D\uD83D\uDCBB";
                rolePsycologistButtonText = "Psycologist \uD83E\uDDD1\u200D⚕\uFE0F";
                roleJokerButtonText = "Joker \uD83E\uDD39";
                roleLinguistButtonText = "Linguist \uD83D\uDC69\u200D\uD83C\uDFEB";
                rolePoetButtonText = "Poet \uD83D\uDCDD";
                roleMonaLisaButtonText = "Mona Lisa \uD83D\uDDBC";
                roleEinsteinButtonText = "Einstein \uD83E\uDDE0";
                roleBullyButtonText = "Bully \uD83E\uDD2C";

                switch (userSessionData.getRole()) {
                    case CHAT_GPT -> roleChatGptButtonText = roleChatGptButtonText + " [CHOSEN]";
                    case PROGRAMMER -> roleProgrammerButtonText = roleProgrammerButtonText + " [CHOSEN]";
                    case PSYCOLOGIST -> rolePsycologistButtonText = rolePsycologistButtonText + " [CHOSEN]";
                    case JOKER -> roleJokerButtonText = roleJokerButtonText + " [CHOSEN]";
                    case LINGUIST -> roleLinguistButtonText = roleLinguistButtonText + " [CHOSEN]";
                    case POET -> rolePoetButtonText = rolePoetButtonText + " [CHOSEN]";
                    case MONA_LISA -> roleMonaLisaButtonText = roleMonaLisaButtonText + " [CHOSEN]";
                    case EINSTEIN -> roleEinsteinButtonText = roleEinsteinButtonText + " [CHOSEN]";
                    case BULLY -> roleBullyButtonText = roleBullyButtonText + " [CHOSEN]";
                }
            }
        }


        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton roleChatGptButton = InlineKeyboardButton.builder()
                .callbackData(ChooseCallback.CHOOSE_CHAT_GPT_ROLE_CALLBACK_DATA)
                .text(roleChatGptButtonText)
                .build();

        InlineKeyboardButton roleProgrammerButton = InlineKeyboardButton.builder()
                .callbackData(ChooseCallback.CHOOSE_PROGRAMMER_ROLE_CALLBACK_DATA)
                .text(roleProgrammerButtonText)
                .build();

        InlineKeyboardButton rolePsycologistButton = InlineKeyboardButton.builder()
                .callbackData(ChooseCallback.CHOOSE_PSYCOLOGIST_ROLE_CALLBACK_DATA)
                .text(rolePsycologistButtonText)
                .build();

        InlineKeyboardButton roleJokerButton = InlineKeyboardButton.builder()
                .callbackData(ChooseCallback.CHOOSE_JOKER_ROLE_CALLBACK_DATA)
                .text(roleJokerButtonText)
                .build();

        InlineKeyboardButton roleLinguistButton = InlineKeyboardButton.builder()
                .callbackData(ChooseCallback.CHOOSE_LINGUIST_ROLE_CALLBACK_DATA)
                .text(roleLinguistButtonText)
                .build();

        InlineKeyboardButton rolePoetButton = InlineKeyboardButton.builder()
                .callbackData(ChooseCallback.CHOOSE_POET_ROLE_CALLBACK_DATA)
                .text(rolePoetButtonText)
                .build();

        InlineKeyboardButton roleMonaLisaButton = InlineKeyboardButton.builder()
                .callbackData(ChooseCallback.CHOOSE_MONA_LISA_ROLE_CALLBACK_DATA)
                .text(roleMonaLisaButtonText)
                .build();

        InlineKeyboardButton roleEinsteinButton = InlineKeyboardButton.builder()
                .callbackData(ChooseCallback.CHOOSE_EINSTEIN_ROLE_CALLBACK_DATA)
                .text(roleEinsteinButtonText)
                .build();

        InlineKeyboardButton roleBullyButton = InlineKeyboardButton.builder()
                .callbackData(ChooseCallback.CHOOSE_BULLY_ROLE_CALLBACK_DATA)
                .text(roleBullyButtonText)
                .build();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow2 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow3 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow4 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow5 = new ArrayList<>();

        keyboardButtonsRow1.add(roleChatGptButton);
        keyboardButtonsRow2.add(roleProgrammerButton);
        keyboardButtonsRow2.add(rolePsycologistButton);
        keyboardButtonsRow3.add(roleJokerButton);
        keyboardButtonsRow3.add(roleLinguistButton);
        keyboardButtonsRow4.add(rolePoetButton);
        keyboardButtonsRow4.add(roleMonaLisaButton);
        keyboardButtonsRow5.add(roleEinsteinButton);
        keyboardButtonsRow5.add(roleBullyButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);
        rowList.add(keyboardButtonsRow2);
        rowList.add(keyboardButtonsRow3);
        rowList.add(keyboardButtonsRow4);
        rowList.add(keyboardButtonsRow5);

        inlineKeyboardMarkup.setKeyboard(rowList);

        SendMessage message = SendMessage.builder()
                .text(chooseRoleMessageText)
                .chatId(chatId)
                .parseMode("html")
                .replyMarkup(inlineKeyboardMarkup)
                .build();

        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void handleAdminMessage(User user, long chatId) {
        if (!user.getRole().equals(UserRole.ADMIN)) {
            SendMessage message = SendMessage.builder()
                    .chatId(chatId)
                    .text("\uD83D\uDEAB <b>У вас не хватает прав</b>")
                    .parseMode("html")
                    .build();

            try {
                execute(message);
            } catch (TelegramApiException e) {
                e.printStackTrace();
            }
            return;
        }

        String adminMessageText = "\uD83D\uDED1 <b>Админ-панель</b>\n\n<i>«С большой силой приходит большая ответственность»</i>";

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton statButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.STAT_CALLBACK_DATA)
                .text("\uD83D\uDCC8 Статистика")
                .build();

        InlineKeyboardButton allUsersButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.ALL_USERS_DATA_CALLBACK_DATA)
                .text("\uD83D\uDC65 Все пользователи")
                .build();

        InlineKeyboardButton topDepositsButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.TOP_DEPOSITS_CALLBACK_DATA)
                .text("\uD83D\uDCB8 Топ депозитов")
                .build();

        InlineKeyboardButton giveSubscribeButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.GIVE_SUBSCRIPTION_CALLBACK_DATA)
                .text("➕ Выдать подписку")
                .build();

        InlineKeyboardButton takeSubscribeButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.TAKE_SUBSCRIPTION_CALLBACK_DATA)
                .text("➖ Забрать подписку")
                .build();

        InlineKeyboardButton giveAdminButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.GIVE_ADMIN_CALLBACK_DATA)
                .text("✔\uFE0F Выдать админку")
                .build();

        InlineKeyboardButton takeAdminButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.TAKE_ADMIN_CALLBACK_DATA)
                .text("✖\uFE0F Забрать админку")
                .build();

        InlineKeyboardButton banButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.BAN_USER_CALLBACK_DATA)
                .text("\uD83D\uDD12 Заблокировать пользователя")
                .build();

        InlineKeyboardButton unbanButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.UNBAN_USER_CALLBACK_DATA)
                .text("\uD83D\uDD13 Разблокировать пользователя")
                .build();

        InlineKeyboardButton giveAllSubscribeButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.GIVE_ALL_SUBSCRIPTION_CALLBACK_DATA)
                .text("\uD83C\uDF81 Выдать глобальную подписку")
                .build();

        InlineKeyboardButton resendButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.RESEND_ADMIN_CALLBACK_DATA)
                .text("\uD83D\uDCCE Рассылка")
                .build();


        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow2 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow3 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow4 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow5 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow6 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow7 = new ArrayList<>();

        keyboardButtonsRow1.add(statButton);

        keyboardButtonsRow2.add(allUsersButton);
        keyboardButtonsRow2.add(topDepositsButton);

        keyboardButtonsRow3.add(giveSubscribeButton);
        keyboardButtonsRow3.add(takeSubscribeButton);

        keyboardButtonsRow4.add(giveAdminButton);
        keyboardButtonsRow4.add(takeAdminButton);

        keyboardButtonsRow5.add(banButton);
        keyboardButtonsRow5.add(unbanButton);

        keyboardButtonsRow6.add(giveAllSubscribeButton);

        keyboardButtonsRow7.add(resendButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);
        rowList.add(keyboardButtonsRow2);
        rowList.add(keyboardButtonsRow3);
        rowList.add(keyboardButtonsRow4);
        rowList.add(keyboardButtonsRow5);
        rowList.add(keyboardButtonsRow6);
        rowList.add(keyboardButtonsRow7);

        inlineKeyboardMarkup.setKeyboard(rowList);


        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(adminMessageText)
                .replyMarkup(inlineKeyboardMarkup)
                .parseMode("html")
                .build();

        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void editAdminMessage(User user, long chatId, int messageId) {
        if (!user.getRole().equals(UserRole.ADMIN)) {
            EditMessageText editMessage = EditMessageText.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .text("\uD83D\uDEAB <b>У вас не хватает прав</b>")
                    .parseMode("html")
                    .build();

            try {
                execute(editMessage);
            } catch (TelegramApiException e) {
                e.printStackTrace();
            }
            return;
        }

        String adminMessageText = "\uD83D\uDED1 <b>Админ-панель</b>\n\n<i>«С большой силой приходит большая ответственность»</i>";

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton statButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.STAT_CALLBACK_DATA)
                .text("\uD83D\uDCC8 Статистика")
                .build();

        InlineKeyboardButton allUsersButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.ALL_USERS_DATA_CALLBACK_DATA)
                .text("\uD83D\uDC65 Все пользователи")
                .build();

        InlineKeyboardButton topDepositsButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.TOP_DEPOSITS_CALLBACK_DATA)
                .text("\uD83D\uDCB8 Топ депозитов")
                .build();

        InlineKeyboardButton giveSubscribeButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.GIVE_SUBSCRIPTION_CALLBACK_DATA)
                .text("➕ Выдать подписку")
                .build();

        InlineKeyboardButton takeSubscribeButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.TAKE_SUBSCRIPTION_CALLBACK_DATA)
                .text("➖ Забрать подписку")
                .build();

        InlineKeyboardButton giveAdminButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.GIVE_ADMIN_CALLBACK_DATA)
                .text("✔\uFE0F Выдать админку")
                .build();

        InlineKeyboardButton takeAdminButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.TAKE_ADMIN_CALLBACK_DATA)
                .text("✖\uFE0F Забрать админку")
                .build();

        InlineKeyboardButton banButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.BAN_USER_CALLBACK_DATA)
                .text("\uD83D\uDD12 Заблокировать пользователя")
                .build();

        InlineKeyboardButton unbanButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.UNBAN_USER_CALLBACK_DATA)
                .text("\uD83D\uDD13 Разблокировать пользователя")
                .build();

        InlineKeyboardButton giveAllSubscribeButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.GIVE_ALL_SUBSCRIPTION_CALLBACK_DATA)
                .text("\uD83C\uDF81 Выдать глобальную подписку")
                .build();

        InlineKeyboardButton resendButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.RESEND_ADMIN_CALLBACK_DATA)
                .text("\uD83D\uDCCE Рассылка")
                .build();


        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow2 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow3 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow4 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow5 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow6 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow7 = new ArrayList<>();

        keyboardButtonsRow1.add(statButton);

        keyboardButtonsRow2.add(allUsersButton);
        keyboardButtonsRow2.add(topDepositsButton);

        keyboardButtonsRow3.add(giveSubscribeButton);
        keyboardButtonsRow3.add(takeSubscribeButton);

        keyboardButtonsRow4.add(giveAdminButton);
        keyboardButtonsRow4.add(takeAdminButton);

        keyboardButtonsRow5.add(banButton);
        keyboardButtonsRow5.add(unbanButton);

        keyboardButtonsRow6.add(giveAllSubscribeButton);

        keyboardButtonsRow7.add(resendButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);
        rowList.add(keyboardButtonsRow2);
        rowList.add(keyboardButtonsRow3);
        rowList.add(keyboardButtonsRow4);
        rowList.add(keyboardButtonsRow5);
        rowList.add(keyboardButtonsRow6);
        rowList.add(keyboardButtonsRow7);

        inlineKeyboardMarkup.setKeyboard(rowList);


        EditMessageText editMessage = EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text(adminMessageText)
                .replyMarkup(inlineKeyboardMarkup)
                .parseMode("html")
                .build();

        try {
            execute(editMessage);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void editStatMessage(User user, long chatId, int messageId) {
        if (!user.getRole().equals(UserRole.ADMIN)) {
            EditMessageText editMessage = EditMessageText.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .text("\uD83D\uDEAB <b>У вас не хватает прав</b>")
                    .parseMode("html")
                    .build();

            try {
                execute(editMessage);
            } catch (TelegramApiException e) {
                e.printStackTrace();
            }
            return;
        }

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                .callbackData(BackCallback.BACK_ADMIN_CALLBACK_DATA)
                .text("\uD83D\uDD19 Назад")
                .build();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

        keyboardButtonsRow1.add(backButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);

        inlineKeyboardMarkup.setKeyboard(rowList);

        EditMessageText editMessage = EditMessageText.builder()
                .messageId(messageId)
                .chatId(chatId)
                .replyMarkup(inlineKeyboardMarkup)
                .text("⌛\uFE0F <b>Загрузка...</b>")
                .parseMode("html")
                .build();

        try {
            execute(editMessage);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }

        long usersCount = userService.getCount();
        long subscribesCount = subscriptionService.getCount();

        AtomicDouble rubEarnForAllTime = new AtomicDouble();
        AtomicDouble rubEarnForMonth = new AtomicDouble();
        AtomicDouble rubEarnForDay = new AtomicDouble();

        AtomicDouble usdEarnForAllTime = new AtomicDouble();
        AtomicDouble usdEarnForMonth = new AtomicDouble();
        AtomicDouble usdEarnForDay = new AtomicDouble();

        List<Deposit> allTimeDeposits = depositService.getAll();
        List<Deposit> monthDeposits = depositService.getAllGreaterThan(System.currentTimeMillis() - 30 * 24 * 60 * 60 * 1000L);
        List<Deposit> dayDeposits = depositService.getAllGreaterThan(System.currentTimeMillis() - 24 * 60 * 60 * 1000L);

        allTimeDeposits.forEach(c -> {
            if (c.getCurrency().equals(DepositCurrency.RUB)){
                rubEarnForAllTime.addAndGet(c.getAmount());
            } else {
                usdEarnForAllTime.addAndGet(c.getAmount());
            }
        });

        monthDeposits.forEach(c -> {
            if (c.getCurrency().equals(DepositCurrency.RUB)){
                rubEarnForMonth.addAndGet(c.getAmount());
            } else {
                usdEarnForMonth.addAndGet(c.getAmount());
            }
        });

        dayDeposits.forEach(c -> {
            if (c.getCurrency().equals(DepositCurrency.RUB)){
                rubEarnForDay.addAndGet(c.getAmount());
            } else {
                usdEarnForDay.addAndGet(c.getAmount());
            }
        });

        String editMessageText = "\uD83D\uDCC8 <b>Статистика использования бота</b>\n\n"
                + "\uD83D\uDC65 <b>Всего пользователей:</b> " + usersCount + " шт.\n"
                + "\uD83D\uDCB3 <b>Всего подписок:</b> " + subscribesCount + " шт.\n\n"
                + "\uD83D\uDCB0 <b>Выручка за все время:</b> " + rubEarnForAllTime + " ₽, " + usdEarnForAllTime + " $\n"
                + "\uD83D\uDCB0 <b>Выручка за месяц:</b> " + rubEarnForMonth + " ₽, " + usdEarnForMonth + " $\n"
                + "\uD83D\uDCB0 <b>Выручка за 24 часа:</b> " + rubEarnForDay + " ₽, " + usdEarnForDay + " $";

        EditMessageText editMessage1 = EditMessageText.builder()
                .messageId(messageId)
                .chatId(chatId)
                .replyMarkup(inlineKeyboardMarkup)
                .text(editMessageText)
                .parseMode("html")
                .build();

        try {
            execute(editMessage1);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void editTopDepositsMessage(User user, long chatId, int messageId){
        if (!user.getRole().equals(UserRole.ADMIN)) {
            EditMessageText editMessage = EditMessageText.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .text("\uD83D\uDEAB <b>У вас не хватает прав</b>")
                    .parseMode("html")
                    .build();

            try {
                execute(editMessage);
            } catch (TelegramApiException e) {
                e.printStackTrace();
            }
            return;
        }

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                .callbackData(BackCallback.BACK_ADMIN_CALLBACK_DATA)
                .text("\uD83D\uDD19 Назад")
                .build();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

        keyboardButtonsRow1.add(backButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);

        inlineKeyboardMarkup.setKeyboard(rowList);

        EditMessageText editMessage = EditMessageText.builder()
                .messageId(messageId)
                .chatId(chatId)
                .replyMarkup(inlineKeyboardMarkup)
                .text("⌛\uFE0F <b>Загрузка...</b>")
                .parseMode("html")
                .build();

        try {
            execute(editMessage);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }

        List<Long> telegramUserIds = depositService.getTopUsersByDepositCount();

        StringBuilder editMessageTextBuilder = new StringBuilder();

        int number = 1;

        for (long telegramUserId : telegramUserIds){
            int depositCounts = depositService.getCountByTelegramUserId(telegramUserId);

            editMessageTextBuilder.append("\uD83D\uDCB0 <b>")
                    .append(number).append(".</b> ")
                    .append("<code>").append(telegramUserId)
                    .append("</code>")
                    .append("\n").append("<b>Количество депозитов:</b> ")
                    .append(depositCounts)
                    .append("\n\n");

            number += 1;
        }

        EditMessageText editMessage1 = EditMessageText.builder()
                .messageId(messageId)
                .chatId(chatId)
                .replyMarkup(inlineKeyboardMarkup)
                .text("\uD83D\uDCB8 <b>Топ депозитов</b>\n\n" + editMessageTextBuilder)
                .parseMode("html")
                .build();

        try {
            execute(editMessage1);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void handleAllUserDataMessage(User user, long chatId, int messageId) {
        if (!user.getRole().equals(UserRole.ADMIN)) {
            EditMessageText editMessage = EditMessageText.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .text("\uD83D\uDEAB <b>У вас не хватает прав</b>")
                    .parseMode("html")
                    .build();

            try {
                execute(editMessage);
            } catch (TelegramApiException e) {
                e.printStackTrace();
            }
            return;
        }

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton backButton = InlineKeyboardButton.builder()
                .callbackData(BackCallback.BACK_ADMIN_CALLBACK_DATA)
                .text("\uD83D\uDD19 Назад")
                .build();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();

        keyboardButtonsRow1.add(backButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);

        inlineKeyboardMarkup.setKeyboard(rowList);

        EditMessageText editMessage = EditMessageText.builder()
                .messageId(messageId)
                .chatId(chatId)
                .replyMarkup(inlineKeyboardMarkup)
                .text("⌛\uFE0F <b>Загрузка...</b>")
                .parseMode("html")
                .build();

        try {
            execute(editMessage);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }

        List<User> userList = userService.getAll();

        File tempFile = null;

        try {
            tempFile = File.createTempFile("tempfile" + System.currentTimeMillis(), ".txt");
        } catch (IOException e) {
            e.printStackTrace();
        }

        try (FileWriter writer = new FileWriter(tempFile)) {
            for (User key : userList) {
                String roleData = key.getRole().equals(UserRole.ADMIN) ? "Админ" : "Пользователь";
                String regData = new SimpleDateFormat("dd.MM.yyyy в HH:mm").format(new Date(key.getTimestamp()));
                String hasSubscribedData = key.getUserData().getSubscription() == null ? "нет" : "да";

                String userData = "_________________________________________\n"
                        + "ID: " + key.getId() + "\n"
                        + "Роль: " + roleData + "\n"
                        + "Telegram CHAT-ID: " + key.getTelegramChatId() + "\n"
                        + "Telegram USER-ID: " + key.getTelegramUserId() + "\n"
                        + "Дата регистрации: " + regData + "\n\n"
                        + "Доступные запросы: " + key.getUserData().getAvailableRequests() + "\n"
                        + "Пригласил: " + key.getUserData().getInvited() + "\n\n"
                        + "Имеет подписку: " + hasSubscribedData + "\n";


                writer.write(userData);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        InputFile inputFile = new InputFile(tempFile);

        try {
            inputFile.validate();
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }

        SendDocument document = SendDocument.builder()
                .chatId(chatId)
                .document(inputFile)
                .build();

        try {
            execute(document);

            boolean isDeleted = tempFile.delete();
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }

        EditMessageText editMessage1 = EditMessageText.builder()
                .messageId(messageId)
                .chatId(chatId)
                .replyMarkup(inlineKeyboardMarkup)
                .text("✅ <b>Файл со всеми пользователями успешно отправлен</b>")
                .parseMode("html")
                .build();

        try {
            execute(editMessage1);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void handleChangeModel(User user, long chatId) {
        if (user.getUserData().getSubscription() == null) {
            handlePayMessage(chatId);
            return;
        }

        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);

        UserSessionDataLanguage language;

        if (userSessionData == null) {
            userSessionData = getDefUserSessionData();
            language = UserSessionDataLanguage.RU;
        } else {
            language = userSessionData.getLanguage();
        }

        String messageText = null;

        String chatGptThreeFiveButtonText = "\uD83D\uDC7D ChatGPT 3.5 Turbo";
        String chatGptFourButtonText = "\uD83E\uDD16 ChatGPT 4";
        String chatGptFourTurboButtonText = "\uD83D\uDC7E ChatGPT 4 Turbo";
        String chatGptFourVisionButtonText = "\uD83D\uDCF7 ChatGPT 4 Vision";

        switch (language) {
            case RU -> {
                messageText = "⚙\uFE0F <b>Выберите модель, с которой вы хотите ввести диалог.</b>\n"
                        + "<b>После выбора предыдущий диалог будет завершен:</b>";

                if (userSessionData.getModel().equals("gpt-3.5-turbo")) {
                    chatGptThreeFiveButtonText = chatGptThreeFiveButtonText + " [ВЫБРАНО]";
                } else if (userSessionData.getModel().equals("gpt-4")) {
                    chatGptFourButtonText = chatGptFourButtonText + " [ВЫБРАНО]";
                } else if (userSessionData.getModel().equals("gpt-4-1106-preview")) {
                    chatGptFourTurboButtonText = chatGptFourTurboButtonText + " [ВЫБРАНО]";
                } else if (userSessionData.getModel().equals("gpt-4-vision-preview")){
                    chatGptFourVisionButtonText = chatGptFourVisionButtonText + " [ВЫБРАНО]";
                }
            }
            case UA -> {
                messageText = "⚙\uFE0F <b>Виберіть модель, з якою ви бажаєте ввести діалог.</b>\n"
                        + "<b>Після вибору попередній діалог буде завершено:</b>";

                if (userSessionData.getModel().equals("gpt-3.5-turbo")) {
                    chatGptThreeFiveButtonText = chatGptThreeFiveButtonText + " [ВИБРАНО]";
                } else if (userSessionData.getModel().equals("gpt-4")) {
                    chatGptFourButtonText = chatGptFourButtonText + " [ВИБРАНО]";
                } else if (userSessionData.getModel().equals("gpt-4-1106-preview")) {
                    chatGptFourTurboButtonText = chatGptFourTurboButtonText + " [ВИБРАНО]";
                } else if (userSessionData.getModel().equals("gpt-4-vision-preview")){
                    chatGptFourVisionButtonText = chatGptFourVisionButtonText + " [ВИБРАНО]";
                }
            }
            case EN -> {
                messageText = "⚙\uFE0F <b>Select the model with which you want to enter a dialogue.</b>\n"
                        + "<b>Once selected, the previous dialog will be completed:</b>";

                if (userSessionData.getModel().equals("gpt-3.5-turbo")) {
                    chatGptThreeFiveButtonText = chatGptThreeFiveButtonText + " [CHOSEN]";
                } else if (userSessionData.getModel().equals("gpt-4")) {
                    chatGptFourButtonText = chatGptFourButtonText + " [CHOSEN]";
                } else if (userSessionData.getModel().equals("gpt-4-1106-preview")) {
                    chatGptFourTurboButtonText = chatGptFourTurboButtonText + " [CHOSEN]";
                } else if (userSessionData.getModel().equals("gpt-4-vision-preview")){
                    chatGptFourVisionButtonText = chatGptFourVisionButtonText + " [CHOSEN]";
                }
            }
        }

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton chatGptThreeFiveButton = InlineKeyboardButton.builder()
                .callbackData(ChangeModelCallback.CHANGE_MODEL_CHAT_GPT_THREE_FIVE_CALLBACK_DATA)
                .text(chatGptThreeFiveButtonText)
                .build();

        InlineKeyboardButton chatGptFourButton = InlineKeyboardButton.builder()
                .callbackData(ChangeModelCallback.CHANGE_MODEL_CHAT_GPT_FOUR_CALLBACK_DATA)
                .text(chatGptFourButtonText)
                .build();

        InlineKeyboardButton chatGptFourTurboButton = InlineKeyboardButton.builder()
                .callbackData(ChangeModelCallback.CHANGE_MODEL_CHAT_GPT_FOUR_TURBO_CALLBACK_DATA)
                .text(chatGptFourTurboButtonText)
                .build();

        InlineKeyboardButton chatGptFourVisionButton = InlineKeyboardButton.builder()
                .callbackData(ChangeModelCallback.CHANGE_MODEL_CHAT_GPT_FOUR_VISION_CALLBACK_DATA)
                .text(chatGptFourVisionButtonText)
                .build();

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow2 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow3 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow4 = new ArrayList<>();

        keyboardButtonsRow1.add(chatGptThreeFiveButton);
        keyboardButtonsRow2.add(chatGptFourButton);
        keyboardButtonsRow3.add(chatGptFourTurboButton);
        keyboardButtonsRow4.add(chatGptFourVisionButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);
        rowList.add(keyboardButtonsRow2);
        rowList.add(keyboardButtonsRow3);
        rowList.add(keyboardButtonsRow4);

        inlineKeyboardMarkup.setKeyboard(rowList);

        SendMessage message = SendMessage.builder()
                .text(messageText)
                .chatId(chatId)
                .replyMarkup(inlineKeyboardMarkup)
                .parseMode("html")
                .build();

        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }

    }

    private ReplyKeyboardMarkup getMainReplyKeyboardMarkup(UserSessionDataLanguage userSessionDataLanguage, User user) {

        String profileButtonText = null;
        String refreshButtonText = null;
        String roleButtonText = null;
        String cardButtonText = null;
        String swapModelButtonText = null;

        switch (userSessionDataLanguage) {
            case RU -> {
                profileButtonText = "\uD83D\uDC64 Профиль";
                refreshButtonText = "\uD83D\uDD04 Сбросить контекст";
                roleButtonText = "\uD83C\uDFAD Выбрать роль";
                cardButtonText = "\uD83D\uDCB3 Подписка";
                swapModelButtonText = "\uD83D\uDD04 Поменять модель";
            }
            case EN -> {
                profileButtonText = "\uD83D\uDC64 Profile";
                refreshButtonText = "\uD83D\uDD04 Drop chat context";
                roleButtonText = "\uD83C\uDFAD Choose a role";
                cardButtonText = "\uD83D\uDCB3 Subscription";
                swapModelButtonText = "\uD83D\uDD04 Change model";
            }
            case UA -> {
                profileButtonText = "\uD83D\uDC64 Профіль";
                refreshButtonText = "\uD83D\uDD04 Скинути контекст";
                roleButtonText = "\uD83C\uDFAD Вибрати роль";
                cardButtonText = "\uD83D\uDCB3 Пiдписка";
                swapModelButtonText = "\uD83D\uDD04 Змінити модель";
            }
        }
        ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
        keyboardMarkup.setSelective(true);
        keyboardMarkup.setResizeKeyboard(true);
        keyboardMarkup.setOneTimeKeyboard(false);

        KeyboardButton profileButton = new KeyboardButton(profileButtonText);
        KeyboardButton refreshButton = new KeyboardButton(refreshButtonText);
        KeyboardButton roleButton = new KeyboardButton(roleButtonText);
        KeyboardButton cardButton = new KeyboardButton(cardButtonText);

        KeyboardRow keyboardRow1 = new KeyboardRow();
        KeyboardRow keyboardRow2 = new KeyboardRow();

        keyboardRow1.add(profileButton);
        keyboardRow1.add(refreshButton);

        keyboardRow2.add(roleButton);
        keyboardRow2.add(cardButton);

        List<KeyboardRow> keyboardRows = new ArrayList<>();

        keyboardRows.add(keyboardRow1);
        keyboardRows.add(keyboardRow2);

        if (user.getUserData().getSubscription() != null) {
            KeyboardButton swapModelButton = new KeyboardButton(swapModelButtonText);

            KeyboardRow keyboardRow3 = new KeyboardRow();

            keyboardRow3.add(swapModelButton);

            keyboardRows.add(keyboardRow3);
        }
        if (user.getRole().equals(UserRole.ADMIN)) {
            KeyboardButton adminButton = new KeyboardButton("\uD83D\uDED1 Админ-панель");

            KeyboardRow keyboardRow4 = new KeyboardRow();

            keyboardRow4.add(adminButton);

            keyboardRows.add(keyboardRow4);
        }

        keyboardMarkup.setKeyboard(keyboardRows);

        return keyboardMarkup;
    }


    private UserSessionData getDefUserSessionData(){
        return UserSessionData.builder()
                .model("gpt-3.5-turbo")
                .role(UserSessionDataRole.CHAT_GPT)
                .messageHistory(new HashMap<>())
                .language(UserSessionDataLanguage.RU)
                .build();
    }
}
