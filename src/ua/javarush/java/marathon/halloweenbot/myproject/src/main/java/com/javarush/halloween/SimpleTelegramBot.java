package com.javarush.halloween;

import io.github.cdimascio.dotenv.Dotenv;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.commands.DeleteMyCommands;
import org.telegram.telegrambots.meta.api.methods.commands.GetMyCommands;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.menubutton.SetChatMenuButton;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.File;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeChat;
import org.telegram.telegrambots.meta.api.objects.menubutton.MenuButtonCommands;
import org.telegram.telegrambots.meta.api.objects.menubutton.MenuButtonDefault;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SimpleTelegramBot implements LongPollingSingleThreadUpdateConsumer {
    private static final Logger log = LoggerFactory.getLogger(SimpleTelegramBot.class);
    // Внутрішні дані
    protected final String token;
    protected final TelegramClient client;
    protected Path root;

    // Список обробників подій 
    private volatile boolean isInitialized = false;
    private final List<MyFunctionalInterface> handlerList = new ArrayList<>();
    private final ThreadLocal<Update> updateEvent = new ThreadLocal<>();

    public SimpleTelegramBot(String token) {
        // За допомогою client наш бот звертатиметься до серверів Telegram
        this.token = token;
        this.client = new OkHttpTelegramClient(token);

        // Формуємо абсолютний шлях до папки з ресурсами
        this.root = SimpleTelegramBot.getProjectRoot();
    }

    /**
     * Від сервера Telegram надійшла подія Update updateEvent.
     * Потрібно викликати onInitialize() — лише один раз.
     * Потрібно викликати handler, якщо тип події збігається.
     */
    @Override
    public void consume(Update updateEvent) {
        //call onInitialize() with try..catch
        try {
            if (!isInitialized) {
                isInitialized = true;
                onInitialize();
            }
        } catch (Exception e) {
            System.out.println("onInitialize ERROR: " + e.getMessage());
            handleError(updateEvent, e);
        }

        //call handler-list with try..catch for every handler 
        try {
            this.updateEvent.set(updateEvent);

            for (var handler : handlerList) {
                try {
                    handler.execute();
                } catch (Exception e) {
                    System.out.println("onHandler ERROR: " + e.getMessage());
                    handleError(updateEvent, e);
                }
            }

            onUpdateEventReceived(this.updateEvent.get());
        } catch (Exception e) {
            System.out.println("onUpdateEventReceived ERROR: " + e.getMessage());
            handleError(updateEvent, e);
        }
    }

    /**
     * Якщо під час обробки події виник виняток:
     * - логуємо його
     * - надсилаємо повідомлення в чат
     */
    public void handleError(Update update, Exception exception) {
        // Друкуємо traceback у консоль
        log.error("Помилка під час обробки update: {}", update, exception);

        try {
            if (update != null && update.getMessage() != null) {
                String message;
                if (exception.getMessage() != null && !exception.getMessage().isEmpty()) {
                    message = exception.getMessage();
                } else {
                    message = exception.toString();
                }

                this.sendHtmlMessage("⚠️ "+ message);
            }
        } catch (Exception e) {
            log.warn("Не вдалося надіслати повідомлення про помилку користувачу", e);
        }
    }

    /**
     * Перевизначайте для ініціалізації бота (меню, команди, кеші тощо)
     */
    public void onInitialize() {
        // do nothing
    }

    /**
     * Зареєструвати обробник команди виду "/command"
     */
    public void addCommandHandler(String command, MyFunctionalInterface method) {
        handlerList.add(() -> {
            String messageText = getMessageText();
            if (("/" + command).equals(messageText))
                method.execute();
        });
    }

    /**
     * Зареєструвати обробник кнопки за regex за callback data
     */
    public void addButtonHandler(String regex, MyFunctionalInterface method) {
        handlerList.add(() -> {
            String buttonKey = getButtonKey();
            if (Pattern.matches(regex, buttonKey) && !buttonKey.isEmpty())
                method.execute();
        });
    }

    /**
     * Зареєструвати обробник звичайного тексту (не команди)
     */
    public void addMessageTextHandler(MyFunctionalInterface method) {
        handlerList.add(() -> {
            String messageText = getMessageText();
            if (!isMessageCommand() && !messageText.isEmpty())
                method.execute();
        });
    }

    /**
     * Зареєструвати обробник отримання фото
     */
    public void addMessagePhotoHandler(MyFunctionalInterface method) {
        handlerList.add(() -> {
            List<PhotoSize> photoList = getMessagePhotoList();
            if (!isMessageCommand() && !photoList.isEmpty())
                method.execute();
        });
    }

    /**
     * Перевизначайте для власної логіки після chain‑хендлерів
     */
    public void onUpdateEventReceived(Update updateEvent) throws Exception {
        //do nothing
    }

    /**
     * Поточний chatId (для message/callback)
     */
    public String getCurrentChatId() {
        Update update = updateEvent.get();
        if (update.hasMessage())
            return update.getMessage().getFrom().getId().toString();

        if (update.hasCallbackQuery())
            return update.getCallbackQuery().getFrom().getId().toString();

        return null;
    }

    /**
     * Текст останнього вхідного message (або порожній рядок)
     */
    public String getMessageText() {
        Update update = updateEvent.get();
        var text = update.hasMessage() ? update.getMessage().getText() : null;
        return text != null ? text : "";
    }

    /**
     * Список фото з останнього вхідного message (або порожній список)
     */
    public List<PhotoSize> getMessagePhotoList() {
        Update update = updateEvent.get();
        var list = update.hasMessage() ? update.getMessage().getPhoto() : null;
        return list != null ? list : new ArrayList<>();
    }

    /**
     * Чи є message командою
     */
    public boolean isMessageCommand() {
        Update update = updateEvent.get();
        return update.hasMessage() && update.getMessage().isCommand();
    }

    /**
     * Callback data натиснутої inline‑кнопки (або порожній рядок)
     */
    public String getButtonKey() {
        Update update = updateEvent.get();
        return update.hasCallbackQuery() ? update.getCallbackQuery().getData() : "";
    }

    /**
     * Повідомлення з натиснутою inline‑кнопкою (або порожній рядок)
     */
    public Message getButtonMessage() {
        Update update = updateEvent.get();
        var maybe = update.hasCallbackQuery() ? update.getCallbackQuery().getMessage() : null;
        return (Message) maybe;
    }

    /**
     * Надіслати текст (Markdown). Якщо «підкреслення» ламають Markdown — даємо підказку й шлемо HTML‑версією.
     */
    public Message sendTextMessage(String text) {
        if (isMarkdownValid(text)) {
            SendMessage command = createApiSendMessageCommand(String.valueOf(text));
            return executeTelegramApiMethod(command);
        } else {
            var message = "Рядок '%s' є недопустимим з огляду на markdown. Скористайтеся методом sendHtmlMessage().".formatted(text);
            System.out.println(message);
            return sendHtmlMessage(message);
        }
    }

    /**
     * Надіслати HTML‑текст
     */
    public Message sendHtmlMessage(String text) {
        var command = SendMessage.builder()
                .text(text)
                .parseMode("HTML")
                .chatId(getCurrentChatId())
                .build();

        return executeTelegramApiMethod(command);
    }

    /**
     * Надіслати фото + текст
     */

    public Message sendPhotoMessage(String photoKey) {
        return sendPhotoTextMessage(photoKey, null);
    }

    public Message sendPhotoMessage(Path photoPath) {
        return sendPhotoTextMessage(photoPath, null);
    }

    public Message sendPhotoTextMessage(String photoKey, String text) {
        Path photoPath = Path.of("images/" + photoKey + ".jpg");
        return sendPhotoTextMessage(photoPath, text);
    }

    public Message sendPhotoTextMessage(Path photoPath, String text) {
        var command = createApiPhotoMessageCommand(photoPath, text);
        return executeTelegramApiMethod(command);
    }

    /**
     * Надіслати відео
     */
    public Message sendVideoMessage(Path videoPath) {
        var command = createApiVideoMessageCommand(videoPath);
        return executeTelegramApiMethod(command);
    }

    /**
     * Змінити текст раніше надісланого повідомлення
     */
    public void updateMessage(Message message, String text, String checkKey, String... buttons) {
        // 1) Збір нової клавіатури/панелі кнопок
        InlineKeyboardMarkup markup = buildKeyboard(List.of(buttons), checkKey);

        // 2) Перевіряємо, чи змінювався текст
        boolean textChanged = !Objects.equals(message.getText(), text);

        if (textChanged) {
            // Змінюємо текст і клавіатуру в одному запиті
            EditMessageText edit = EditMessageText.builder()
                    .chatId(message.getChatId())
                    .messageId(message.getMessageId())
                    .text(text)
                    .replyMarkup(markup)
                    .parseMode("Markdown")
                    .build();
            executeTelegramApiMethod(edit);
        } else {
            // Текст той самий — оновлюємо лише клавіатуру
            EditMessageReplyMarkup editMarkup = EditMessageReplyMarkup.builder()
                    .chatId(message.getChatId())
                    .messageId(message.getMessageId())
                    .replyMarkup(markup)
                    .build();
            executeTelegramApiMethod(editMarkup);
        }
    }

    /**
     * Повідомлення з inline‑кнопками (варіант varargs: "key1","Name1","key2","Name2",...)
     */
    public Message sendTextButtonsMessage(String text, String... buttons) {
        return sendTextButtonsCheckMessage(text, null, buttons);
    }

    public Message sendTextButtonsCheckMessage(String text, String checkKey, String... buttons) {
        SendMessage command = createApiSendMessageCommand(text);

        if (buttons.length > 0) {
            var markup = buildKeyboard(List.of(buttons), checkKey);
            command.setReplyMarkup(markup);
        }

        return executeTelegramApiMethod(command);
    }

    /**
     * Відображаємо головне меню бота  
     */
    public void showMainMenu(String... commands) {
        ArrayList<BotCommand> list = new ArrayList<BotCommand>();

        // перетворюємо пари ("/cmd","desc") → BotCommand
        for (int i = 0; i < commands.length; i += 2) {
            String key = commands[i];
            String description = commands[i + 1];

            if (key.startsWith("/")) //remove first /
                key = key.substring(1);

            BotCommand bc = new BotCommand(key, description);
            list.add(bc);
        }

        // Отримуємо поточний список команд для цього чату
        var chatId = getCurrentChatId();
        BotCommandScopeChat scope = BotCommandScopeChat.builder().chatId(chatId).build();

        GetMyCommands gmcs = new GetMyCommands();
        gmcs.setScope(scope);
        ArrayList<BotCommand> oldCommands = executeTelegramApiMethod(gmcs);

        // Якщо список не змінився — виходимо
        if (oldCommands.equals(list))
            return;

        // Встановлюємо новий список
        var cmds = SetMyCommands.builder()
                .commands(list)
                .scope(scope)
                .build();

        executeTelegramApiMethod(cmds);

        // Показуємо кнопку меню
        var ex = new SetChatMenuButton();
        ex.setChatId(chatId);
        ex.setMenuButton(MenuButtonCommands.builder().build());
        executeTelegramApiMethod(ex);
    }

    /**
     * Приховуємо головне меню бота
     */
    public void hideMainMenu() {
        // Видаляємо команди з меню
        var chatId = getCurrentChatId();
        BotCommandScopeChat scope = BotCommandScopeChat.builder().chatId(chatId).build();

        DeleteMyCommands dmds = new DeleteMyCommands();
        dmds.setScope(scope);
        executeTelegramApiMethod(dmds);

        // Приховуємо кнопку меню
        var ex = new SetChatMenuButton();
        ex.setChatId(chatId);
        ex.setMenuButton(MenuButtonDefault.builder().build());
        executeTelegramApiMethod(ex);
    }

    /**
     * Формуємо обʼєкт‑команду з надсилання тексту в поточний чат
     */
    private SendMessage createApiSendMessageCommand(String text) {
        var command = SendMessage.builder()
                .text(text)
                .parseMode("markdown")
                .chatId(getCurrentChatId())
                .build();
        return command;
    }

    /**
     * Створюємо панель із кнопками, яку прикріплюємо до повідомлення.
     * Додаємо галочку для кнопки, чий key збігається з checkKey
     */
    private InlineKeyboardMarkup buildKeyboard(List<String> buttons, String checkKey) {
        var keyboardRowList = new ArrayList<InlineKeyboardRow>();

        for (int i = 0; i < buttons.size(); i += 2) {
            String buttonKey = buttons.get(i);
            String buttonName = buttons.get(i + 1);

            buttonName = buttonKey.equals(checkKey) ? buttonName + " ✅" : buttonName;
            var button = InlineKeyboardButton.builder().text(buttonName).callbackData(buttonKey).build();

            InlineKeyboardRow row = new InlineKeyboardRow(button);
            keyboardRowList.add(row);
        }

        return new InlineKeyboardMarkup(keyboardRowList);
    }

    /**
     * Формуємо обʼєкт‑команду з надсилання фото в поточний чат
     */
    private SendPhoto createApiPhotoMessageCommand(Path photoPath, String text) {
        try {
            photoPath = photoPath.isAbsolute() ? photoPath : root.resolve(photoPath);
            InputFile inputFile = new InputFile();
            inputFile.setMedia(photoPath.toFile());

            var command = SendPhoto.builder()
                    .photo(inputFile)
                    .chatId(getCurrentChatId())
                    .caption(text)
                    .build();

            return command;
        } catch (Exception e) {
            throw new RuntimeException("Не вдалося створити фото‑повідомлення!");
        }
    }

    /**
     * Формуємо обʼєкт‑команду з надсилання відео в поточний чат
     */
    private SendVideo createApiVideoMessageCommand(Path videoPath) {
        try {
            videoPath = videoPath.isAbsolute() ? videoPath : root.resolve(videoPath);
            InputFile inputFile = new InputFile();
            inputFile.setMedia(videoPath.toFile());

            var command = SendVideo.builder()
                    .video(inputFile)
                    .chatId(getCurrentChatId())
                    .build();

            return command;
        } catch (Exception e) {
            throw new RuntimeException("Не вдалося створити відеоповідомлення!");
        }
    }

    /**
     * Завантажуємо prompt із папки `prompts/` за його іменем
     */
    public static String loadPrompt(String name) {
        try {
            String path = name.contains("/") ? name : "prompts/" + name + ".txt";
            var is = ClassLoader.getSystemResourceAsStream(path);
            return new String(is.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException("Не вдалося завантажити AI prompt!");
        }
    }

    /**
     * Завантажуємо повідомлення з папки `messages/` за його іменем
     */
    public static String loadMessage(String name) {
        try {
            String path = name.contains("/") ? name : "messages/" + name + ".txt";
            var is = ClassLoader.getSystemResourceAsStream(path);
            return new String(is.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException("Не вдалося завантажити повідомлення!");
        }
    }

    /**
     * Створюємо директорію для файлів поточного користувача
     */
    public void createUserDir(String userId) {
        try {
            Path userPath = Paths.get("users/" + userId);
            Files.createDirectories(root.resolve(userPath));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Завантажуємо файл із сервера Telegram за FILE_ID і зберігаємо його на диск у PATH
     */
    public void downloadTelegramFile(String fileId, Path path) {
        File tgFile = executeTelegramApiMethod(new GetFile(fileId));
        String filePathOnTG = tgFile.getFilePath();
        String downloadUrl = "https://api.telegram.org/file/bot" + token + "/" + filePathOnTG;

        downloadFile(downloadUrl, path);
    }

    /**
     * Завантажуємо файл за URL і зберігаємо його на диск у PATH
     */
    public void downloadFile(String url, Path path) {
        try {
            path = path.isAbsolute() ? path : root.resolve(path);
            InputStream is = new URL(url).openStream();
            Files.copy(is, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Перевіряємо, що текст — валідний Telegram Markdown
     */
    private static boolean isMarkdownValid(String text) {
        long underscoreCount = text != null ? text.chars().filter(c -> c == '_').count() : 0;
        return underscoreCount % 2 == 0;
    }


    /* Обгортки над client.execute(...) */

    private <T extends Serializable, Method extends BotApiMethod<T>> T executeTelegramApiMethod(Method method) {
        try {
            return client.execute(method);
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
    }

    private Message executeTelegramApiMethod(SendPhoto message) {
        try {
            return client.execute(message);
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
    }

    private Message executeTelegramApiMethod(SendVideo message) {
        try {
            return client.execute(message);
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
    }

    @FunctionalInterface
    public static interface MyFunctionalInterface {
        void execute();
    }

    public static Path getProjectRoot() {
        try {
            // Пробуємо прочитати PROJECT_ROOT з .env
            Dotenv env = Dotenv.configure().ignoreIfMissing().load();
            String projectRoot = env.get("PROJECT_ROOT");
            if (projectRoot != null)
                return Path.of(projectRoot);

            // Визначаємо PROJECT_ROOT самостійно
            var uri = SimpleTelegramBot.class.getClassLoader().getResource("").toURI();
            return Paths.get(uri);

        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
