package ru.onebeattrue.domain;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public record ShortLink(
        String code, UUID owner, URI target, int limit, int visits, Instant expiresAt) {
    public int remaining() {
        return limit - visits;
    }
}
