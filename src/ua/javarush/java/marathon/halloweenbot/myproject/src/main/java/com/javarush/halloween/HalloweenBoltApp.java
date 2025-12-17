package com.javarush.halloween;

import io.github.cdimascio.dotenv.Dotenv;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

public class HalloweenBoltApp extends SimpleTelegramBot {

    private AIService aiService = new AIService();

    private AppMode mode;

    private String imageType = "create_anime";
    private ArrayList<Path> imageList = new ArrayList<>();

    public HalloweenBoltApp(String token) {
        super(token);
    }

    public void startCommand() {
        mode = AppMode.MAIN;

        // Отримали ідентифікатор користувача
        String currentChatId = getCurrentChatId();

        // Створили папку користувача
        createUserDir(currentChatId);

        hideMainMenu();

        showMainMenu("/start", "🧟‍♂️ Головне меню бота",
                "/image", "⚰️ Створюємо зображення",
                "/edit", "🧙‍♂️ Змінюємо зображення",
                "/merge", "🕷️ Об'єднуємо зображення",
                "/party", "🎃 Фото для Halloween-вечірки",
                "/video", "🎬☠️ Моторошне Halloween-відео з фото"
        );


        sendPhotoMessage("main");
        sendTextMessage(loadMessage("main"));

    }

    public void imageCommand() {
        mode = AppMode.CREATE;

        sendPhotoMessage("create");
        sendTextButtonsCheckMessage(loadMessage("create"), imageType,
                "create_anime", "👧 Аніме",
                "create_photo", "📸 Фото"
        );
    }

    public void imageMessage() {
        String text = getMessageText();
        String userId = getCurrentChatId();
        Path photopath = Path.of("users/" + userId + "/photo.jpg");
        String prompt = loadPrompt(imageType);
        aiService.createImage(prompt + text, photopath);
        sendPhotoMessage(photopath);
    }

    public void editCommand() {
        mode = AppMode.EDIT;
        sendPhotoMessage("edit");
        sendTextMessage(loadMessage("edit"));

    }

    public void editMessage() {
        String text = getMessageText();
        String userId = getCurrentChatId();
        Path photoPath = Path.of("users/" + userId + "/photo.jpg");
        if(!Files.exists(root.resolve(photoPath))) {
            sendTextMessage("Спочатку завантажте або створіть зображення");
            return;
        }

        String prompt = loadPrompt("edit");
        aiService.editImage(photoPath, prompt + text, photoPath);
        sendPhotoMessage(photoPath);

    }

    public void savePhoto(){
        var photo = getMessagePhotoList().getLast();
        var fileId = photo.getFileId();

        String userId = getCurrentChatId();
        Path photoPath = Path.of("users/" + userId + "/photo.jpg");
        downloadTelegramFile(fileId, photoPath);
        sendTextMessage("Фото готово до роботи");

    }

    public void mergeCommand() {
        mode = AppMode.MERGE;
        imageList.clear();

        String text = loadMessage("merge");
        sendPhotoMessage("merge");
        sendTextButtonsMessage(text,
                "merge_join", "Просто об’єднати зображення",
                "merge_first", "Додати всіх на перше зображення",
                "merge_last", "Додати всіх на останнє зображення");

    }

    public void mergeAddPhoto() {
        var photo = getMessagePhotoList().getLast();
        var fileId = photo.getFileId();

        int count = imageList.size() + 1;
        String userId = getCurrentChatId();
        Path photoPath = Path.of("users/" + userId + "/photo" + count + ".jpg");
        downloadTelegramFile(fileId, photoPath);
        imageList.add(photoPath);
        sendTextMessage(count + " фото готово до роботи");
    }

    public void mergeButtonCallback() {
        if(imageList.size() < 2) {
            sendTextMessage("Спочатку завантажте або створіть зображення");
            return;
        }

        String userId = getCurrentChatId();
        Path photoPath = Path.of("users/" + userId + "/result.jpg");

        String buttonKey = getButtonKey();
        String prompt = loadPrompt(buttonKey);
        aiService.mergeImages(imageList, prompt, photoPath);
        sendPhotoMessage(photoPath);

    }

