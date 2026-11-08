package ru.onebeattrue.application;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import ru.onebeattrue.domain.ShortLink;

public final class Shortener {
    private static final Pattern CODE = Pattern.compile("[a-zA-Z0-9]{8}");

    private final LinkStore store;
    private final LinkPolicy policy;
    private final Clock clock;
    private final Supplier<String> codes;

    public Shortener(LinkStore store, LinkPolicy policy, Clock clock, Supplier<String> codes) {
        this.store = store;
        this.policy = policy;
        this.clock = clock;
        this.codes = codes;
    }

    public ShortLink create(UUID owner, String url, Integer limit) {
        URI target = parseUrl(url);
        int maxVisits = limit == null ? policy.defaultLimit() : policy.checkedLimit(limit);
        UUID identity = owner == null ? UUID.randomUUID() : owner;
        Instant expiresAt = now().plusMillis(policy.ttl().toMillis());

        for (int attempt = 0; attempt < 12; attempt++) {
            ShortLink link =
                    new ShortLink(
                            parseCode(codes.get()), identity, target, maxVisits, 0, expiresAt);
            if (store.insert(link, owner == null)) {
                return link;
            }
        }
        throw new IllegalStateException("Не удалось подобрать свободный код. Попробуйте ещё раз.");
    }

    public boolean hasOwner(UUID owner) {
        return store.hasOwner(owner);
    }

    public List<ShortLink> list(UUID owner) {
        return store.list(owner, now());
    }

    public LinkStore.FollowResult follow(String code) {
        return store.follow(parseCode(code), now());
    }

    public Optional<ShortLink> edit(UUID owner, String code, String url, Integer limit) {
        if (url == null && limit == null) {
            throw new IllegalArgumentException("Укажите новый адрес или лимит.");
        }
        return store.update(
                owner,
                parseCode(code),
                url == null ? null : parseUrl(url),
                limit == null ? null : policy.checkedLimit(limit),
                now());
    }

    public boolean delete(UUID owner, String code) {
        return store.delete(owner, parseCode(code), now());
    }

    public List<String> purgeExpired() {
        return store.purgeExpired(now());
    }

    private Instant now() {
        return Instant.ofEpochMilli(clock.millis());
    }

    private static String parseCode(String code) {
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Код должен содержать 8 латинских букв или цифр.");
        }
        return code;
    }

    private static URI parseUrl(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null
                    && uri.getUserInfo() == null
                    && uri.getPort() <= 65535) {
                return uri;
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(
                    "Некорректный URL. Укажите адрес с http:// или https://.");
        }
        throw new IllegalArgumentException(
                "Некорректный URL. Укажите адрес с http:// или https://.");
    }
}
