package ru.onebeattrue.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppConfigTest {
    @TempDir Path directory;

    @Test
    void loadsStoragePathRelativeToConfigurationFile() throws IOException {
        Path config = directory.resolve("service.properties");
        Files.writeString(
                config,
                """
                database.path=db/links.db
                links.ttl=PT2H
                links.cleanup-interval=PT5S
                links.default-limit=3
                links.max-limit=9
                """);

        AppConfig settings = AppConfig.load(config);

        assertEquals(directory.resolve("db/links.db"), settings.database());
        assertEquals(Duration.ofHours(2), settings.links().ttl());
        assertEquals(Duration.ofSeconds(5), settings.cleanupInterval());
        assertEquals(3, settings.links().defaultLimit());
        assertEquals(9, settings.links().maxLimit());
    }

    @Test
    void rejectsInvalidCleanupInterval() throws IOException {
        Path config = directory.resolve("service.properties");
        Files.writeString(
                config,
                """
                database.path=links.db
                links.ttl=PT2H
                links.cleanup-interval=PT0S
                links.default-limit=3
                links.max-limit=9
                """);

        assertThrows(IllegalArgumentException.class, () -> AppConfig.load(config));
    }
}
