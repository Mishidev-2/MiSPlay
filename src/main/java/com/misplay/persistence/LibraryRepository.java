package com.misplay.persistence;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Blocking database operations. Call these methods on the database worker. */
public final class LibraryRepository {
    private static final int SCHEMA_VERSION = 1;
    private final Path databasePath;

    public LibraryRepository(Path databasePath) {
        this.databasePath = Objects.requireNonNull(databasePath)
                .toAbsolutePath().normalize();
    }

    public static Path defaultDatabasePath() {
        String localAppData = System.getenv("LOCALAPPDATA");

        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData, "MiSPlay", "library.db");
        }

        return Path.of(System.getProperty("user.home"), ".misplay", "library.db");
    }

    public Path getDatabasePath() {
        return databasePath;
    }

    public List<File> loadFiles() throws IOException, SQLException {
        try (Connection connection = openConnection()) {
            initializeSchema(connection);

            List<File> files = new ArrayList<>();

            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT path FROM library_tracks ORDER BY id"
                 )) {
                while (rows.next()) {
                    Path path = Path.of(rows.getString("path"));

                    if (!path.isAbsolute()) {
                        throw new SQLException("Saved library contains a relative path.");
                    }

                    // Missing files remain library members, including offline drives.
                    files.add(path.normalize().toFile());
                }
            }

            return List.copyOf(files);
        }
    }

    public void addFiles(List<File> files) throws IOException, SQLException {
        if (files.isEmpty()) {
            return;
        }

        try (Connection connection = openConnection()) {
            initializeSchema(connection);
            connection.setAutoCommit(false);

            try {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO library_tracks(path) VALUES (?) "
                                + "ON CONFLICT(path) DO NOTHING"
                )) {
                    for (File file : files) {
                        String path = file.toPath().toAbsolutePath()
                                .normalize().toString();

                        insert.setString(1, path);
                        insert.addBatch();
                    }

                    insert.executeBatch();
                }

                connection.commit();
            } catch (SQLException | RuntimeException error) {
                rollback(connection, error);
                throw error;
            }
        }
    }

    private Connection openConnection() throws IOException, SQLException {
        Files.createDirectories(databasePath.getParent());

        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException error) {
            throw new SQLException("SQLite JDBC driver is unavailable.", error);
        }

        Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + databasePath
        );

        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout = 3000");
            }

            return connection;
        } catch (SQLException error) {
            try {
                connection.close();
            } catch (SQLException closeError) {
                error.addSuppressed(closeError);
            }

            throw error;
        }
    }

    private void initializeSchema(Connection connection) throws SQLException {
        int version;

        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            if (!result.next()) {
                throw new SQLException("Could not read the library schema version.");
            }

            version = result.getInt(1);
        }

        if (version < 0 || version > SCHEMA_VERSION) {
            throw new SQLException("Unsupported library schema version: " + version);
        }

        if (version == SCHEMA_VERSION) {
            return;
        }

        connection.setAutoCommit(false);

        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS library_tracks (
                            id INTEGER PRIMARY KEY,
                            path TEXT NOT NULL UNIQUE
                        )
                        """);

                // Verify an existing version-zero table before claiming this schema.
                try (ResultSet ignored = statement.executeQuery(
                        "SELECT id, path FROM library_tracks LIMIT 0"
                )) {
                    // The query checks that the required columns exist.
                }

                statement.execute("PRAGMA user_version = " + SCHEMA_VERSION);
            }

            connection.commit();
        } catch (SQLException error) {
            rollback(connection, error);
            throw error;
        }

        connection.setAutoCommit(true);
    }

    private static void rollback(Connection connection, Exception error) {
        try {
            connection.rollback();
        } catch (SQLException rollbackError) {
            error.addSuppressed(rollbackError);
        }
    }
}
