package ru.marthastudios.chatgptbot.service;

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
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.Voice;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.marthastudios.chatgptbot.api.OpenaiApi;
import ru.marthastudios.chatgptbot.callback.*;
import ru.marthastudios.chatgptbot.dto.openai.ChatRequestDto;
import ru.marthastudios.chatgptbot.dto.openai.ChatResponseDto;
import ru.marthastudios.chatgptbot.entity.Subscription;
import ru.marthastudios.chatgptbot.entity.User;
import ru.marthastudios.chatgptbot.entity.UserData;
import ru.marthastudios.chatgptbot.enums.UserRole;
import ru.marthastudios.chatgptbot.enums.UserSessionDataLanguage;
import ru.marthastudios.chatgptbot.enums.UserSessionDataRole;
import ru.marthastudios.chatgptbot.pojo.CustomPair;
import ru.marthastudios.chatgptbot.pojo.UserSessionData;
import ru.marthastudios.chatgptbot.property.BotProperty;
import ru.marthastudios.chatgptbot.util.TelegramTextFormatterUtil;
import ru.marthastudios.chatgptbot.util.UrlFileDownloader;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.*;

@Service
public class TelegramBotService extends TelegramLongPollingBot {
    @Autowired
    private BotProperty botProperty;
    @Autowired
    private UserService userService;
    @Autowired
    private ReferralService referralService;
    @Autowired
    private SubscriptionService subscriptionService;
    @Autowired
    private OpenaiApi openaiApi;
    public static final Map<Long, UserSessionData> usersSessionsDataMap = new HashMap<>();
    public static Map<Long, Long> usersCooldownsMap = new HashMap<>();
    private static final Map<Long, CustomPair<Integer, Long>> giveSubscriptionSteps = new HashMap<>();
    private static final Set<Long> takeSubscriptionSteps = new HashSet<>();
    private static final Set<Long> banUserSteps = new HashSet<>();
    private static final Set<Long> unbanUserSteps = new HashSet<>();
    private static final Set<Long> giveAdminSteps = new HashSet<>();
    private static final Set<Long> takeAdminSteps = new HashSet<>();


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
                                    .callbackData(BackCallback.BACK_ADMIN_GIVE_SUBSCRIBE_CALLBACK_DATA)
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
                                case EN -> getSubMessageText = "✅ <b>You have received a subscription from the Administrator to</b> " + text + " <b>days</b>";
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
                createChatCompletion(userService.getByTelegramChatId(chatId), chatId, text);


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
                    tempFile = UrlFileDownloader.downloadFile(URL);
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

