package ru.onebeattrue.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.util.UUID;
import ru.onebeattrue.application.Shortener;
import ru.onebeattrue.application.StorageException;
import ru.onebeattrue.domain.ShortLink;

public final class Console {
    private static final String PREFIX = "short/";

    private final Shortener shortener;
    private final Browser browser;
    private final PrintWriter out;
    private UUID owner;

    public Console(Shortener shortener, Browser browser, PrintWriter out) {
        this.shortener = shortener;
        this.browser = browser;
        this.out = out;
    }

    public void run(BufferedReader input) throws IOException {
        out.println("Сокращатель ссылок. Введите help для списка команд.");
        for (String line; (line = input.readLine()) != null; ) {
            if (line.isBlank()) {
                continue;
            }
            try {
                if (!execute(line.strip().split("\\s+"))) {
                    return;
                }
            } catch (IllegalArgumentException | IllegalStateException | StorageException e) {
                out.println("Ошибка: " + e.getMessage());
            }
        }
    }

    public void cleanExpired() {
        try {
            for (String code : shortener.purgeExpired()) {
                out.println(PREFIX + code + ": срок действия истёк, ссылка удалена.");
            }
        } catch (StorageException e) {
            out.println("Не удалось удалить просроченные ссылки: " + e.getMessage());
        }
    }

    private boolean execute(String[] args) {
        if (args[0].startsWith(PREFIX)) {
            requireArity(args, 1, args[0]);
            open(args[0]);
            return true;
        }

        switch (args[0]) {
            case "create" -> {
                if (args.length < 2 || args.length > 3) {
                    throw usage("create <URL> [лимит]");
                }
                create(args[1], args.length == 3 ? parseLimit(args[2]) : null);
            }
            case "use" -> {
                requireArity(args, 2, "use <UUID>");
                use(args[1]);
            }
            case "new" -> {
                requireArity(args, 1, "new");
                owner = null;
                out.println("Новая сессия. UUID будет выдан при создании первой ссылки.");
            }
            case "whoami" -> {
                requireArity(args, 1, "whoami");
                out.println("UUID: " + requireOwner());
            }
            case "list" -> {
                requireArity(args, 1, "list");
                var links = shortener.list(requireOwner());
                if (links.isEmpty()) {
                    out.println("У вас пока нет ссылок.");
                }
                links.forEach(this::printLink);
            }
            case "open" -> {
                requireArity(args, 2, "open <короткая ссылка>");
                open(args[1]);
            }
            case "edit" -> {
                requireArity(args, 4, "edit <короткая ссылка> <URL или -> <лимит или ->");
                edit(args[1], args[2], args[3]);
            }
            case "delete" -> {
                requireArity(args, 2, "delete <короткая ссылка>");
                boolean deleted = shortener.delete(requireOwner(), code(args[1]));
                out.println(
                        deleted
                                ? "Ссылка удалена."
                                : "Ссылка недоступна для удаления. Проверьте код, владельца и срок действия.");
            }
            case "help" -> {
                requireArity(args, 1, "help");
                printHelp();
            }
            case "exit" -> {
                requireArity(args, 1, "exit");
                return false;
            }
            default -> throw new IllegalArgumentException("Неизвестная команда. Введите help.");
        }
        return true;
    }

    private void create(String url, Integer limit) {
        ShortLink link = shortener.create(owner, url, limit);
        if (owner == null) {
            owner = link.owner();
            out.println("Ваш UUID: " + owner + ". Сохраните его для входа командой use.");
        }
        printLink(link);
    }

    private void use(String id) {
        UUID requested;
        try {
            requested = UUID.fromString(id);
            if (!requested.toString().equalsIgnoreCase(id)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Укажите UUID в формате xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx.");
        }
        if (!shortener.hasOwner(requested)) {
            throw new IllegalArgumentException("Пользователь с таким UUID не найден.");
        }
        owner = requested;
        out.println("Текущий пользователь: " + owner);
    }

    private void edit(String shortLink, String url, String limit) {
        shortener
                .edit(
                        requireOwner(),
                        code(shortLink),
                        url.equals("-") ? null : url,
                        limit.equals("-") ? null : parseLimit(limit))
                .ifPresentOrElse(
                        this::printLink,
                        () ->
                                out.println(
                                        "Не удалось изменить ссылку. Проверьте владельца, срок действия и число уже сделанных переходов."));
    }

    private void open(String shortLink) {
        String linkCode = code(shortLink);
        var result = shortener.follow(linkCode);
        switch (result.status()) {
            case OPENED -> {
                ShortLink link = result.link();
                launch(link.target());
                if (link.remaining() == 0) {
                    out.println(PREFIX + linkCode + ": лимит исчерпан, ссылка заблокирована.");
                } else {
                    out.println("Осталось переходов: " + link.remaining());
                }
            }
            case MISSING -> out.println("Ссылка не найдена или уже удалена.");
            case EXPIRED -> out.println(PREFIX + linkCode + ": срок действия истёк.");
            case LIMIT_REACHED -> out.println(PREFIX + linkCode + ": лимит переходов исчерпан.");
        }
    }

    private void launch(URI target) {
        try {
            browser.open(target);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            out.println("Не удалось открыть браузер. Исходный адрес: " + target);
        }
    }

    private UUID requireOwner() {
        if (owner == null) {
            throw new IllegalStateException(
                    "Сначала создайте ссылку или войдите командой use <UUID>.");
        }
        return owner;
    }

    private void printLink(ShortLink link) {
        out.printf(
                "%s%s → %s | переходы: %d/%d | действует до: %s%n",
                PREFIX, link.code(), link.target(), link.visits(), link.limit(), link.expiresAt());
    }

    private static String code(String input) {
        return input.startsWith(PREFIX) ? input.substring(PREFIX.length()) : input;
    }

    private static int parseLimit(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Лимит должен быть целым числом.");
        }
    }

    private static void requireArity(String[] args, int expected, String syntax) {
        if (args.length != expected) {
            throw usage(syntax);
        }
    }

    private static IllegalArgumentException usage(String syntax) {
        return new IllegalArgumentException("Формат команды: " + syntax);
    }

    private void printHelp() {
        out.print(
                """
                create <URL> [лимит]                             Сократить ссылку
                open <короткая ссылка>                          Открыть адрес в браузере
                short/Ab3x9Kq2                                  Открыть ссылку напрямую
                use <UUID>                                      Войти как существующий пользователь
                new                                             Начать сессию нового пользователя
                whoami                                          Показать текущий UUID
                list                                            Показать свои ссылки
                edit <короткая ссылка> <URL или -> <лимит или -> Изменить адрес и/или лимит
                delete <короткая ссылка>                        Удалить свою ссылку
                help                                            Показать команды
                exit                                            Выйти
                Пример: create https://example.org/guide 5
                """);
        out.flush();
    }
}
