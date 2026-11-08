package ru.onebeattrue.infrastructure;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ru.onebeattrue.application.LinkStore;
import ru.onebeattrue.application.StorageException;
import ru.onebeattrue.domain.ShortLink;

public final class SqliteLinkStore implements LinkStore {
    private final String jdbcUrl;

    public SqliteLinkStore(Path database) throws IOException {
        Path path = database.toAbsolutePath().normalize();
        Files.createDirectories(path.getParent());
        jdbcUrl = "jdbc:sqlite:" + path.toUri().toASCIIString();
        execute(
                connection -> {
                    try (var statement = connection.createStatement()) {
                        statement.executeUpdate(
                                "CREATE TABLE IF NOT EXISTS owners (id TEXT PRIMARY KEY)");
                        statement.executeUpdate(
                                """
                                CREATE TABLE IF NOT EXISTS links (
                                    code TEXT PRIMARY KEY,
                                    owner TEXT NOT NULL REFERENCES owners(id) DEFERRABLE INITIALLY DEFERRED,
                                    target TEXT NOT NULL,
                                    visit_limit INTEGER NOT NULL CHECK (visit_limit > 0),
                                    visits INTEGER NOT NULL CHECK (visits BETWEEN 0 AND visit_limit),
                                    expires_at INTEGER NOT NULL
                                )
                                """);
                        statement.executeUpdate(
                                "CREATE INDEX IF NOT EXISTS links_by_owner ON links (owner)");
                        statement.executeUpdate(
                                "CREATE INDEX IF NOT EXISTS links_by_expiry ON links (expires_at)");
                    }
                    return null;
                });
    }

    @Override
    public boolean insert(ShortLink link, boolean newOwner) {
        return transaction(
                connection -> {
                    boolean inserted;
                    try (var insert =
                            prepare(
                                    connection,
                                    """
                                    INSERT INTO links (code, owner, target, visit_limit, visits, expires_at)
                                    VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (code) DO NOTHING
                                    """,
                                    link.code(),
                                    link.owner().toString(),
                                    link.target().toString(),
                                    link.limit(),
                                    link.visits(),
                                    link.expiresAt().toEpochMilli())) {
                        inserted = insert.executeUpdate() == 1;
                    }
                    if (inserted && newOwner) {
                        try (var owner =
                                prepare(
                                        connection,
                                        "INSERT INTO owners (id) VALUES (?)",
                                        link.owner().toString())) {
                            owner.executeUpdate();
                        }
                    }
                    return inserted;
                });
    }

    @Override
    public boolean hasOwner(UUID owner) {
        return execute(
                connection -> {
                    try (var select =
                                    prepare(
                                            connection,
                                            "SELECT 1 FROM owners WHERE id = ?",
                                            owner.toString());
                            var result = select.executeQuery()) {
                        return result.next();
                    }
                });
    }

    @Override
    public List<ShortLink> list(UUID owner, Instant now) {
        return execute(
                connection -> {
                    try (var select =
                                    prepare(
                                            connection,
                                            "SELECT * FROM links WHERE owner = ? AND expires_at > ? ORDER BY code",
                                            owner.toString(),
                                            now.toEpochMilli());
                            var result = select.executeQuery()) {
                        List<ShortLink> links = new ArrayList<>();
                        while (result.next()) {
                            links.add(readLink(result));
                        }
                        return links;
                    }
                });
    }

    @Override
    public FollowResult follow(String code, Instant now) {
        return transaction(
                connection -> {
                    var followed =
                            selectLink(
                                    connection,
                                    """
                                    UPDATE links SET visits = visits + 1
                                    WHERE code = ? AND expires_at > ? AND visits < visit_limit
                                    RETURNING *
                                    """,
                                    code,
                                    now.toEpochMilli());
                    if (followed.isPresent()) {
                        return new FollowResult(FollowStatus.OPENED, followed.get());
                    }

                    var existing =
                            selectLink(connection, "SELECT * FROM links WHERE code = ?", code);
                    if (existing.isEmpty()) {
                        return new FollowResult(FollowStatus.MISSING, null);
                    }
                    ShortLink link = existing.get();
                    if (!link.expiresAt().isAfter(now)) {
                        return new FollowResult(FollowStatus.EXPIRED, link);
                    }
                    return new FollowResult(FollowStatus.LIMIT_REACHED, link);
                });
    }

    @Override
    public Optional<ShortLink> update(
            UUID owner, String code, URI target, Integer limit, Instant now) {
        return execute(
                connection ->
                        selectLink(
                                connection,
                                """
                                UPDATE links SET target = COALESCE(?, target), visit_limit = COALESCE(?, visit_limit)
                                WHERE code = ? AND owner = ? AND expires_at > ?
                                    AND visits <= COALESCE(?, visit_limit)
                                RETURNING *
                                """,
                                target == null ? null : target.toString(),
                                limit,
                                code,
                                owner.toString(),
                                now.toEpochMilli(),
                                limit));
    }

    @Override
    public boolean delete(UUID owner, String code, Instant now) {
        return execute(
                connection -> {
                    try (var delete =
                            prepare(
                                    connection,
                                    "DELETE FROM links WHERE code = ? AND owner = ? AND expires_at > ?",
                                    code,
                                    owner.toString(),
                                    now.toEpochMilli())) {
                        return delete.executeUpdate() == 1;
                    }
                });
    }

    @Override
    public List<String> purgeExpired(Instant now) {
        return execute(
                connection -> {
                    try (var delete =
                                    prepare(
                                            connection,
                                            "DELETE FROM links WHERE expires_at <= ? RETURNING code",
                                            now.toEpochMilli());
                            var result = delete.executeQuery()) {
                        List<String> expired = new ArrayList<>();
                        while (result.next()) {
                            expired.add(result.getString("code"));
                        }
                        return expired;
                    }
                });
    }

    private <T> T execute(SqlWork<T> work) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout = 5000");
                statement.execute("PRAGMA foreign_keys = ON");
            }
            return work.run(connection);
        } catch (SQLException e) {
            throw new StorageException("Ошибка SQLite: " + e.getMessage(), e);
        }
    }

    private <T> T transaction(SqlWork<T> work) {
        return execute(
                connection -> {
                    connection.setAutoCommit(false);
                    try {
                        T result = work.run(connection);
                        connection.commit();
                        return result;
                    } catch (SQLException | RuntimeException e) {
                        try {
                            connection.rollback();
                        } catch (SQLException rollbackError) {
                            e.addSuppressed(rollbackError);
                        }
                        throw e;
                    }
                });
    }

    private static Optional<ShortLink> selectLink(
            Connection connection, String sql, Object... parameters) throws SQLException {
        try (var statement = prepare(connection, sql, parameters);
                var result = statement.executeQuery()) {
            return result.next() ? Optional.of(readLink(result)) : Optional.empty();
        }
    }

    private static PreparedStatement prepare(
            Connection connection, String sql, Object... parameters) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            return statement;
        } catch (SQLException e) {
            try {
                statement.close();
            } catch (SQLException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    private static ShortLink readLink(ResultSet row) throws SQLException {
        return new ShortLink(
                row.getString("code"),
                UUID.fromString(row.getString("owner")),
                URI.create(row.getString("target")),
                row.getInt("visit_limit"),
                row.getInt("visits"),
                Instant.ofEpochMilli(row.getLong("expires_at")));
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }
}