                createChatCompletion(userService.getByTelegramChatId(chatId), chatId, transcriptionText);

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
                    case AdminCallback.GIVE_SUBSCRIBE_CALLBACK_DATA -> {
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
                                .callbackData(BackCallback.BACK_ADMIN_GIVE_SUBSCRIBE_CALLBACK_DATA)
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
                    case AdminCallback.TAKE_SUBSCRIBE_CALLBACK_DATA -> {
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
                                .callbackData(BackCallback.BACK_ADMIN_TAKE_SUBSCRIBE_CALLBACK_DATA)
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

                    case BackCallback.BACK_ADMIN_GIVE_SUBSCRIBE_CALLBACK_DATA -> {
                        User user = userService.getByTelegramChatId(chatId);

                        giveSubscriptionSteps.remove(chatId);

                        editAdminMessage(user, chatId, messageId);
                    }
                    case BackCallback.BACK_ADMIN_TAKE_SUBSCRIBE_CALLBACK_DATA -> {
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
                    case BackCallback.BACK_ADMIN_GIVE_ADMIN_CALLBACK_DATA-> {
                        User user = userService.getByTelegramChatId(chatId);

                        giveAdminSteps.remove(chatId);

                        editAdminMessage(user, chatId, messageId);
                    }
                    case BackCallback.BACK_ADMIN_TAKE_ADMIN_CALLBACK_DATA-> {
                        User user = userService.getByTelegramChatId(chatId);

                        takeAdminSteps.remove(chatId);

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
                    case PaymentCallback.PAYMENT_THIRTY_CALLBACK_DATA -> {

                    }
                    case PaymentCallback.PAYMENT_SEVEN_CALLBACK_DATA -> {

                    }
                    case PaymentCallback.PAYMENT_NINETY_CALLBACK_DATA -> {

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
        InputStream videoStream = getClass().getClassLoader().getResourceAsStream("ezgif-3-55625e136a.gif");

        InputFile videoInputFile = new InputFile(videoStream, "ezgif-3-55625e136a.gif");

        SendVideo sendVideo = SendVideo.builder()
                .chatId(chatId)
                .video(videoInputFile)
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

        sendVideo.setReplyMarkup(inlineKeyboardMarkup);

        try {
            execute(sendVideo);
            videoStream.close();
        } catch (TelegramApiException | IOException e) {
            e.printStackTrace();
        }


    }

    public void handleProfileMessage(User user, long chatId) {
        int availableRequests = user.getUserData().getAvailableRequests();
        boolean hasSubscribe = user.getUserData().getSubscription() != null;
        String subscribeDateString = null;

        if (hasSubscribe) {
            Date subscribeDate = new Date(user.getUserData().getSubscription().getExpiration());

            SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy");

            subscribeDateString = dateFormat.format(subscribeDate);
        }

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
                        "\uD83D\uDCAC <b>Доступные запросы для ChatGPT: ∞ (Подписка истекает " + subscribeDateString + ")</b>\n\n"
                        :
                        "\uD83D\uDCAC <b>Доступные запросы для ChatGPT: " + availableRequests + "</b>\n\n";

                messageText = preMessage
                        + "<b>Зачем нужны запросы ChatGPT?</b>\n\n"
                        + "Задавая вопрос, вы тратите 1 запрос (вопрос = запрос).\n" +
                        "Вы можете использовать 5 бесплатных запросов каждый день. Запросы восстанавливаются в 00:00 (GMT+3).\n\n"
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
                        "\uD83D\uDCAC <b>Доступні запити для ChatGPT: ∞ (Підписка закінчується " + subscribeDateString + ")</b>\n\n"
                        :
                        "\uD83D\uDCAC <b>Доступні запити для ChatGPT: " + availableRequests + "</b>\n\n";

                messageText = preMessage
                        + "<b>Навіщо потрібні запити ChatGPT?</b>\n\n"
                        + "Задаючи питання, ви витрачаєте 1 Запит (питання = запит).\n" +
                        "Ви можете використовувати 5 безкоштовних запитів щодня. Запити відновлюються о 06: 00 (GMT+3).\n\n"
                        + "<b>Недостатньо запитів ChatGPT?</b>\n\n"
                        + "▫\uFE0F Ви можете придбати підписку ChatGPT і не турбуватися про ліміти;\n" +
                        "▫\uFE0F Запросіть друга і отримайте за нього 5 запитів ChatGPT.\n\n"
                        + "<b><i>Як правильно спілкуватися з ChatGPT –</i></b> https://telegra.ph/Gajd-GevaGPT-Kak-sostavit-horoshij-zapros-v-ChatGPT-11-27";

                buySubscriptionButtonText = "\uD83D\uDE80 Купити пiдписку";
                inviteFriendButtonText = "\uD83D\uDC65 Запросiть друга";
                changeLanguageButtonText = "\uD83C\uDF0D Змiнити мову";
            }

            case EN -> {
                String preMessage = hasSubscribe ?
                        "\uD83D\uDCAC <b>Available requests for ChatGPT: ∞ (Subscription expires " + subscribeDateString + ")</b>\n\n"
                        :
                        "\uD83D\uDCAC <b>Available requests for ChatGPT: " + availableRequests + "</b>\n\n";

                messageText = preMessage
                        + "<b>Why do I need ChatGPT requests?</b>\n\n"
                        + "By asking a question, you spend 1 query (question = query).\n" +
                        "You can use 5 free requests every day. Requests are restored at 00:00 (GMT+3).\n\n"
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
                .disableWebPagePreview(false)
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

        if (hasSubscribe) {
            Date subscribeDate = new Date(user.getUserData().getSubscription().getExpiration());

            SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy");

            subscribeDateString = dateFormat.format(subscribeDate);
        }

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
                        "\uD83D\uDCAC <b>Доступные запросы для ChatGPT: ∞ (Подписка истекает " + subscribeDateString + ")</b>\n\n"
                        :
                        "\uD83D\uDCAC <b>Доступные запросы для ChatGPT: " + availableRequests + "</b>\n\n";

                messageText = preMessage
                        + "<b>Зачем нужны запросы ChatGPT?</b>\n\n"
                        + "Задавая вопрос, вы тратите 1 запрос (вопрос = запрос).\n" +
                        "Вы можете использовать 5 бесплатных запросов каждый день. Запросы восстанавливаются в 00:00 (GMT+3).\n\n"
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
                        "\uD83D\uDCAC <b>Доступні запити для ChatGPT: ∞ (Підписка закінчується " + subscribeDateString + ")</b>\n\n"
                        :
                        "\uD83D\uDCAC <b>Доступні запити для ChatGPT: " + availableRequests + "</b>\n\n";

                messageText = preMessage
                        + "<b>Навіщо потрібні запити ChatGPT?</b>\n\n"
                        + "Задаючи питання, ви витрачаєте 1 Запит (питання = запит).\n" +
                        "Ви можете використовувати 5 безкоштовних запитів щодня. Запити відновлюються о 06: 00 (GMT+3).\n\n"
                        + "<b>Недостатньо запитів ChatGPT?</b>\n\n"
                        + "▫\uFE0F Ви можете придбати підписку ChatGPT і не турбуватися про ліміти;\n" +
                        "▫\uFE0F Запросіть друга і отримайте за нього 5 запитів ChatGPT.\n\n"
                        + "<b><i>Як правильно спілкуватися з ChatGPT –</i></b> https://telegra.ph/Gajd-GevaGPT-Kak-sostavit-horoshij-zapros-v-ChatGPT-11-27";

                buySubscriptionButtonText = "\uD83D\uDE80 Купити пiдписку";
                inviteFriendButtonText = "\uD83D\uDC65 Запросiть друга";
                changeLanguageButtonText = "\uD83C\uDF0D Змiнити мову";
            }

            case EN -> {
                String preMessage = hasSubscribe ?
                        "\uD83D\uDCAC <b>Available requests for ChatGPT: ∞ (Subscription expires " + subscribeDateString + ")</b>\n\n"
                        :
                        "\uD83D\uDCAC <b>Available requests for ChatGPT: " + availableRequests + "</b>\n\n";

                messageText = preMessage
                        + "<b>Why do I need ChatGPT requests?</b>\n\n"
                        + "By asking a question, you spend 1 query (question = query).\n" +
                        "You can use 5 free requests every day. Requests are restored at 00:00 (GMT+3).\n\n"
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
                .disableWebPagePreview(false)
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

        InputStream photoStream = getClass().getClassLoader().getResourceAsStream("img.png");

        InputFile photoInputFile = new InputFile(photoStream, "img.png");

        SendPhoto sendPhoto = SendPhoto.builder()
                .chatId(chatId)
                .caption(messageText)
                .replyMarkup(inlineKeyboardMarkup)
                .photo(photoInputFile)
                .parseMode("html")
                .build();

        try {
            execute(sendPhoto);
            photoStream.close();
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
                .url("https://t.me/blockhaincom")
                .build();

        InlineKeyboardButton adSupportButton = InlineKeyboardButton.builder()
                .text(adSupportButtonText)
                .url("https://t.me/blockhaincom")
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

    public void createChatCompletion(User user, long chatId, String text) {
        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);

        String userSessionDataRoleText = null;

        if (userSessionData == null) {
            userSessionData = getDefUserSessionData();
        }

        String typingText = null;
        String insufficientBalanceImgPath = null;

        String sevenDaysButtonText = null;
        String thirtyButtonText = null;
        String ninetyButtonText = null;

        switch (userSessionData.getLanguage()) {
            case RU -> {
                insufficientBalanceImgPath = "ruinsuff.jpg";
                typingText = "Печатает...";

                sevenDaysButtonText = "\uD83D\uDCC5 Безлимит на 7 дней - 199 ₽";
                thirtyButtonText = "\uD83D\uDCC5 Безлимит на 30 дней - 449 ₽";
                ninetyButtonText = "\uD83D\uDCC5 Безлимит на 90 дней - 1199 ₽";

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
                insufficientBalanceImgPath = "eninsuff.jpg";
                typingText = "Typing...";

                sevenDaysButtonText = "\uD83D\uDCC5 Unlimited for 7 days - 7 $";
                thirtyButtonText = "\uD83D\uDCC5 Unlimited for 30 days - 20 $";
                ninetyButtonText = "\uD83D\uDCC5 Unlimited for 90 days - 40 $";

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
                insufficientBalanceImgPath = "uainsuff.jpg";
                typingText = "Друкує...";

                sevenDaysButtonText = "\uD83D\uDCC5 Безлiмiт на 7 днiв - 7 $";
                thirtyButtonText = "\uD83D\uDCC5 Безлiмiт на 30 днiв - 20 $";
                ninetyButtonText = "\uD83D\uDCC5 Безлiмiт на 90 днiв - 40 $";

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
            InputStream photoStream = getClass().getClassLoader().getResourceAsStream(insufficientBalanceImgPath);

            InputFile photoInputFile = new InputFile(photoStream, insufficientBalanceImgPath);

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

            SendPhoto sendPhoto = SendPhoto.builder()
                    .photo(photoInputFile)
                    .chatId(chatId)
                    .replyMarkup(inlineKeyboardMarkup)
                    .build();

            try {
                execute(sendPhoto);
                photoStream.close();
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

        int lastKey = 0;

        for (Map.Entry<Integer, ChatRequestDto.Message> key : messageHistory.entrySet()) {
            if (key.getKey() > lastKey) {
                lastKey = key.getKey();
            }
        }

        lastKey += 1;

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

        if (messageHistory.size() > 15) {
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

        if (user.getUserData().getSubscription() == null) {
            user.getUserData().setAvailableRequests(user.getUserData().getAvailableRequests() - 1);
        }

        userService.create(user);

        System.out.println(chatResponseDtoResponseEntity.getChoices()[0].getMessage().getContent());
    }

    private void handleResetMessage(long chatId) {
        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);

        if (userSessionData == null) {
            userSessionData = UserSessionData.builder()
                    .language(UserSessionDataLanguage.RU)
                    .role(UserSessionDataRole.CHAT_GPT).build();
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
                .callbackData("sdfsd")
                .text("\uD83D\uDCB0 Топ депозитов")
                .build();

        InlineKeyboardButton giveSubscribeButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.GIVE_SUBSCRIBE_CALLBACK_DATA)
                .text("➕ Выдать подписку")
                .build();

        InlineKeyboardButton takeSubscribeButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.TAKE_SUBSCRIBE_CALLBACK_DATA)
                .text("➖ Забрать подписку")
                .build();

        InlineKeyboardButton giveAdminButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.GIVE_ADMIN_CALLBACK_DATA)
                .text("➕ Выдать админку")
                .build();

        InlineKeyboardButton takeAdminButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.TAKE_ADMIN_CALLBACK_DATA)
                .text("➖ Забрать админку")
                .build();

        InlineKeyboardButton banButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.BAN_USER_CALLBACK_DATA)
                .text("\uD83D\uDD12 Заблокировать пользователя")
                .build();

        InlineKeyboardButton unbanButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.UNBAN_USER_CALLBACK_DATA)
                .text("\uD83D\uDD13 Разблокировать пользователя")
                .build();


        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow2 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow3 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow4 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow5 = new ArrayList<>();

        keyboardButtonsRow1.add(statButton);

        keyboardButtonsRow2.add(allUsersButton);
        keyboardButtonsRow2.add(topDepositsButton);

        keyboardButtonsRow3.add(giveSubscribeButton);
        keyboardButtonsRow3.add(takeSubscribeButton);

        keyboardButtonsRow4.add(giveAdminButton);
        keyboardButtonsRow4.add(takeAdminButton);

        keyboardButtonsRow5.add(banButton);
        keyboardButtonsRow5.add(unbanButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);
        rowList.add(keyboardButtonsRow2);
        rowList.add(keyboardButtonsRow3);
        rowList.add(keyboardButtonsRow4);
        rowList.add(keyboardButtonsRow5);

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

        //todo
        InlineKeyboardButton topDepositsButton = InlineKeyboardButton.builder()
                .callbackData("sdfsd")
                .text("\uD83D\uDCB0 Топ депозитов")
                .build();

        InlineKeyboardButton giveSubscribeButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.GIVE_SUBSCRIBE_CALLBACK_DATA)
                .text("➕ Выдать подписку")
                .build();

        InlineKeyboardButton takeSubscribeButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.TAKE_SUBSCRIBE_CALLBACK_DATA)
                .text("➖ Забрать подписку")
                .build();

        InlineKeyboardButton giveAdminButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.GIVE_ADMIN_CALLBACK_DATA)
                .text("➕ Выдать админку")
                .build();

        InlineKeyboardButton takeAdminButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.TAKE_ADMIN_CALLBACK_DATA)
                .text("➖ Забрать админку")
                .build();

