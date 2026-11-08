package ru.onebeattrue.application;

import java.time.Duration;

public record LinkPolicy(Duration ttl, int defaultLimit, int maxLimit) {
    public LinkPolicy {
        if (ttl.compareTo(Duration.ofMillis(1)) < 0 || ttl.compareTo(Duration.ofDays(3650)) > 0) {
            throw new IllegalArgumentException("Срок жизни должен быть от 1 мс до 3650 дней.");
        }
        if (defaultLimit < 1 || defaultLimit > maxLimit) {
            throw new IllegalArgumentException("Лимит по умолчанию должен быть от 1 до максимума.");
        }
    }

    int checkedLimit(int limit) {
        if (limit < 1 || limit > maxLimit) {
            throw new IllegalArgumentException(
                    "Лимит переходов должен быть от 1 до " + maxLimit + ".");
        }
        return limit;
    }
}
