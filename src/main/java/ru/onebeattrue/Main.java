package ru.onebeattrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import ru.onebeattrue.application.Shortener;
import ru.onebeattrue.application.StorageException;
import ru.onebeattrue.cli.Console;
import ru.onebeattrue.cli.DesktopBrowser;
import ru.onebeattrue.infrastructure.AppConfig;
import ru.onebeattrue.infrastructure.RandomCodes;
import ru.onebeattrue.infrastructure.SqliteLinkStore;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        if (args.length > 1) {
            System.err.println("Использование: java -jar link-shortener.jar [путь к настройкам]");
            System.exit(2);
        }

        try {
            run(Path.of(args.length == 0 ? "config/application.properties" : args[0]));
        } catch (IOException | IllegalArgumentException | StorageException e) {
            System.err.println("Ошибка приложения: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void run(Path configPath) throws IOException {
        AppConfig config = AppConfig.load(configPath);
        var store = new SqliteLinkStore(config.database());
        var shortener = new Shortener(store, config.links(), Clock.systemUTC(), new RandomCodes());
        var console =
                new Console(
                        shortener,
                        new DesktopBrowser(),
                        new PrintWriter(System.out, true, StandardCharsets.UTF_8));

        console.cleanExpired();
        var cleaner = Executors.newSingleThreadScheduledExecutor();
        try {
            long interval = config.cleanupInterval().toMillis();
            cleaner.scheduleWithFixedDelay(
                    console::cleanExpired, interval, interval, TimeUnit.MILLISECONDS);
            console.run(
                    new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
        } finally {
            cleaner.shutdownNow();
        }
    }
}
