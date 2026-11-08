package ru.onebeattrue.application;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ru.onebeattrue.domain.ShortLink;

public interface LinkStore {
    boolean insert(ShortLink link, boolean newOwner);

    boolean hasOwner(UUID owner);

    List<ShortLink> list(UUID owner, Instant now);

    FollowResult follow(String code, Instant now);

    Optional<ShortLink> update(UUID owner, String code, URI target, Integer limit, Instant now);

    boolean delete(UUID owner, String code, Instant now);

    List<String> purgeExpired(Instant now);

    enum FollowStatus {
        OPENED,
        MISSING,
        EXPIRED,
        LIMIT_REACHED
    }

    record FollowResult(FollowStatus status, ShortLink link) {}
}
