package ru.marthastudios.chatgptbot.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.marthastudios.chatgptbot.entity.User;
import ru.marthastudios.chatgptbot.entity.UserData;
import ru.marthastudios.chatgptbot.enums.UserSessionDataLanguage;
import ru.marthastudios.chatgptbot.pojo.UserSessionData;
import ru.marthastudios.chatgptbot.property.BotProperty;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class TelegramBotService extends TelegramLongPollingBot {
    @Autowired
    private BotProperty botProperty;
    @Autowired
    private UserService userService;
    private static final Map<Long, UserSessionData> usersSessionsDataMap = new HashMap<>();
    private static final String READY_SUBSCRIBED_CALLBACK_DATA = "ready subscribed callback data";
    private static final String BUY_SUBSCRIPTION_CALLBACK_DATA = "buy subscription callback data";
    private static final String CHANGE_LANGUAGE_CALLBACK_DATA = "change language callback data";
    private static final String INVITE_FRIEND_CALLBACK_DATA = "invite friend callback data";
    private static final String BACK_PROFILE_MESSAGE_CALLBACK_DATA = "back profile message callback data";
    private static final String SELECT_RU_LANGUAGE_CALLBACK_DATA = "select ru language callback data";
    private static final String SELECT_EN_LANGUAGE_CALLBACK_DATA = "select en language callback data";
    private static final String SELECT_UA_LANGUAGE_CALLBACK_DATA = "select ua language callback data";

    public TelegramBotService(BotProperty botProperty){
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
            if (update.hasMessage() && update.getMessage().hasText()){
                long chatId = update.getMessage().getChatId();
                String text = update.getMessage().getText();

                GetChatMember getChatMember = new GetChatMember("-1002009167031", update.getMessage().getFrom().getId());

                ChatMember chatMember = null;

                try {
                    chatMember = execute(getChatMember);
                } catch (TelegramApiException ignored){
                }

                if(chatMember.getStatus().equals("left")){
                    sendUnsubscribedMessage(chatId);
                    return;
                }

                switch (text){
                    case "/start" -> {
                        handleStartMessage(chatId);
                        return;
                    }

                    case "/profile", "\uD83D\uDC64 Профиль", "\uD83D\uDC64 Profile", "\uD83D\uDC64 Профіль" -> {
                        User user = userService.getByTelegramUserId(update.getMessage().getFrom().getId());

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
                }


            } else if (update.hasCallbackQuery() && update.getCallbackQuery().getData() != null){
                long chatId = update.getCallbackQuery().getMessage().getChatId();
                int messageId = update.getCallbackQuery().getMessage().getMessageId();

                switch (update.getCallbackQuery().getData()){
                    case READY_SUBSCRIBED_CALLBACK_DATA -> {
                        GetChatMember getChatMember = new GetChatMember("-1002009167031", update.getCallbackQuery().getFrom().getId());

                        ChatMember chatMember = null;

                        try {
                            chatMember = execute(getChatMember);
                        } catch (TelegramApiException e){
                        }

                        if (chatMember.getStatus().equals("left")){
                            deleteMessage(messageId, chatId);
                            sendUnsubscribedMessage(chatId);
                        } else {
                            boolean existsByTelegramUserId = userService.existsByTelegramUserId(update.getCallbackQuery().getFrom().getId());

                            if (!existsByTelegramUserId){
                                User user = User.builder()
                                        .telegramUserId(update.getCallbackQuery().getFrom().getId())
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
                    case SELECT_RU_LANGUAGE_CALLBACK_DATA -> {
                        usersSessionsDataMap.put(chatId,
                                UserSessionData.builder()
                                .language(UserSessionDataLanguage.RU)
                                .build());

                        deleteMessage(messageId, chatId);
                        sendSuccessSubscribeMessage(chatId);
                    }
                    case SELECT_UA_LANGUAGE_CALLBACK_DATA -> {
                        usersSessionsDataMap.put(chatId, UserSessionData.builder()
                                .language(UserSessionDataLanguage.UA)
                                .build());

                        deleteMessage(messageId, chatId);
                        sendSuccessSubscribeMessage(chatId);
                    }
                    case SELECT_EN_LANGUAGE_CALLBACK_DATA -> {
                        usersSessionsDataMap.put(chatId, UserSessionData.builder()
                                .language(UserSessionDataLanguage.EN)
                                .build());

                        deleteMessage(messageId, chatId);
                        sendSuccessSubscribeMessage(chatId);
                    }
                    case BUY_SUBSCRIPTION_CALLBACK_DATA -> handlePayMessage(chatId);
                    case CHANGE_LANGUAGE_CALLBACK_DATA -> {
                        EditMessageText editMessage = EditMessageText.builder()
                                .chatId(chatId)
                                .messageId(messageId)
                                .text("Выберите язык / Виберіть мову / Select a language")
                                .build();

                        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

                        InlineKeyboardButton ruButton = InlineKeyboardButton.builder()
                                .callbackData(SELECT_RU_LANGUAGE_CALLBACK_DATA)
                                .text("\uD83C\uDDF7\uD83C\uDDFA RU")
                                .build();

                        InlineKeyboardButton uaButton = InlineKeyboardButton.builder()
                                .callbackData(SELECT_UA_LANGUAGE_CALLBACK_DATA)
                                .text("\uD83C\uDDFA\uD83C\uDDE6 UA")
                                .build();

                        InlineKeyboardButton enButton = InlineKeyboardButton.builder()
                                .callbackData(SELECT_EN_LANGUAGE_CALLBACK_DATA)
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
                        } catch (TelegramApiException e){
                            e.printStackTrace();
                        }
                    }
                    case BACK_PROFILE_MESSAGE_CALLBACK_DATA -> {
                        User user = userService.getByTelegramUserId(update.getCallbackQuery().getFrom().getId());

                        editProfileMessage(user, messageId, chatId);
                    }
                    case INVITE_FRIEND_CALLBACK_DATA -> {
                        User user = userService.getByTelegramUserId(update.getCallbackQuery().getFrom().getId());

                        editInviteFriendMessage(user, messageId, chatId, update.getCallbackQuery().getFrom().getId());
                    }
                }
            }
        }).start();
    }

    private void sendUnsubscribedMessage(long chatId){
        SendMessage sendMessage = new SendMessage();

        sendMessage.setChatId(chatId);
        sendMessage.setParseMode("html");

        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);
        UserSessionDataLanguage userSessionDataLanguage;

        if (userSessionData == null){
            userSessionDataLanguage = UserSessionDataLanguage.RU;
        } else {
            userSessionDataLanguage = userSessionData.getLanguage();
        }

        String messageText = null;
        String readyButtonText = null;

        switch (userSessionDataLanguage){
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
                .text("Geva IT be | Технологии, ИИ")
                .url("https://t.me/+q2dEUDcWVrZkZWU6")
                .build();

        InlineKeyboardButton readyButton = InlineKeyboardButton.builder()
                .text(readyButtonText)
                .callbackData(READY_SUBSCRIBED_CALLBACK_DATA)
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
        } catch (TelegramApiException e){
            e.printStackTrace();
        }
    }
    private void deleteMessage(int messageId, long chatId){
        DeleteMessage deleteMessage = DeleteMessage.builder()
                .messageId(messageId)
                .chatId(chatId)
                .build();

        try {
            execute(deleteMessage);
        } catch (TelegramApiException e){
            e.printStackTrace();
        }
    }

    private void editSuccessSubscribeMessage(int messageId, long chatId){
        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);
        UserSessionDataLanguage userSessionDataLanguage;

        if (userSessionData == null){
            userSessionDataLanguage = UserSessionDataLanguage.RU;
        } else {
            userSessionDataLanguage = userSessionData.getLanguage();
        }

        String messageText = null;

        switch (userSessionDataLanguage){
            case RU -> {
                messageText = "<b>Задай свой вопрос или отправьте мне голосовое сообщение.</b>\n\n<i>Больше информации о ChatGPT в разделе \"\uD83D\uDC64 Профиль\".</i>";
            }
            case EN -> {
                messageText = "<b>Ask your question or send me a voicemail.</b>\n\n<i>More information about ChatGPT in the \"\uD83D\uDC64 Profile\" section.</i>";
            }
            case UA -> {
                messageText = "<b>Постав своє запитання або надішліть мені голосове повідомлення.</b>\n\n<i>Більше інформації про ChatGPT у розділі \"\uD83D\uDC64 Профіль\".</i>";
            }
        }

        EditMessageText editMessage = EditMessageText.builder()
                .text(messageText)
                .chatId(chatId)
                .messageId(messageId)
                .parseMode("html")
                .build();


        try {
            execute(editMessage);
        } catch (TelegramApiException e){
            e.printStackTrace();
        }
    }

    private void sendSuccessSubscribeMessage(long chatId){
        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);
        UserSessionDataLanguage userSessionDataLanguage;

        if (userSessionData == null){
            userSessionDataLanguage = UserSessionDataLanguage.RU;
        } else {
            userSessionDataLanguage = userSessionData.getLanguage();
        }

        String messageText = null;

        String profileButtonText = null;
        String refreshButtonText = null;
        String roleButtonText = null;
        String cardButtonText = null;

        switch (userSessionDataLanguage){
            case RU -> {
                messageText = "<b>Задай свой вопрос или отправьте мне голосовое сообщение.</b>\n\n<i>Больше информации о ChatGPT в разделе \"\uD83D\uDC64 Профиль\".</i>";

                profileButtonText = "\uD83D\uDC64 Профиль";
                refreshButtonText = "\uD83D\uDD04 Сбросить контекст";
                roleButtonText = "\uD83C\uDFAD Выбрать роль";
                cardButtonText = "\uD83D\uDCB3 Подписка";
            }
            case EN -> {
                messageText = "<b>Ask your question or send me a voicemail.</b>\n\n<i>More information about ChatGPT in the \"\uD83D\uDC64 Profile\" section.</i>";

                profileButtonText = "\uD83D\uDC64 Profile";
                refreshButtonText = "\uD83D\uDD04 Reset context";
                roleButtonText = "\uD83C\uDFAD Choose a role";
                cardButtonText = "\uD83D\uDCB3 Subscription";
            }
            case UA -> {
                messageText = "<b>Постав своє запитання або надішліть мені голосове повідомлення.</b>\n\n<i>Більше інформації про ChatGPT у розділі \"\uD83D\uDC64 Профіль\".</i>";

                profileButtonText = "\uD83D\uDC64 Профіль";
                refreshButtonText = "\uD83D\uDD04 Скинути контекст";
                roleButtonText = "\uD83C\uDFAD Вибрати роль";
                cardButtonText = "\uD83D\uDCB3 Пiдписка";
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

        keyboardMarkup.setKeyboard(keyboardRows);

        SendMessage sendMessage = SendMessage.builder()
                .text(messageText)
                .chatId(chatId)
                .replyMarkup(keyboardMarkup)
                .parseMode("html")
                .build();

        try {
            execute(sendMessage);
        } catch (TelegramApiException e){
            e.printStackTrace();
        }
    }

    public void handleStartMessage(long chatId){
        InputStream videoStream = getClass().getClassLoader().getResourceAsStream("ezgif-3-55625e136a.gif");

        InputFile videoInputFile = new InputFile(videoStream, "ezgif-3-55625e136a.gif");

        SendVideo sendVideo = SendVideo.builder()
                .chatId(chatId)
                .video(videoInputFile)
                .caption("Выберите язык / Виберіть мову / Select a language")
                .build();

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton ruButton = InlineKeyboardButton.builder()
                .callbackData(SELECT_RU_LANGUAGE_CALLBACK_DATA)
                .text("\uD83C\uDDF7\uD83C\uDDFA RU")
                .build();

        InlineKeyboardButton uaButton = InlineKeyboardButton.builder()
                .callbackData(SELECT_UA_LANGUAGE_CALLBACK_DATA)
                .text("\uD83C\uDDFA\uD83C\uDDE6 UA")
                .build();

        InlineKeyboardButton enButton = InlineKeyboardButton.builder()
                .callbackData(SELECT_EN_LANGUAGE_CALLBACK_DATA)
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
        } catch (TelegramApiException e){
            e.printStackTrace();
        }
    }

    public void handleProfileMessage(User user, long chatId){
        int availableRequests = user.getUserData().getAvailableRequests();

        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);
        UserSessionDataLanguage userSessionDataLanguage;

        if (userSessionData == null){
            userSessionDataLanguage = UserSessionDataLanguage.RU;
        } else {
            userSessionDataLanguage = userSessionData.getLanguage();
        }

        String messageText = null;

        String buySubscriptionButtonText = null;
        String inviteFriendButtonText = null;
        String changeLanguageButtonText = null;

        switch (userSessionDataLanguage){
            case RU -> {
                messageText = "\uD83D\uDCAC <b>Доступные запросы для ChatGPT: " + availableRequests + "</b>\n\n"
                        + "<b>Зачем нужны запросы ChatGPT?</b>\n\n"
                        + "Задавая вопрос, вы тратите 1 запрос (вопрос = запрос).\n" +
                        "Вы можете использовать 5 бесплатных запросов каждый день. Запросы восстанавливаются в 00:00 (GMT+3).\n\n"
                        + "<b>Недостаточно запросов ChatGPT?</b>\n\n"
                        + "▫\uFE0F Вы можете приобрести подписку ChatGPT и не беспокоиться о лимитах;\n" +
                        "▫\uFE0F Пригласите друга и получите за него 5 запросов ChatGPT.\n\n"
                        + "<b><i>Как правильно общаться с ChatGPT –</i></b> https://telegra.ph/Gajd-Kak-sostavit-horoshij-zapros-v-ChatGPT-s-primerami-07-16";

                buySubscriptionButtonText = "\uD83D\uDE80 Купить подписку";
                inviteFriendButtonText = "\uD83D\uDC65 Пригласить друга";
                changeLanguageButtonText = "\uD83C\uDF0D Сменить язык бота";
            }

            case UA -> {
                messageText = "\uD83D\uDCAC <b>Доступні запити для ChatGPT: " + availableRequests + "</b>\n\n"
                        + "<b>Навіщо потрібні запити ChatGPT?</b>\n\n"
                        + "Задаючи питання, ви витрачаєте 1 Запит (питання = запит).\n" +
                        "Ви можете використовувати 5 безкоштовних запитів щодня. Запити відновлюються о 06: 00 (GMT+3).\n\n"
                        + "<b>Недостатньо запитів ChatGPT?</b>\n\n"
                        + "▫\uFE0F Ви можете придбати підписку ChatGPT і не турбуватися про ліміти;\n" +
                        "▫\uFE0F Запросіть друга і отримайте за нього 5 запитів ChatGPT.\n\n"
                        + "<b><i>Як правильно спілкуватися з ChatGPT –</i></b> https://telegra.ph/Gajd-Kak-sostavit-horoshij-zapros-v-ChatGPT-s-primerami-07-16";

                buySubscriptionButtonText = "\uD83D\uDE80 Купити пiдписку";
                inviteFriendButtonText = "\uD83D\uDC65 Запросiть друга";
                changeLanguageButtonText = "\uD83C\uDF0D Змiнити мову";
            }

            case EN -> {
                messageText = "\uD83D\uDCAC <b>Available requests for ChatGPT: " + availableRequests + "</b>\n\n"
                        + "<b>Why do I need ChatGPT requests?</b>\n\n"
                        + "By asking a question, you spend 1 query (question = query).\n" +
                        "You can use 5 free requests every day. Requests are restored at 00:00 (GMT+3).\n\n"
                        + "<b>Not enough ChatGPT requests?</b>\n\n"
                        + "▫\uFE0F You can purchase a ChatGPT subscription and not worry about limits;\n" +
                        "▫\uFE0F Invite a friend and get 5 ChatGPT requests for him.\n\n"
                        + "<b><i>How to properly communicate with ChatGPT –</i></b> https://telegra.ph/Gajd-Kak-sostavit-horoshij-zapros-v-ChatGPT-s-primerami-07-16";

                buySubscriptionButtonText = "\uD83D\uDE80 Buy a subscription";
                inviteFriendButtonText = "\uD83D\uDC65 Invite a friend";
                changeLanguageButtonText = "\uD83C\uDF0D Change language";
            }
        }

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton buySubscriptionButton = InlineKeyboardButton.builder()
                .callbackData(BUY_SUBSCRIPTION_CALLBACK_DATA)
                .text(buySubscriptionButtonText)
                .build();

        InlineKeyboardButton inviteFriendButton = InlineKeyboardButton.builder()
                .callbackData(INVITE_FRIEND_CALLBACK_DATA)
                .text(inviteFriendButtonText)
                .build();

        InlineKeyboardButton changeLanguageButton = InlineKeyboardButton.builder()
                .callbackData(CHANGE_LANGUAGE_CALLBACK_DATA)
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
        } catch (TelegramApiException e){
            e.printStackTrace();
        }
    }

    public void editProfileMessage(User user, int messageId, long chatId){
        int availableRequests = user.getUserData().getAvailableRequests();

        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);
        UserSessionDataLanguage userSessionDataLanguage;

        if (userSessionData == null){
            userSessionDataLanguage = UserSessionDataLanguage.RU;
        } else {
            userSessionDataLanguage = userSessionData.getLanguage();
        }

        String messageText = null;

        String buySubscriptionButtonText = null;
        String inviteFriendButtonText = null;
        String changeLanguageButtonText = null;

        switch (userSessionDataLanguage){
            case RU -> {
                messageText = "\uD83D\uDCAC <b>Доступные запросы для ChatGPT: " + availableRequests + "</b>\n\n"
                        + "<b>Зачем нужны запросы ChatGPT?</b>\n\n"
                        + "Задавая вопрос, вы тратите 1 запрос (вопрос = запрос).\n" +
                        "Вы можете использовать 5 бесплатных запросов каждый день. Запросы восстанавливаются в 00:00 (GMT+3).\n\n"
                        + "<b>Недостаточно запросов ChatGPT?</b>\n\n"
                        + "▫\uFE0F Вы можете приобрести подписку ChatGPT и не беспокоиться о лимитах;\n" +
                        "▫\uFE0F Пригласите друга и получите за него 5 запросов ChatGPT.\n\n"
                        + "<b><i>Как правильно общаться с ChatGPT –</i></b> https://telegra.ph/Gajd-Kak-sostavit-horoshij-zapros-v-ChatGPT-s-primerami-07-16";

                buySubscriptionButtonText = "\uD83D\uDE80 Купить подписку";
                inviteFriendButtonText = "\uD83D\uDC65 Пригласить друга";
                changeLanguageButtonText = "\uD83C\uDF0D Сменить язык бота";
            }

            case UA -> {
                messageText = "\uD83D\uDCAC <b>Доступні запити для ChatGPT: " + availableRequests + "</b>\n\n"
                        + "<b>Навіщо потрібні запити ChatGPT?</b>\n\n"
                        + "Задаючи питання, ви витрачаєте 1 Запит (питання = запит).\n" +
                        "Ви можете використовувати 5 безкоштовних запитів щодня. Запити відновлюються о 06: 00 (GMT+3).\n\n"
                        + "<b>Недостатньо запитів ChatGPT?</b>\n\n"
                        + "▫\uFE0F Ви можете придбати підписку ChatGPT і не турбуватися про ліміти;\n" +
                        "▫\uFE0F Запросіть друга і отримайте за нього 5 запитів ChatGPT.\n\n"
                        + "<b><i>Як правильно спілкуватися з ChatGPT –</i></b> https://telegra.ph/Gajd-Kak-sostavit-horoshij-zapros-v-ChatGPT-s-primerami-07-16";

                buySubscriptionButtonText = "\uD83D\uDE80 Купити пiдписку";
                inviteFriendButtonText = "\uD83D\uDC65 Запросiть друга";
                changeLanguageButtonText = "\uD83C\uDF0D Змiнити мову";
            }

            case EN -> {
                messageText = "\uD83D\uDCAC <b>Available requests for ChatGPT: " + availableRequests + "</b>\n\n"
                        + "<b>Why do I need ChatGPT requests?</b>\n\n"
                        + "By asking a question, you spend 1 query (question = query).\n" +
                        "You can use 5 free requests every day. Requests are restored at 00:00 (GMT+3).\n\n"
                        + "<b>Not enough ChatGPT requests?</b>\n\n"
                        + "▫\uFE0F You can purchase a ChatGPT subscription and not worry about limits;\n" +
                        "▫\uFE0F Invite a friend and get 5 ChatGPT requests for him.\n\n"
                        + "<b><i>How to properly communicate with ChatGPT –</i></b> https://telegra.ph/Gajd-Kak-sostavit-horoshij-zapros-v-ChatGPT-s-primerami-07-16";

                buySubscriptionButtonText = "\uD83D\uDE80 Buy a subscription";
                inviteFriendButtonText = "\uD83D\uDC65 Invite a friend";
                changeLanguageButtonText = "\uD83C\uDF0D Change language";
            }
        }

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton buySubscriptionButton = InlineKeyboardButton.builder()
                .callbackData(BUY_SUBSCRIPTION_CALLBACK_DATA)
                .text(buySubscriptionButtonText)
                .build();

        InlineKeyboardButton inviteFriendButton = InlineKeyboardButton.builder()
                .callbackData(INVITE_FRIEND_CALLBACK_DATA)
                .text(inviteFriendButtonText)
                .build();

        InlineKeyboardButton changeLanguageButton = InlineKeyboardButton.builder()
                .callbackData(CHANGE_LANGUAGE_CALLBACK_DATA)
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
        } catch (TelegramApiException e){
            e.printStackTrace();
        }
    }

    private void editInviteFriendMessage(User user, int messageId, long chatId, long userId){
        int invited = user.getUserData().getInvited();

        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);
        UserSessionDataLanguage userSessionDataLanguage;

        if (userSessionData == null){
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

                backButtonText = "Назад \uD83D\uDD19";
            }
            case UA -> {
                messageText = "\uD83E\uDD1D <b>Заробляйте запити за допомогою нашої реферальної системи</b>\n\n"
                        + "З кожного запрошеного Користувача Ви отримуєте: 5 запитів ChatGPT\n\n"
                        + "Запрошено за весь час: " + invited + " \n\n"
                        + "<code>https://t.me/gevagpt_bot?start=" + userId + "</code>";

                backButtonText = "Заднiй \uD83D\uDD19";
            }
            case EN -> {
                messageText = "\uD83E\uDD1D <b>Earn requests with our referral system</b>\n\n"
                        + "From each invited user you receive: 5 ChatGPT requests\n\n"
                        + "Invited for all time: " + invited + " \n\n"
                        + "<code>https://t.me/gevagpt_bot?start=" + userId + "</code>";

                backButtonText = "Back \uD83D\uDD19";
            }
        }

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton backProfileButton = InlineKeyboardButton.builder()
                .callbackData(BACK_PROFILE_MESSAGE_CALLBACK_DATA)
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
        } catch (TelegramApiException e){
            e.printStackTrace();
        }
    }

    private void handlePayMessage(long chatId){
        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);
        UserSessionDataLanguage userSessionDataLanguage;

        if (userSessionData == null){
            userSessionDataLanguage = UserSessionDataLanguage.RU;
        } else {
            userSessionDataLanguage = userSessionData.getLanguage();
        }

        String messageText = null;

        String sevenDaysButtonText = null;
        String thirtyButtonText = null;
        String ninetyButtonText = null;

        switch (userSessionDataLanguage){
            case RU -> {
                messageText = "<b>Что дает подписка:</b>\n\n"
                        + "▫\uFE0F Отсутствие рекламы;\n"
                        + "▫\uFE0F GPT-3.5 — безлимитное количество запросов;\n"
                        + "▫\uFE0F Приоритетная обработка запросов;\n"
                        + "▫\uFE0F Доступ к новым версиям СhatGPT.";

                sevenDaysButtonText = "\uD83D\uDCC5 Безлимит на 7 дней - 199 ₽";
                thirtyButtonText = "\uD83D\uDCC5 Безлимит на 30 дней - 449 ₽";
                ninetyButtonText = "\uD83D\uDCC5 Безлимит на 90 дней - 1199 ₽";
            }

            case UA -> {
                messageText = "<b>Що дає підписка:</b>\n\n"
                        + "▫\uFE0F Відсутність реклами;\n"
                        + "▫\uFE0F GPT-3.5-безлімітна кількість запитів;\n"
                        + "▫\uFE0F Пріоритетна обробка запитів;\n"
                        + "▫\uFE0F Доступ до нових версій СhatGPT.";

                sevenDaysButtonText = "\uD83D\uDCC5 Безлiмiт на 7 днiв - 7 $";
                thirtyButtonText = "\uD83D\uDCC5 Безлiмiт на 30 днiв - 20 $";
                ninetyButtonText = "\uD83D\uDCC5 Безлiмiт на 90 днiв - 40 $";
            }

            case EN -> {
                messageText = "<b>What does a subscription give:</b>\n\n"
                        + "▫\uFE0F No advertising;\n"
                        + "▫\uFE0F GPT-3.5 — unlimited number of requests;\n"
                        + "▫\uFE0F Priority processing of requests;\n"
                        + "▫\uFE0F Access to new versions of ChatGPT.";

                sevenDaysButtonText = "\uD83D\uDCC5 Unlimited for 7 days - 7 $";
                thirtyButtonText = "\uD83D\uDCC5 Unlimited for 30 days - 20 $";
                ninetyButtonText = "\uD83D\uDCC5 Unlimited for 90 days - 40 $";
            }
        }

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup();

        InlineKeyboardButton sevenButton = InlineKeyboardButton.builder()
                .callbackData("dd")
                .text(sevenDaysButtonText)
                .build();

        //todo callbacks
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
        } catch (TelegramApiException e){
            e.printStackTrace();
        }
    }
    private void handleSupportMessage(long userId, long chatId){
        UserSessionData userSessionData = usersSessionsDataMap.get(chatId);
        UserSessionDataLanguage userSessionDataLanguage;

        if (userSessionData == null){
            userSessionDataLanguage = UserSessionDataLanguage.RU;
        } else {
            userSessionDataLanguage = userSessionData.getLanguage();
        }

        String messageText = null;

        String techSupportButtonText = null;
        String adSupportButtonText = null;

        switch (userSessionDataLanguage){
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
        } catch (TelegramApiException e){
            e.printStackTrace();
        }
    }
}
