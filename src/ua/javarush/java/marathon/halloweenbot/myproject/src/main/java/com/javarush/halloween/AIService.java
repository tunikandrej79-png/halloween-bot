package com.javarush.halloween;

import com.google.common.collect.ImmutableList;
import com.google.genai.Client;
import com.google.genai.types.*;
import io.github.cdimascio.dotenv.Dotenv;
import net.coobird.thumbnailator.Thumbnails;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class AIService {
    // Моделі — для роботи з текстом, зображеннями й відео
    private static final String TEXT_MODEL = "gemini-2.5-flash";
    private static final String IMAGE_MODEL = "gemini-2.5-flash-image";
    private static final String VIDEO_MODEL = "veo-3.0-fast-generate-001";

    // Налаштування безпеки
    private static final SafetySetting[] safetySettings = new SafetySetting[]{
            SafetySetting.builder() // Дозволяємо «горор» і кров як художній/кінематографічний образ
                    .category(HarmCategory.Known.HARM_CATEGORY_DANGEROUS_CONTENT)
                    .threshold(HarmBlockThreshold.Known.BLOCK_NONE)
                    .build(),
            SafetySetting.builder() // Будь-який сексуальний контент блокуємо максимально суворо.
                    .category(HarmCategory.Known.HARM_CATEGORY_SEXUALLY_EXPLICIT)
                    .threshold(HarmBlockThreshold.Known.BLOCK_NONE)
                    .build()
    };

    // Внутрішні дані
    private final Path root;
    private final Client client;
    private final GenerateContentConfig config;

    public AIService() {
        // Завантажуємо налаштування з файла `.env`
        Dotenv env = Dotenv.configure().ignoreIfMissing().load();

        var options = HttpOptions.builder()
                .baseUrl(env.get("BASE_URL"))
                .apiVersion(env.get("API_VERSION"))
                .build();

        this.client = Client.builder()
                .apiKey(env.get("GOOGLE_API_KEY"))
                .httpOptions(options)
                .build();

        this.config = GenerateContentConfig.builder()
                .safetySettings(safetySettings)
                .build();

        // Формуємо абсолютний шлях до папки з ресурсами
        this.root = SimpleTelegramBot.getProjectRoot();
    }

    /**
     * Простий текстовий запит до Google Gemini: текст --> текст
     */
    public String askForAnswer(String text) {
        var response = client.models.generateContent(TEXT_MODEL, text, config);
        return response.text();
    }

    /**
     * Генерація зображення за текстом за допомогою Google Gemini 2.5 Flash Image Preview.
     */
    public void createImage(String prompt, Path outputPath) {
        var resp = client.models.generateContent(IMAGE_MODEL, prompt, config);
        writeImageResponse(resp, outputPath);
    }

    /**
     * Редагування зображення: вихідна картинка + текстова інструкція.
     */
    public void editImage(Path inputPath, String prompt, Path outputPath) {
        Part text = Part.fromText(prompt);
        Part image = partFromLocalFile(inputPath);
        Content content = Content.fromParts(text, image);

        GenerateContentResponse resp = client.models.generateContent(IMAGE_MODEL, content, config);
        writeImageResponse(resp, outputPath);
    }

    /**
     * Обʼєднання кількох зображень за текстовою інструкцією.
     */
    public void mergeImages(List<Path> inputList, String prompt, Path outputPath) {
        // спочатку текст-інструкція
        List<Part> parts = new ArrayList<>();
        parts.add(Part.fromText(prompt));

        // потім картинки
        for (Path p : inputList) {
            parts.add(partFromLocalFile(p));
        }
        Content content = Content.fromParts(parts.toArray(Part[]::new));

        GenerateContentResponse resp = client.models.generateContent(IMAGE_MODEL, content, config);
        writeImageResponse(resp, outputPath);
    }

    /**
     * Генерація короткого відео за текстом і стартовим кадром (зображенням).
     * Використовується модель Google Gemini Flash Veo 3. Завжди 8 секунд.
     */
    public void videoFromTextAndImage(Path inputImage, String prompt, Path outVideo) {
        inputImage = inputImage.isAbsolute() ? inputImage : root.resolve(inputImage);
        Image img = Image.fromFile(inputImage.toString());

        // Базова конфігурація (підставте потрібні параметри — тривалість/AR тощо)
        GenerateVideosConfig cfg = GenerateVideosConfig.builder()
                .aspectRatio("9:16")
                .numberOfVideos(1)
                .build();

        // Запускаємо тривалу операцію
        GenerateVideosOperation op = client.models.generateVideos(VIDEO_MODEL, prompt, img, cfg);
        writeVideoResponse(op, outVideo);
    }

    /**
     * Розбір відповіді generateContent і збереження картинки.
     */
    private void writeImageResponse(GenerateContentResponse resp, Path outputPath) {
        final String errorMessage = "ШІ не зміг створити картинку. Спробуйте інший промпт.";
        final String noContentMessage = "Відповідь від ШІ не містить зображення. Спробуйте інший промпт.";
        final String imageSafetyMessage = "Запит відхилено фільтрами безпеки. Спробуйте змінити опис.";

        // Перевірка спрацювання фільтрів
        FinishReason finishReason = resp.finishReason();
        var finishKey = finishReason.knownEnum();
        if (finishKey == FinishReason.Known.IMAGE_SAFETY)
            throw new RuntimeException(imageSafetyMessage);

        // Порожня відповідь
        ImmutableList<Part> parts = resp.parts();
        if (parts == null || parts.isEmpty()) {
            throw new RuntimeException(noContentMessage);
        }

        // Виведемо можливі текстові пояснення
        parts.stream()
                .map(Part::text)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .forEach(System.out::println);

        // Шукаємо зображення й зберігаємо його
        for (Part part : Objects.requireNonNull(resp.parts())) {
            Optional<Blob> optionalBlob = part.inlineData();
            if (optionalBlob.isPresent() && optionalBlob.get().data().isPresent()) {
                Blob blob = optionalBlob.get();
                String mime = blob.mimeType().orElse("image/png");
                saveImageAsJpeg(outputPath, blob.data().get(), mime);
                return;
            }
        }
        throw new RuntimeException(errorMessage);
    }

    private void writeVideoResponse(GenerateVideosOperation op, Path outVideo) {
        try {
            // 1) Чекаємо завершення LRO (Long-Running Operation) із тайм-аутом
            final long timeoutNanos = TimeUnit.MINUTES.toNanos(5);
            final long started = System.nanoTime();

            // кожні 3 секунди опитуємо стан
            while (!op.done().orElse(false)) {
                if (System.nanoTime() - started > timeoutNanos) {
                    throw new RuntimeException("Очікування генерації відео перевищило 5 хвилин.");
                }
                TimeUnit.SECONDS.sleep(3);

                // Опитуємо стан операції за допомогою getVideosOperation
                op = client.operations.getVideosOperation(op, null);
            }

            // 2) Перевіряємо, що модель повернула відповідь і є принаймні одне відео
            var respOpt = op.response();
            if (respOpt.isEmpty()) {
                throw new RuntimeException("Відео не згенеровано: порожня відповідь операції.");
            }

            var videosOpt = respOpt.get().generatedVideos();
            if (videosOpt.isEmpty() || videosOpt.get().isEmpty()) {
                throw new RuntimeException("Відео не згенеровано: список generatedVideos порожній.");
            }

            GeneratedVideo gen = videosOpt.get().getFirst();
            var videoOpt = gen.video();
            if (videoOpt.isEmpty()) {
                throw new RuntimeException("Відео не згенеровано: обʼєкт Video відсутній.");
            }

            // 3) Завантажуємо файл і зберігаємо на диск
            // Підстраховка: перетворюємо шлях на абсолютний 
            outVideo = outVideo.isAbsolute() ? outVideo : root.resolve(outVideo);

            // Опціонально: перейменуємо наявний файл із часовою міткою
            rotateExisting(outVideo);

            // Завантажити файл на диск
            client.files.download(videoOpt.get(), outVideo.toString(), null);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Перейменовуємо картинку/відео перед перезаписом.
     * Зручно мати історію картинок для відлагодження.
     */
    private static void rotateExisting(Path file) {
        try {
            if (Files.exists(file) && Files.isRegularFile(file)) {
                String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
                String name = file.getFileName().toString();
                int dot = name.lastIndexOf('.');
                String base = (dot > 0) ? name.substring(0, dot) : name;
                String ext = (dot > 0) ? name.substring(dot) : "";
                Path rotated = file.getParent().resolve(base + "_" + ts + ext);
                Files.move(file, rotated, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Не критично: продовжуємо без ротації, але логуємо
            System.err.println("Не вдалося перейменувати наявний файл: " + e.getMessage());
        }
    }

    /**
     * Завантажуємо локальну картинку як Content Part.
     * Потрібно для роботи з Google Gemini API
     */
    private Part partFromLocalFile(Path path) {
        try {
            path = path.isAbsolute() ? path : root.resolve(path);
            String mime = Files.probeContentType(path);
            if (mime == null) mime = "image/jpeg"; // підстраховка
            return Part.fromBytes(Files.readAllBytes(path), mime);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Зберігаємо надіслану картинку як jpeg.
     * Зазвичай картинку надсилають як image/png, тому її потрібно перетворити на image/jpeg.
     */
    private void saveImageAsJpeg(Path outputPath, byte[] bytes, String mimeType) {
        try {
            outputPath = outputPath.isAbsolute() ? outputPath : root.resolve(outputPath);

            // Якщо картинка вже в JPEG — зберігаємо як є
            var jpegList = List.of("image/jpeg", "image/jpg");
            if (jpegList.contains(mimeType)) {
                Files.write(outputPath, bytes);
                return;
            }

            // Інакше — конвертуємо в JPEG одним ланцюжком Thumbnailator
            Thumbnails.of(new ByteArrayInputStream(bytes))
                    .scale(1.0)                          // без зміни розміру
                    .imageType(BufferedImage.TYPE_INT_RGB) // прибираємо альфу (тло стане чорним/бібліотечним дефолтом)
                    .outputFormat("jpg")
                    .outputQuality(0.92f)                // якість 0..1
                    .toFile(outputPath.toFile());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}