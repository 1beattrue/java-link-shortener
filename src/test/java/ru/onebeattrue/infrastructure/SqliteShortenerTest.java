package ru.onebeattrue.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.onebeattrue.application.LinkStore.FollowStatus.EXPIRED;
import static ru.onebeattrue.application.LinkStore.FollowStatus.LIMIT_REACHED;
import static ru.onebeattrue.application.LinkStore.FollowStatus.MISSING;
import static ru.onebeattrue.application.LinkStore.FollowStatus.OPENED;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.onebeattrue.application.LinkPolicy;
import ru.onebeattrue.application.LinkStore.FollowStatus;
import ru.onebeattrue.application.Shortener;
import ru.onebeattrue.domain.ShortLink;

class SqliteShortenerTest {
    private static final String TARGET = "https://example.org/a?tag=one#start";
    private static final LinkPolicy POLICY = new LinkPolicy(Duration.ofHours(1), 4, 20);

    @TempDir Path temporary;

    private Path database;
    private SqliteLinkStore store;
    private Shortener service;
    private MutableClock clock;

    @BeforeEach
    void setUp() throws Exception {
        database = temporary.resolve("with spaces").resolve("links.db");
        store = new SqliteLinkStore(database);
        clock = new MutableClock();
        AtomicInteger sequence = new AtomicInteger();
        service =
                new Shortener(
                        store,
                        POLICY,
                        clock,
                        () -> String.format(Locale.ROOT, "%08d", sequence.incrementAndGet()));
    }

    @Test
    void separatesOwnersEvenForTheSameUrl() {
        ShortLink first = service.create(null, TARGET, null);
        ShortLink second = service.create(null, TARGET, null);

        assertNotEquals(first.owner(), second.owner());
        assertNotEquals(first.code(), second.code());
        assertEquals(List.of(first), service.list(first.owner()));
        assertEquals(List.of(second), service.list(second.owner()));
    }

    @Test
    void reusesOwnerForSubsequentLinks() {
        ShortLink first = service.create(null, TARGET, null);
        ShortLink second = service.create(first.owner(), "https://example.com/", 7);

        assertEquals(List.of(first, second), service.list(first.owner()));
        assertEquals(4, first.limit());
        assertEquals(7, second.limit());
    }

    @Test
    void retriesAConflictingCodeForAnotherOwner() {
        ShortLink first = service.create(null, TARGET, 2);
        var candidates = new ArrayDeque<>(List.of(first.code(), "Other123"));
        var retrying = new Shortener(store, POLICY, clock, candidates::removeFirst);

        ShortLink second = retrying.create(null, TARGET, 2);

        assertEquals("Other123", second.code());
        assertEquals(List.of(first), service.list(first.owner()));
        assertEquals(List.of(second), service.list(second.owner()));
    }

    @Test
    void persistsOwnerAddressAndCounterAcrossInstances() throws Exception {
        ShortLink link = service.create(null, TARGET, 2);
        assertEquals(OPENED, service.follow(link.code()).status());

        var restarted =
                new Shortener(new SqliteLinkStore(database), POLICY, clock, () -> "Another1");

        assertTrue(restarted.hasOwner(link.owner()));
        assertFalse(restarted.hasOwner(UUID.randomUUID()));
        ShortLink persisted = restarted.list(link.owner()).get(0);
        assertEquals(URI.create(TARGET), persisted.target());
        assertEquals(1, persisted.remaining());
        assertEquals(OPENED, restarted.follow(link.code()).status());
        assertEquals(LIMIT_REACHED, service.follow(link.code()).status());
    }

    @Test
    void opensTheLastAllowedVisitAndBlocksTheNextOne() {
        ShortLink link = service.create(null, TARGET, 1);

        var accepted = service.follow(link.code());
        assertEquals(OPENED, accepted.status());
        assertEquals(0, accepted.link().remaining());
        assertEquals(URI.create(TARGET), accepted.link().target());
        assertEquals(LIMIT_REACHED, service.follow(link.code()).status());
        assertEquals(1, service.list(link.owner()).get(0).visits());
    }

    @Test
    void enforcesTheLimitUnderConcurrentRequests() throws Exception {
        ShortLink link = service.create(null, TARGET, 5);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(12);
        try {
            var futures = new ArrayList<Future<FollowStatus>>();
            for (int i = 0; i < 24; i++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return service.follow(link.code()).status();
                                }));
            }
            start.countDown();
            var statuses = new ArrayList<FollowStatus>();
            for (var future : futures) {
                statuses.add(future.get(15, TimeUnit.SECONDS));
            }
            assertEquals(5, statuses.stream().filter(OPENED::equals).count());
            assertEquals(19, statuses.stream().filter(LIMIT_REACHED::equals).count());
            assertEquals(5, service.list(link.owner()).get(0).visits());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void expiresAtTheConfiguredBoundaryAndPurgesExactlyOnce() {
        ShortLink link = service.create(null, TARGET, 3);
        clock.advance(Duration.ofHours(1).minusMillis(1));
        assertEquals(OPENED, service.follow(link.code()).status());
        assertTrue(service.purgeExpired().isEmpty());

        clock.advance(Duration.ofMillis(1));

        assertEquals(EXPIRED, service.follow(link.code()).status());
        assertTrue(service.list(link.owner()).isEmpty());
        assertEquals(List.of(link.code()), service.purgeExpired());
        assertTrue(service.purgeExpired().isEmpty());
        assertEquals(MISSING, service.follow(link.code()).status());
    }

    @Test
    void forbidsAnotherOwnerFromEditingOrDeleting() {
        ShortLink link = service.create(null, TARGET, 3);
        ShortLink stranger = service.create(null, TARGET, 3);

        assertTrue(service.edit(stranger.owner(), link.code(), "https://example.net", 6).isEmpty());
        assertFalse(service.delete(stranger.owner(), link.code()));
        assertEquals(List.of(link), service.list(link.owner()));
    }

    @Test
    void ownerCanUpdateTargetAndRaiseLimitAfterBlocking() {
        ShortLink link = service.create(null, TARGET, 1);
        service.follow(link.code());
        assertEquals(LIMIT_REACHED, service.follow(link.code()).status());

        ShortLink updated =
                service.edit(link.owner(), link.code(), "https://example.net/", 2).orElseThrow();

        assertEquals(link.expiresAt(), updated.expiresAt());
        assertEquals(1, updated.visits());
        assertEquals(
                URI.create("https://example.net/"), service.follow(link.code()).link().target());
        assertEquals(LIMIT_REACHED, service.follow(link.code()).status());
    }

    @Test
    void rejectsLoweringLimitBelowTheVisitCountWithoutChangingTarget() {
        ShortLink link = service.create(null, TARGET, 3);
        service.follow(link.code());
        service.follow(link.code());

        assertTrue(service.edit(link.owner(), link.code(), "https://example.net/", 1).isEmpty());
        ShortLink stored = service.list(link.owner()).get(0);
        assertEquals(URI.create(TARGET), stored.target());
        assertEquals(3, stored.limit());
    }

    @Test
    void ownerCanDeleteALink() {
        ShortLink link = service.create(null, TARGET, null);

        assertTrue(service.delete(link.owner(), link.code()));
        assertTrue(service.list(link.owner()).isEmpty());
        assertEquals(MISSING, service.follow(link.code()).status());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "file:///etc/hosts",
                "https://",
                "https://exa mple.com",
                "https://user@example.org",
                "https://example.org:99999"
            })
    void rejectsMalformedOrUnsupportedUrls(String target) {
        assertThrows(IllegalArgumentException.class, () -> service.create(null, target, null));
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-11-08T10:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }
    }
}