        InlineKeyboardButton banButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.BAN_USER_CALLBACK_DATA)
                .text("\uD83D\uDD12 Заблокировать пользователя")
                .build();

        InlineKeyboardButton unbanButton = InlineKeyboardButton.builder()
                .callbackData(AdminCallback.UNBAN_USER_CALLBACK_DATA)
                .text("\uD83D\uDD13 Разблокировать пользователя")
                .build();


        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow2 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow3 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow4 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow5 = new ArrayList<>();

        keyboardButtonsRow1.add(statButton);

        keyboardButtonsRow2.add(allUsersButton);
        keyboardButtonsRow2.add(topDepositsButton);

        keyboardButtonsRow3.add(giveSubscribeButton);
        keyboardButtonsRow3.add(takeSubscribeButton);

        keyboardButtonsRow4.add(giveAdminButton);
        keyboardButtonsRow4.add(takeAdminButton);

        keyboardButtonsRow5.add(banButton);
        keyboardButtonsRow5.add(unbanButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);
        rowList.add(keyboardButtonsRow2);
        rowList.add(keyboardButtonsRow3);
        rowList.add(keyboardButtonsRow4);
        rowList.add(keyboardButtonsRow5);

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
        long usersCount = userService.getCount();
        long subscribesCount = subscriptionService.getCount();

        String editMessageText = "\uD83D\uDCC8 <b>Статистика использования бота</b>\n\n"
                + "\uD83D\uDC65 <b>Всего пользователей:</b> " + usersCount + " шт.\n"
                + "\uD83D\uDCB3 <b>Всего подписок:</b> " + subscribesCount + " шт.\n\n"
                + "\uD83D\uDCB0 <b>Выручка за все время:</b> 0 ₽\n"
                + "\uD83D\uDCB0 <b>Выручка за месяц:</b> 0 ₽\n"
                + "\uD83D\uDCB0 <b>Выручка за 24 часа:</b> 0 ₽";

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
                .text(editMessageText)
                .parseMode("html")
                .build();

        try {
            execute(editMessage);
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

        List<InlineKeyboardButton> keyboardButtonsRow1 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow2 = new ArrayList<>();
        List<InlineKeyboardButton> keyboardButtonsRow3 = new ArrayList<>();

        keyboardButtonsRow1.add(chatGptThreeFiveButton);
        keyboardButtonsRow2.add(chatGptFourButton);
        keyboardButtonsRow3.add(chatGptFourTurboButton);

        List<List<InlineKeyboardButton>> rowList = new ArrayList<>();

        rowList.add(keyboardButtonsRow1);
        rowList.add(keyboardButtonsRow2);
        rowList.add(keyboardButtonsRow3);

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
