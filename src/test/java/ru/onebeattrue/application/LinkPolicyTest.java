package ru.onebeattrue.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LinkPolicyTest {
    @Test
    void acceptsBothEndsOfConfiguredRange() {
        LinkPolicy policy = new LinkPolicy(Duration.ofHours(1), 3, 12);

        assertEquals(1, policy.checkedLimit(1));
        assertEquals(12, policy.checkedLimit(12));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 13})
    void rejectsLimitsOutsideConfiguredRange(int limit) {
        LinkPolicy policy = new LinkPolicy(Duration.ofHours(1), 3, 12);

        assertThrows(IllegalArgumentException.class, () -> policy.checkedLimit(limit));
    }

    @Test
    void rejectsAnImpossibleDefaultLimit() {
        assertThrows(
                IllegalArgumentException.class, () -> new LinkPolicy(Duration.ofHours(1), 13, 12));
    }

    @Test
    void rejectsTtlShorterThanClockResolution() {
        assertThrows(
                IllegalArgumentException.class, () -> new LinkPolicy(Duration.ofNanos(1), 3, 12));
    }
}
