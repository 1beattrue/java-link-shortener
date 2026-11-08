package ru.onebeattrue.infrastructure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Properties;
import ru.onebeattrue.application.LinkPolicy;

public record AppConfig(Path database, LinkPolicy links, Duration cleanupInterval) {
    public static AppConfig load(Path source) throws IOException {
        Path config = source.toAbsolutePath().normalize();
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(config)) {
            properties.load(reader);
        }
        Path database =
                config.getParent().resolve(required(properties, "database.path")).normalize();
        var policy =
                new LinkPolicy(
                        duration(properties, "links.ttl"),
                        integer(properties, "links.default-limit"),
                        integer(properties, "links.max-limit"));
        Duration interval = duration(properties, "links.cleanup-interval");
        if (interval.compareTo(Duration.ofMillis(1)) < 0
                || interval.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Интервал очистки должен быть от 1 мс до 1 суток.");
        }
        return new AppConfig(database, policy, interval);
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Укажите параметр " + key + ".");
        }
        return value.strip();
    }

    private static int integer(Properties properties, String key) {
        try {
            return Integer.parseInt(required(properties, key));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Параметр " + key + " должен быть целым числом.");
        }
    }

    private static Duration duration(Properties properties, String key) {
        try {
            return Duration.parse(required(properties, key));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "Укажите длительность " + key + " в формате ISO 8601.");
        }
    }
}
