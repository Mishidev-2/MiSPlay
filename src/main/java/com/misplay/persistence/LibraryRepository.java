package com.misplay.persistence;

import com.misplay.model.TrackMetadata;
import com.misplay.model.PlaybackPreferences;
import com.misplay.model.Playlist;
import com.misplay.model.RepeatMode;

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
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Blocking database operations. Call these methods on the database worker. */
public final class LibraryRepository {
    private static final int SCHEMA_VERSION = 6;
    // Bump this when tag interpretation or fallback rules change.
    private static final int METADATA_CACHE_VERSION = 2;
    private final Path databasePath;

    /** A tag result together with the file attributes it was read from. */
    public record CachedMetadata(
            TrackMetadata metadata,
            long fileSize,
            String modifiedTime
    ) {
        public CachedMetadata {
            Objects.requireNonNull(metadata);
            Objects.requireNonNull(modifiedTime);

            if (fileSize < 0) {
                throw new IllegalArgumentException("File size cannot be negative.");
            }
        }
    }

    /** Null cachedMetadata means that this path needs a fresh tag read. */
    public record SavedTrack(File file, CachedMetadata cachedMetadata) {
        public SavedTrack {
            Objects.requireNonNull(file);
        }
    }

    /** All saved library state read before startup is unlocked. */
    public record LibraryState(
            List<SavedTrack> tracks, PlaybackPreferences preferences,
            List<Playlist> playlists, Set<File> favoriteFiles
    ) {
        public LibraryState {
            tracks = List.copyOf(tracks);
            Objects.requireNonNull(preferences);
            playlists = List.copyOf(playlists);
            favoriteFiles = Set.copyOf(favoriteFiles);
        }
    }

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
        return loadLibrary().stream().map(SavedTrack::file).toList();
    }

    public List<SavedTrack> loadLibrary() throws IOException, SQLException {
        return loadState().tracks();
    }

    public LibraryState loadState() throws IOException, SQLException {
        try (Connection connection = openConnection()) {
            initializeSchema(connection);
            connection.setAutoCommit(false);

            try {
                List<SavedTrack> entries = readTracks(connection);
                PlaybackPreferences preferences = readPreferences(connection);
                List<Playlist> playlists = readPlaylists(connection);
                Set<File> favorites = readFavorites(connection);
                connection.commit();
                return new LibraryState(entries, preferences, playlists, favorites);
            } catch (SQLException | RuntimeException error) {
                rollback(connection, error);
                throw error;
            }
        }
    }

    private static List<SavedTrack> readTracks(Connection connection) throws SQLException {
        List<SavedTrack> entries = new ArrayList<>();
        ArtistRows names = readArtists(connection);

        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     """
                     SELECT id, path, title, artist, album, file_size,
                            modified_time, metadata_version,
                            track_number, disc_number, duration_millis
                     FROM library_tracks ORDER BY id
                     """
             )) {
            while (rows.next()) {
                Path path = Path.of(rows.getString("path"));

                if (!path.isAbsolute()) {
                    throw new SQLException("Saved library contains a relative path.");
                }

                // Missing files remain library members, including offline drives.
                entries.add(new SavedTrack(
                        path.normalize().toFile(), readCachedMetadata(rows, names)
                ));
            }
        }

        return List.copyOf(entries);
    }

    private static PlaybackPreferences readPreferences(Connection connection)
            throws SQLException {
        PlaybackPreferences defaults = PlaybackPreferences.defaults();

        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(
                     "SELECT volume, repeat_mode, shuffle_enabled "
                             + "FROM playback_preferences WHERE id = 1"
             )) {
            if (!row.next()) {
                return defaults;
            }

            double volume = defaults.volume();
            Object storedVolume = row.getObject("volume");

            if (storedVolume instanceof Number number) {
                double value = number.doubleValue();

                if (Double.isFinite(value) && value >= 0.0 && value <= 1.0) {
                    volume = value;
                }
            }

            RepeatMode repeatMode = defaults.repeatMode();
            String storedRepeat = row.getString("repeat_mode");

            if (storedRepeat != null) {
                try {
                    repeatMode = RepeatMode.valueOf(storedRepeat);
                } catch (IllegalArgumentException ignored) {
                    // Unknown choices use a safe default without losing other settings.
                }
            }

            Object storedShuffle = row.getObject("shuffle_enabled");
            boolean shuffle = storedShuffle instanceof Number number
                    && number.doubleValue() == 1.0;

            return new PlaybackPreferences(volume, repeatMode, shuffle);
        }
    }

    public void addFiles(List<File> files) throws IOException, SQLException {
        saveChanges(files, List.of());
    }

    /** Inserts paths before updating their cache, preserving library order. */
    public void saveChanges(List<File> files, List<SavedTrack> metadataChanges)
            throws IOException, SQLException {
        saveChanges(files, metadataChanges, null);
    }

    /** Null preferences means this batch does not change playback settings. */
    public void saveChanges(
            List<File> files, List<SavedTrack> metadataChanges,
            PlaybackPreferences preferences
    ) throws IOException, SQLException {
        saveChanges(files, metadataChanges, preferences, null);
    }

    /** Null playlists preserves them; an empty snapshot deletes all playlists. */
    public void saveChanges(
            List<File> files, List<SavedTrack> metadataChanges,
            PlaybackPreferences preferences, List<Playlist> playlists
    ) throws IOException, SQLException {
        saveChanges(files, metadataChanges, preferences, playlists, null);
    }

    /** Null favorites preserves them; an empty snapshot removes all favorites. */
    public void saveChanges(
            List<File> files, List<SavedTrack> metadataChanges,
            PlaybackPreferences preferences, List<Playlist> playlists,
            Set<File> favorites
    ) throws IOException, SQLException {
        if (files.isEmpty() && metadataChanges.isEmpty()
                && preferences == null && playlists == null && favorites == null) {
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

                try (PreparedStatement update = connection.prepareStatement(
                        """
                        UPDATE library_tracks
                        SET title = ?, artist = ?, album = ?, file_size = ?,
                            modified_time = ?, metadata_version = ?,
                            track_number = ?, disc_number = ?, duration_millis = ?
                        WHERE path = ?
                        """
                )) {
                    for (SavedTrack change : metadataChanges) {
                        CachedMetadata cached = change.cachedMetadata();

                        if (cached == null) {
                            for (int index = 1; index <= 9; index++) {
                                int type = index == 4 || index >= 6
                                        ? java.sql.Types.BIGINT : java.sql.Types.VARCHAR;
                                update.setNull(index, type);
                            }
                        } else {
                            update.setString(1, cached.metadata().title());
                            update.setString(2, cached.metadata().artist());
                            update.setString(3, cached.metadata().album());
                            update.setLong(4, cached.fileSize());
                            update.setString(5, cached.modifiedTime());
                            update.setInt(6, METADATA_CACHE_VERSION);
                            setNumber(update, 7, cached.metadata().trackNumber());
                            setNumber(update, 8, cached.metadata().discNumber());
                            setNumber(update, 9, cached.metadata().durationMillis());
                        }

                        update.setString(10, change.file().toPath()
                                .toAbsolutePath().normalize().toString());
                        update.addBatch();
                    }

                    for (int count : update.executeBatch()) {
                        if (count == 0) {
                            throw new SQLException("A cached track path is missing from the database.");
                        }
                    }
                }

                saveArtists(connection, metadataChanges);

                if (preferences != null) {
                    try (PreparedStatement save = connection.prepareStatement(
                            """
                            INSERT INTO playback_preferences
                                (id, volume, repeat_mode, shuffle_enabled)
                            VALUES (1, ?, ?, ?)
                            ON CONFLICT(id) DO UPDATE SET
                                volume = excluded.volume,
                                repeat_mode = excluded.repeat_mode,
                                shuffle_enabled = excluded.shuffle_enabled
                            """
                    )) {
                        save.setDouble(1, preferences.volume());
                        save.setString(2, preferences.repeatMode().name());
                        save.setInt(3, preferences.shuffleEnabled() ? 1 : 0);

                        if (save.executeUpdate() != 1) {
                            throw new SQLException("Playback preferences were not saved.");
                        }
                    }
                }

                if (playlists != null) {
                    savePlaylists(connection, playlists);
                }

                if (favorites != null) {
                    saveFavorites(connection, favorites);
                }

                connection.commit();
            } catch (SQLException | RuntimeException error) {
                rollback(connection, error);
                throw error;
            }
        }
    }

    private static Set<File> readFavorites(Connection connection) throws SQLException {
        Set<File> favorites = new HashSet<>();

        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     """
                     SELECT track.path
                     FROM favorite_tracks favorite
                     LEFT JOIN library_tracks track ON track.id = favorite.track_id
                     """
             )) {
            while (rows.next()) {
                String storedPath = rows.getString("path");

                if (storedPath == null) {
                    throw new SQLException("A favorite refers to a missing library row.");
                }

                Path path = Path.of(storedPath);

                if (!path.isAbsolute()) {
                    throw new SQLException("A favorite contains a relative path.");
                }

                favorites.add(path.normalize().toFile());
            }
        }

        return Set.copyOf(favorites);
    }

    private static void saveFavorites(Connection connection, Set<File> favorites)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM favorite_tracks");
        }

        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO favorite_tracks(track_id) "
                        + "SELECT id FROM library_tracks WHERE path = ?"
        )) {
            for (File file : favorites) {
                insert.setString(1, file.toPath().toAbsolutePath().normalize().toString());

                if (insert.executeUpdate() != 1) {
                    throw new SQLException("A favorite is not in the saved library.");
                }
            }
        }
    }

    private static List<Playlist> readPlaylists(Connection connection)
            throws SQLException {
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, List<File>> members = new LinkedHashMap<>();

        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT id, name FROM playlists ORDER BY position"
             )) {
            while (rows.next()) {
                String id = rows.getString("id");

                if (id == null || names.containsKey(id)) {
                    throw new SQLException("Saved playlists contain an invalid ID.");
                }

                names.put(id, rows.getString("name"));
                members.put(id, new ArrayList<>());
            }
        }

        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     """
                     SELECT entry.playlist_id, track.path
                     FROM playlist_entries entry
                     LEFT JOIN library_tracks track ON track.id = entry.track_id
                     ORDER BY entry.playlist_id, entry.position
                     """
             )) {
            while (rows.next()) {
                List<File> files = members.get(rows.getString("playlist_id"));
                String storedPath = rows.getString("path");

                if (files == null || storedPath == null) {
                    throw new SQLException("A saved playlist refers to a missing library row.");
                }

                Path path = Path.of(storedPath);

                if (!path.isAbsolute()) {
                    throw new SQLException("A saved playlist contains a relative path.");
                }

                // A missing file on disk still belongs to the playlist.
                files.add(path.normalize().toFile());
            }
        }

        List<Playlist> playlists = new ArrayList<>();

        try {
            for (Map.Entry<String, String> entry : names.entrySet()) {
                playlists.add(new Playlist(
                        entry.getKey(), entry.getValue(), members.get(entry.getKey())
                ));
            }
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new SQLException("Saved playlist data is invalid.", error);
        }

        return List.copyOf(playlists);
    }

    private static void savePlaylists(Connection connection, List<Playlist> playlists)
            throws SQLException {
        // Replace just the playlist snapshot in this transaction. Library rows,
        // their stable IDs, metadata and the captured playback queue are untouched.
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM playlist_entries");
            statement.executeUpdate("DELETE FROM playlists");
        }

        try (PreparedStatement insertPlaylist = connection.prepareStatement(
                "INSERT INTO playlists(id, name, position) VALUES (?, ?, ?)"
        ); PreparedStatement insertEntry = connection.prepareStatement(
                """
                INSERT INTO playlist_entries(playlist_id, track_id, position)
                SELECT ?, id, ? FROM library_tracks WHERE path = ?
                """
        )) {
            var ids = new HashSet<String>();

            for (int playlistIndex = 0; playlistIndex < playlists.size(); playlistIndex++) {
                Playlist playlist = playlists.get(playlistIndex);

                if (!ids.add(playlist.id())) {
                    throw new SQLException("Duplicate playlist ID in a save snapshot.");
                }

                insertPlaylist.setString(1, playlist.id());
                insertPlaylist.setString(2, playlist.name());
                insertPlaylist.setInt(3, playlistIndex);
                insertPlaylist.executeUpdate();

                for (int trackIndex = 0; trackIndex < playlist.files().size(); trackIndex++) {
                    insertEntry.setString(1, playlist.id());
                    insertEntry.setInt(2, trackIndex);
                    insertEntry.setString(3, playlist.files().get(trackIndex).toString());

                    if (insertEntry.executeUpdate() != 1) {
                        throw new SQLException("A playlist song is not in the saved library.");
                    }
                }
            }
        }
    }

    private record ArtistRows(
            Map<Long, List<String>> artists, Map<Long, List<String>> albumArtists,
            Set<Long> invalidTracks
    ) {}

    private static ArtistRows readArtists(Connection connection) throws SQLException {
        Map<Long, List<String>> artists = new HashMap<>();
        Map<Long, List<String>> albumArtists = new HashMap<>();
        Set<Long> invalid = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT track_id, role, position, name FROM metadata_artists "
                             + "ORDER BY track_id, role, position"
             )) {
            while (rows.next()) {
                long id = rows.getLong("track_id");
                String role = rows.getString("role");
                if (!"artist".equals(role) && !"album_artist".equals(role)) {
                    invalid.add(id);
                    continue;
                }
                List<String> names = ("artist".equals(role) ? artists : albumArtists)
                        .computeIfAbsent(id, ignored -> new ArrayList<>());
                Object position = rows.getObject("position");
                String name = rows.getString("name");
                if (!isInteger(position) || ((Number) position).longValue() != names.size()
                        || name == null || name.isBlank()) {
                    invalid.add(id);
                } else {
                    names.add(name);
                }
            }
        }
        return new ArtistRows(artists, albumArtists, invalid);
    }

    private static CachedMetadata readCachedMetadata(ResultSet row, ArtistRows names)
            throws SQLException {
        String title = row.getString("title");
        String artist = row.getString("artist");
        String album = row.getString("album");
        String modifiedTime = row.getString("modified_time");
        Object size = row.getObject("file_size");
        Object version = row.getObject("metadata_version");
        long id = row.getLong("id");
        List<String> artists = names.artists().getOrDefault(id, List.of());

        if (!isInteger(version) || ((Number) version).longValue() != METADATA_CACHE_VERSION
                || !isInteger(size) || ((Number) size).longValue() < 0
                || title == null || title.isBlank()
                || album == null || album.isBlank()
                || modifiedTime == null || modifiedTime.isBlank()
                || names.invalidTracks().contains(id) || artists.isEmpty()
                || !String.join("; ", artists).equals(artist)) {
            return null;
        }
        try {
            TrackMetadata metadata = new TrackMetadata(
                    title, artists, album, names.albumArtists().getOrDefault(id, List.of()),
                    optionalInt(row, "track_number"), optionalInt(row, "disc_number"),
                    optionalLong(row, "duration_millis")
            );
            return new CachedMetadata(metadata, ((Number) size).longValue(), modifiedTime);
        } catch (IllegalArgumentException invalid) {
            // Bad or obsolete cache data is a miss; library membership stays valid.
            return null;
        }
    }

    private static boolean isInteger(Object value) {
        return value instanceof Integer || value instanceof Long
                || value instanceof Short || value instanceof Byte;
    }

    private static Long optionalLong(ResultSet row, String column) throws SQLException {
        Object value = row.getObject(column);
        if (value == null) {
            return null;
        }
        if (!isInteger(value) || ((Number) value).longValue() <= 0) {
            throw new IllegalArgumentException("Invalid cached " + column);
        }
        return ((Number) value).longValue();
    }

    private static Integer optionalInt(ResultSet row, String column) throws SQLException {
        Long value = optionalLong(row, column);
        if (value != null && value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid cached " + column);
        }
        return value == null ? null : value.intValue();
    }

    private static void setNumber(PreparedStatement statement, int index, Number value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index, value.longValue());
        }
    }

    private static void saveArtists(Connection connection, List<SavedTrack> changes)
            throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM metadata_artists WHERE track_id = "
                        + "(SELECT id FROM library_tracks WHERE path = ?)"
        ); PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO metadata_artists(track_id, role, position, name) "
                        + "SELECT id, ?, ?, ? FROM library_tracks WHERE path = ?"
        )) {
            for (SavedTrack change : changes) {
                String path = change.file().toPath().toAbsolutePath().normalize().toString();
                delete.setString(1, path);
                delete.executeUpdate();
                if (change.cachedMetadata() == null) {
                    continue;
                }
                TrackMetadata metadata = change.cachedMetadata().metadata();
                for (String role : List.of("artist", "album_artist")) {
                    List<String> names = role.equals("artist")
                            ? metadata.artists() : metadata.albumArtists();
                    for (int index = 0; index < names.size(); index++) {
                        insert.setString(1, role);
                        insert.setInt(2, index);
                        insert.setString(3, names.get(index));
                        insert.setString(4, path);
                        if (insert.executeUpdate() != 1) {
                            throw new SQLException("An artist's track is not in the saved library.");
                        }
                    }
                }
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
                statement.execute("PRAGMA foreign_keys = ON");
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
                if (version == 0) {
                    statement.execute("""
                            CREATE TABLE IF NOT EXISTS library_tracks (
                                id INTEGER PRIMARY KEY,
                                path TEXT NOT NULL UNIQUE
                            )
                            """);
                }

                // Check the old table before upgrading; migration is transactional.
                try (ResultSet ignored = statement.executeQuery(
                        "SELECT id, path FROM library_tracks LIMIT 0"
                )) {
                    // The query checks that the required columns exist.
                }

                if (version < 2) {
                    statement.execute("ALTER TABLE library_tracks ADD COLUMN title TEXT");
                    statement.execute("ALTER TABLE library_tracks ADD COLUMN artist TEXT");
                    statement.execute("ALTER TABLE library_tracks ADD COLUMN album TEXT");
                    statement.execute("ALTER TABLE library_tracks ADD COLUMN file_size INTEGER");
                    statement.execute("ALTER TABLE library_tracks ADD COLUMN modified_time TEXT");
                    statement.execute("ALTER TABLE library_tracks ADD COLUMN metadata_version INTEGER");
                }

                try (ResultSet ignored = statement.executeQuery(
                        "SELECT title, artist, album, file_size, modified_time, "
                                + "metadata_version FROM library_tracks LIMIT 0"
                )) {
                    // Verify the existing cache columns before claiming this schema.
                }

                statement.execute("""
                        CREATE TABLE IF NOT EXISTS playback_preferences (
                            id INTEGER PRIMARY KEY CHECK (id = 1),
                            volume REAL NOT NULL,
                            repeat_mode TEXT NOT NULL,
                            shuffle_enabled INTEGER NOT NULL
                        )
                        """);

                try (ResultSet ignored = statement.executeQuery(
                        "SELECT id, volume, repeat_mode, shuffle_enabled "
                                + "FROM playback_preferences LIMIT 0"
                )) {
                    // An incompatible existing table must fail, not be replaced.
                }

                statement.execute("""
                        CREATE TABLE IF NOT EXISTS playlists (
                            id TEXT PRIMARY KEY NOT NULL,
                            name TEXT NOT NULL,
                            position INTEGER NOT NULL UNIQUE CHECK (position >= 0)
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS playlist_entries (
                            playlist_id TEXT NOT NULL REFERENCES playlists(id)
                                ON DELETE CASCADE,
                            track_id INTEGER NOT NULL REFERENCES library_tracks(id),
                            position INTEGER NOT NULL CHECK (position >= 0),
                            PRIMARY KEY (playlist_id, track_id),
                            UNIQUE (playlist_id, position)
                        )
                        """);

                try (ResultSet ignored = statement.executeQuery(
                        "SELECT id, name, position FROM playlists LIMIT 0"
                ); ResultSet ignoredEntries = statement.executeQuery(
                        "SELECT playlist_id, track_id, position FROM playlist_entries LIMIT 0"
                )) {
                    // Existing incompatible playlist tables must also fail migration.
                }

                statement.execute("""
                        CREATE TABLE IF NOT EXISTS favorite_tracks (
                            track_id INTEGER PRIMARY KEY REFERENCES library_tracks(id)
                        )
                        """);

                try (ResultSet ignored = statement.executeQuery(
                        "SELECT track_id FROM favorite_tracks LIMIT 0"
                )) {
                    // An incompatible existing favorites table must fail migration.
                }

                if (version < 6) {
                    statement.execute("ALTER TABLE library_tracks ADD COLUMN track_number INTEGER");
                    statement.execute("ALTER TABLE library_tracks ADD COLUMN disc_number INTEGER");
                    statement.execute("ALTER TABLE library_tracks ADD COLUMN duration_millis INTEGER");
                }
                try (ResultSet ignored = statement.executeQuery(
                        "SELECT track_number, disc_number, duration_millis "
                                + "FROM library_tracks LIMIT 0"
                )) {
                    // Old metadata remains a version-1 cache miss until freshly read.
                }
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS metadata_artists (
                            track_id INTEGER NOT NULL REFERENCES library_tracks(id)
                                ON DELETE CASCADE,
                            role TEXT NOT NULL CHECK (role IN ('artist', 'album_artist')),
                            position INTEGER NOT NULL CHECK (position >= 0),
                            name TEXT NOT NULL,
                            PRIMARY KEY (track_id, role, position)
                        )
                        """);
                try (ResultSet ignored = statement.executeQuery(
                        "SELECT track_id, role, position, name FROM metadata_artists LIMIT 0"
                )) {
                    // An incompatible existing table fails the whole migration.
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
