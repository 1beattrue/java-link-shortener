package ru.onebeattrue.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.onebeattrue.application.LinkPolicy;
import ru.onebeattrue.application.Shortener;
import ru.onebeattrue.infrastructure.SqliteLinkStore;

class ConsoleTest {
    private static final String CODE = "Ab3x9Kq2";

    @TempDir Path directory;

    private Shortener service;

    @BeforeEach
    void setUp() throws IOException {
        service =
                new Shortener(
                        new SqliteLinkStore(directory.resolve("links.db")),
                        new LinkPolicy(Duration.ofHours(1), 2, 10),
                        Clock.systemUTC(),
                        () -> CODE);
    }

    @Test
    void acceptsShortLinkDirectlyAndLaunchesBrowserOnlyWithinLimit() throws IOException {
        var opened = new ArrayList<URI>();
        String output =
                run(
                        opened::add,
                        """
                        create https://example.org 1
                        short/Ab3x9Kq2
                        open short/Ab3x9Kq2
                        exit
                        """);

        assertEquals(List.of(URI.create("https://example.org")), opened);
        assertTrue(output.contains("Ваш UUID:"));
        assertTrue(output.contains("ссылка заблокирована"));
        assertTrue(output.contains("лимит переходов исчерпан"));
    }

    @Test
    void handlesMissingBrowserAndContinuesProcessingCommands() throws IOException {
        String output =
                run(
                        uri -> {
                            throw new IOException("No desktop");
                        },
                        """
                        create https://example.org
                        open Ab3x9Kq2
                        list
                        exit
                        """);

        assertTrue(
                output.contains("Не удалось открыть браузер. Исходный адрес: https://example.org"));
        assertTrue(output.contains("переходы: 1/2"));
    }

    @Test
    void restoresAUserByUuidAndShowsOnlyTheirLinks() throws IOException {
        var link = service.create(null, "https://example.org", null);
        String output = run(uri -> {}, "use " + link.owner() + "\nlist\nexit\n");

        assertTrue(output.contains("Текущий пользователь: " + link.owner()));
        assertTrue(output.contains("short/" + CODE + " → https://example.org"));
    }

    private String run(Browser browser, String commands) throws IOException {
        var output = new StringWriter();
        var writer = new PrintWriter(output, true);
        new Console(service, browser, writer).run(new BufferedReader(new StringReader(commands)));
        return output.toString();
    }
}