    public void partyCommand() {
        mode = AppMode.PARTY;
        String text = loadMessage("party");
        sendPhotoMessage("party");
        sendTextButtonsMessage(text, "party_image1", "🐺 Місячне затемнення (перевертень)",
                "party_image2", "🦇 Прокляте дзеркало (вампір)",
                "party_image3", "🔮 Відьмине коло (дим і руни)",
                "party_image4", "🧟 Гниття часу (зомбі)",
                "party_image5", "😈 Призов демона (демон)");
    }

    public void onPhoto() {
        if(mode == AppMode.MERGE) {
            mergeAddPhoto();
        } else {
            savePhoto();
        }
    }

    public void imageButtonCallback(){
        imageType = getButtonKey();

        String text = loadMessage("create");

        Message message = getButtonMessage();

        updateMessage(message, text, imageType,
                "create_anime", "👧 Аніме",
                "create_photo", "📸 Фото");

    }

    public void partyButtonCallback(){
        String userId = getCurrentChatId();
        Path photoPath = Path.of("users/" + userId + "/photo.jpg");
        Path resultPath = Path.of("users/" + userId + "/result.jpg");
        if(!Files.exists(root.resolve(photoPath))) {
            sendTextMessage("Спочатку завантажте або створіть зображення");
            return;
        }

        String buttonKey = getButtonKey();
        String prompt = loadPrompt(buttonKey);
        aiService.editImage(photoPath,  prompt, resultPath);
        sendPhotoMessage(resultPath);

    }

    public void videoCommand() {
        mode = AppMode.VIDEO;
        String text = loadMessage("video");
        sendPhotoMessage("video");
        sendTextButtonsMessage(text,
                "video1", "🌕 Місячне затемнення (перевертень)",
                "video2", "🩸 Прокляте дзеркало (вампір)",
                "video3", "🧙‍♀️ Відьмине коло (дим і руни)",
                "video4", "🧟 Гниття часу (зомбі)",
                "video5", "😈 Пентаграма призову (демон)");
    }

    public void videoButtonCallback(){
        String userId = getCurrentChatId();
        Path photoPath = Path.of("users/" + userId + "/photo.jpg");
        Path resultPath = Path.of("users/" + userId + "/video.mp4");
        if(!Files.exists(root.resolve(photoPath))) {
            sendTextMessage("Спочатку завантажте або створіть зображення");
            return;
        }
        String buttonKey = getButtonKey();
        String prompt = loadPrompt(buttonKey);
        sendTextMessage("Генерація відео займе близько 20 секунд");

        aiService.videoFromTextAndImage(photoPath,  prompt, resultPath);
        sendVideoMessage(resultPath);

    }





    // користувачнаписав повідомлення
    //TODO: основний функціонал бота писатимемо тут
    public void onMessage() {

        if(mode == AppMode.CREATE) {
            imageMessage();
        } else if(mode == AppMode.EDIT) {
            editMessage();
        }
        else {
            String userInputMessage = getMessageText();

            sendTextMessage("*Привіт!*");
            sendTextMessage("Як справи, *друже?*");
            sendTextMessage("Ти написав: " + userInputMessage);
        }

    }




    // Ініціалізація. Додаємо обробники подій
    @Override
    public void onInitialize() {
        //TODO: і ще трохи тут
        addMessageTextHandler(this::onMessage);
        addButtonHandler("^create_.*", this::imageButtonCallback);
        addCommandHandler("start", this::startCommand);
        addCommandHandler("image", this::imageCommand);
        addCommandHandler("edit", this::editCommand);
        addMessagePhotoHandler(this::onPhoto);
        addCommandHandler("merge", this::mergeCommand);
        addButtonHandler("^merge_.*", this::mergeButtonCallback);
        addCommandHandler("party", this::partyCommand);
        addCommandHandler("video", this::videoCommand);
        addButtonHandler("^party.*", this::partyButtonCallback);
        addButtonHandler("^video.*", this::videoButtonCallback);


    }
;
    // Режими роботи
    enum AppMode {
        MAIN,
        CREATE,
        EDIT,
        MERGE,
        PARTY,
        VIDEO
    }

    // Створюємо Telegram-бота
    public static void main(String[] args) throws TelegramApiException {
        Dotenv env = Dotenv.configure().ignoreIfMissing().load();
        String telegramToken = env.get("TELEGRAM_TOKEN");

        var botsApplication = new TelegramBotsLongPollingApplication();
        botsApplication.registerBot(telegramToken, new HalloweenBoltApp(telegramToken));
    }
}