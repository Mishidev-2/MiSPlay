package com.misplay.service;

import com.misplay.model.FolderScanResult;
import com.misplay.model.Track;
import com.misplay.model.TrackMetadata;
import com.misplay.model.PlaybackPreferences;
import com.misplay.model.Playlist;
import com.misplay.persistence.LibraryRepository;
import com.misplay.persistence.LibraryRepository.CachedMetadata;
import com.misplay.persistence.LibraryRepository.SavedTrack;
import com.misplay.persistence.LibraryRepository.LibraryState;
import javafx.beans.Observable;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Library state and scheduling. Call public methods on the JavaFX thread. */
public class LibraryService implements AutoCloseable {
    private final ObservableList<Track> tracks =
            FXCollections.observableArrayList(
                    track -> new Observable[]{track.metadataProperty()}
            );

    private final ObservableList<Track> readOnlyTracks =
            FXCollections.unmodifiableObservableList(tracks);

    private final Map<Path, Track> tracksByPath = new HashMap<>();
    private final ObservableList<Playlist> playlists = FXCollections.observableArrayList();
    private final ObservableList<Playlist> readOnlyPlaylists =
            FXCollections.unmodifiableObservableList(playlists);
    private final ReadOnlyObjectWrapper<Set<File>> favoriteFiles =
            new ReadOnlyObjectWrapper<>(Set.of());

    private final Deque<Track> pendingMetadata = new ArrayDeque<>();
    private final Set<Track> queuedMetadata = new HashSet<>();
    private final Map<Path, CachedMetadata> metadataCache = new HashMap<>();
    private final MetadataService metadataService = new MetadataService();

    private final ExecutorService metadataExecutor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread worker = new Thread(runnable, "misplay-metadata");
                worker.setDaemon(true);
                return worker;
            });

    private record MetadataReadResult(CachedMetadata cached, boolean freshRead) {}

    private Task<MetadataReadResult> metadataTask;
    private Track readingTrack;
    private boolean closed;

    private final ReadOnlyBooleanWrapper folderScanning =
            new ReadOnlyBooleanWrapper(false);

    private final ReadOnlyStringWrapper folderScanMessage =
            new ReadOnlyStringWrapper("");

    private final ExecutorService folderScanExecutor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread worker = new Thread(runnable, "misplay-folder-scan");
                worker.setDaemon(true);
                return worker;
            });

    private FolderScanTask folderScanTask;

    private final LibraryRepository libraryRepository;
    private final ReadOnlyBooleanWrapper restoringLibrary =
            new ReadOnlyBooleanWrapper(true);
    private final ReadOnlyBooleanWrapper savingLibrary =
            new ReadOnlyBooleanWrapper(false);
    private final ReadOnlyBooleanWrapper librarySaveFailed =
            new ReadOnlyBooleanWrapper(false);
    private final ReadOnlyStringWrapper libraryStorageMessage =
            new ReadOnlyStringWrapper("Loading saved library data and settings...");

    // A single worker owns all database operations. It drains accepted writes
    // on normal shutdown, so this thread deliberately is not a daemon.
    private final ExecutorService databaseExecutor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread worker = new Thread(runnable, "misplay-library-db");
                worker.setDaemon(false);
                return worker;
            });

    private final Set<File> pendingDatabaseFiles = new LinkedHashSet<>();
    private final Map<Path, SavedTrack> pendingDatabaseMetadata = new LinkedHashMap<>();
    private Task<LibraryState> restoreTask;
    private Task<Void> saveTask;
    private List<File> activeDatabaseFiles = List.of();
    private List<SavedTrack> activeDatabaseMetadata = List.of();
    private PlaybackPreferences playbackPreferences = PlaybackPreferences.defaults();
    private PlaybackPreferences pendingPreferences;
    private PlaybackPreferences activePreferences;
    // Null means no pending change; an empty list is a real delete-all snapshot.
    private List<Playlist> pendingPlaylists;
    private List<Playlist> activePlaylists;
    private Set<File> pendingFavoriteFiles;
    private Set<File> activeFavoriteFiles;
    private boolean restoreStarted;
    private boolean persistenceEnabled;

    public LibraryService() {
        this(LibraryRepository.defaultDatabasePath());
    }

    public LibraryService(Path databasePath) {
        libraryRepository = new LibraryRepository(databasePath);
    }

    public Path getLibraryDatabasePath() {
        return libraryRepository.getDatabasePath();
    }

    public ReadOnlyBooleanProperty restoringLibraryProperty() {
        return restoringLibrary.getReadOnlyProperty();
    }

    public ReadOnlyBooleanProperty savingLibraryProperty() {
        return savingLibrary.getReadOnlyProperty();
    }

    public ReadOnlyBooleanProperty librarySaveFailedProperty() {
        return librarySaveFailed.getReadOnlyProperty();
    }

    public ReadOnlyStringProperty libraryStorageMessageProperty() {
        return libraryStorageMessage.getReadOnlyProperty();
    }

    public PlaybackPreferences getPlaybackPreferences() {
        return playbackPreferences;
    }

    public void setPlaybackPreferences(PlaybackPreferences preferences) {
        if (closed) {
            return;
        }

        if (restoringLibrary.get()) {
            throw new IllegalStateException("Wait for the saved settings to finish loading.");
        }

        Objects.requireNonNull(preferences);

        if (preferences.equals(playbackPreferences)) {
            return;
        }

        playbackPreferences = preferences;

        if (persistenceEnabled) {
            // A waiting snapshot always represents the latest user choices.
            pendingPreferences = preferences;
            writeNextFiles();
        }
    }

    public ObservableList<Playlist> getPlaylists() {
        return readOnlyPlaylists;
    }

    public Set<File> getFavoriteFiles() {
        return favoriteFiles.get();
    }

    public ReadOnlyObjectProperty<Set<File>> favoriteFilesProperty() {
        return favoriteFiles.getReadOnlyProperty();
    }

    public boolean isFavorite(Track track) {
        return favoriteFiles.get().contains(track.getFile());
    }

    /** Apply one bulk edit, publishing one immutable membership snapshot. */
    public int setFavorites(List<Track> selectedTracks, boolean favorite) {
        requireLibraryEditing();
        Set<File> updated = new HashSet<>(favoriteFiles.get());
        int changed = 0;

        for (Track track : selectedTracks) {
            File file = requireLibraryTrack(track);
            boolean edited = favorite ? updated.add(file) : updated.remove(file);

            if (edited) {
                changed++;
            }
        }

        if (changed > 0) {
            Set<File> snapshot = Set.copyOf(updated);
            favoriteFiles.set(snapshot);

            if (persistenceEnabled) {
                pendingFavoriteFiles = snapshot;
                writeNextFiles();
            }
        }

        return changed;
    }

    public Playlist getPlaylist(String id) {
        return playlists.stream().filter(playlist -> playlist.id().equals(id))
                .findFirst().orElseThrow(
                        () -> new IllegalArgumentException("This playlist no longer exists.")
                );
    }

    public Playlist createPlaylist(String name) {
        requireLibraryEditing();
        Playlist playlist = new Playlist(UUID.randomUUID().toString(), name, List.of());
        requireAvailablePlaylistName(playlist.name(), null);
        playlists.add(playlist);
        queuePlaylistSave();
        return playlist;
    }

    public void renamePlaylist(String id, String name) {
        requireLibraryEditing();
        Playlist previous = getPlaylist(id);
        Playlist renamed = new Playlist(id, name, previous.files(), previous.addedFiles());
        requireAvailablePlaylistName(renamed.name(), id);
        replacePlaylist(previous, renamed);
    }

    public void deletePlaylist(String id) {
        requireLibraryEditing();
        playlists.remove(getPlaylist(id));
        queuePlaylistSave();
    }

    /** Append selected library songs once, keeping their supplied order. */
    public int addToPlaylist(String id, List<Track> selectedTracks) {
        requireLibraryEditing();
        Playlist previous = getPlaylist(id);
        Set<File> files = new LinkedHashSet<>(previous.files());
        Set<File> addedFiles = new LinkedHashSet<>(previous.addedFiles());

        for (Track track : selectedTracks) {
            File file = requireLibraryTrack(track);
            files.add(file);
            addedFiles.add(file);
        }

        int added = files.size() - previous.files().size();

        if (added > 0) {
            replacePlaylist(previous, new Playlist(id, previous.name(), List.copyOf(files), List.copyOf(addedFiles)));
        }

        return added;
    }

    public int removeFromPlaylist(String id, List<Track> selectedTracks) {
        requireLibraryEditing();
        Playlist previous = getPlaylist(id);
        Set<File> removals = new HashSet<>();

        for (Track track : selectedTracks) {
            removals.add(requireLibraryTrack(track));
        }

        List<File> remaining = previous.files().stream()
                .filter(file -> !removals.contains(file)).toList();
        int removed = previous.files().size() - remaining.size();

        if (removed > 0) {
            replacePlaylist(previous, new Playlist(id, previous.name(), remaining,
                    previous.addedFiles().stream().filter(file -> !removals.contains(file)).toList()));
        }

        return removed;
    }

    /** Move one song a single step in the full manual order. */
    public boolean movePlaylistTrack(String id, Track track, int direction) {
        requireLibraryEditing();

        if (direction != -1 && direction != 1) {
            throw new IllegalArgumentException("Move direction must be -1 or 1.");
        }

        Playlist previous = getPlaylist(id);
        List<File> files = new ArrayList<>(previous.files());
        int index = files.indexOf(requireLibraryTrack(track));

        if (index < 0) {
            throw new IllegalArgumentException("This song is not in the playlist.");
        }

        int destination = index + direction;

        if (destination < 0 || destination >= files.size()) {
            return false;
        }

        java.util.Collections.swap(files, index, destination);
        replacePlaylist(previous, new Playlist(id, previous.name(), files, previous.addedFiles()));
        return true;
    }

    private void requireLibraryEditing() {
        if (closed || restoringLibrary.get()) {
            throw new IllegalStateException("Wait for the saved library to finish loading.");
        }
    }

    private File requireLibraryTrack(Track track) {
        Objects.requireNonNull(track);
        File file = track.getFile();

        if (tracksByPath.get(file.toPath()) != track) {
            throw new IllegalArgumentException("Choose a song from this library.");
        }

        return file;
    }

    private void requireAvailablePlaylistName(String name, String exceptId) {
        if (name.equalsIgnoreCase("All songs") || playlists.stream().anyMatch(
                playlist -> !playlist.id().equals(exceptId)
                        && playlist.name().equalsIgnoreCase(name)
        )) {
            throw new IllegalArgumentException("Choose a different playlist name.");
        }
    }

    private void replacePlaylist(Playlist previous, Playlist replacement) {
        if (!previous.equals(replacement)) {
            playlists.set(playlists.indexOf(previous), replacement);
            queuePlaylistSave();
        }
    }

    private void queuePlaylistSave() {
        if (persistenceEnabled) {
            pendingPlaylists = List.copyOf(playlists);
            writeNextFiles();
        }
    }

    // Call once from Application.start, after the UI bindings are installed.
    public void restoreLibrary() {
        if (closed || restoreStarted) {
            return;
        }

        restoreStarted = true;

        Task<LibraryState> task = new Task<>() {
            @Override
            protected LibraryState call() throws Exception {
                return libraryRepository.loadState();
            }
        };

        restoreTask = task;

        task.setOnSucceeded(event -> {
            if (closed || restoreTask != task) {
                return;
            }

            List<File> files = new ArrayList<>();
            LibraryState state = task.getValue();

            for (SavedTrack entry : state.tracks()) {
                files.add(entry.file());

                if (entry.cachedMetadata() != null) {
                    metadataCache.put(entry.file().toPath(), entry.cachedMetadata());
                }
            }

            // Assign before restoringLibrary becomes false and the UI unlocks.
            playbackPreferences = state.preferences();
            persistenceEnabled = true;
            registerFiles(files);
            playlists.setAll(state.playlists());
            favoriteFiles.set(state.favoriteFiles());
            restoreTask = null;
            libraryStorageMessage.set(
                    tracks.isEmpty()
                            ? "Library ready. Changes will be saved."
                            : "Restored " + tracks.size() + " saved tracks."
            );
            restoringLibrary.set(false);
        });

        task.setOnFailed(event -> {
            if (closed || restoreTask != task) {
                return;
            }

            restoreTask = null;
            persistenceEnabled = false;
            libraryStorageMessage.set(
                    "Saved library data or settings could not be opened. "
                            + "Changes will not be saved this session."
            );
            restoringLibrary.set(false);
            System.err.println(
                    "Library restore failed for " + getLibraryDatabasePath()
                            + ": " + task.getException()
            );
        });

        databaseExecutor.execute(task);
    }

    public ObservableList<Track> getTracks() {
        return readOnlyTracks;
    }

    public Track getTrack(File file) {
        Path path = file.toPath().toAbsolutePath().normalize();
        Track track = tracksByPath.get(path);

        if (track == null) {
            throw new IllegalArgumentException(
                    "Track is not in the library: " + file
            );
        }

        return track;
    }

    public void addFiles(List<File> files) {
        if (closed) {
            return;
        }

        if (restoringLibrary.get()) {
            throw new IllegalStateException("Wait for the saved library to finish loading.");
        }

        List<Track> additions = registerFiles(files);

        if (persistenceEnabled) {
            for (Track track : additions) {
                pendingDatabaseFiles.add(track.getFile());
            }

            writeNextFiles();
        }
    }

    private List<Track> registerFiles(List<File> files) {
        List<Track> additions = new ArrayList<>();

        for (File file : files) {
            Path path = file.toPath().toAbsolutePath().normalize();
            Track track = tracksByPath.get(path);

            if (track == null) {
                track = new Track(path.toFile());

                tracksByPath.put(path, track);
                additions.add(track);
            }

            // Reimporting an existing path also checks for edited/repaired files.
            queueMetadataCheck(track, false);
        }

        tracks.addAll(additions);

        readNextMetadata();
        return additions;
    }

    public void retryLibrarySave() {
        if (closed || !persistenceEnabled || saveTask != null) {
            return;
        }

        librarySaveFailed.set(false);
        writeNextFiles();
    }

    private void writeNextFiles() {
        if (closed || !persistenceEnabled || saveTask != null
                || librarySaveFailed.get()
                || (pendingDatabaseFiles.isEmpty() && pendingDatabaseMetadata.isEmpty()
                        && pendingPreferences == null && pendingPlaylists == null
                        && pendingFavoriteFiles == null)) {
            return;
        }

        List<File> batch = List.copyOf(pendingDatabaseFiles);
        List<SavedTrack> metadataBatch = List.copyOf(pendingDatabaseMetadata.values());
        PlaybackPreferences preferencesBatch = pendingPreferences;
        List<Playlist> playlistsBatch = pendingPlaylists;
        Set<File> favoritesBatch = pendingFavoriteFiles;
        pendingDatabaseFiles.clear();
        pendingDatabaseMetadata.clear();
        pendingPreferences = null;
        pendingPlaylists = null;
        pendingFavoriteFiles = null;

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                libraryRepository.saveChanges(
                        batch, metadataBatch, preferencesBatch, playlistsBatch, favoritesBatch
                );
                return null;
            }
        };

        saveTask = task;
        activeDatabaseFiles = batch;
        activeDatabaseMetadata = metadataBatch;
        activePreferences = preferencesBatch;
        activePlaylists = playlistsBatch;
        activeFavoriteFiles = favoritesBatch;
        savingLibrary.set(true);
        libraryStorageMessage.set("Saving library data and settings...");

        task.setOnSucceeded(event -> {
            if (closed || saveTask != task) {
                return;
            }

            saveTask = null;
            activeDatabaseFiles = List.of();
            activeDatabaseMetadata = List.of();
            activePreferences = null;
            activePlaylists = null;
            activeFavoriteFiles = null;
            savingLibrary.set(false);
            libraryStorageMessage.set("Library data and settings saved.");
            writeNextFiles();
        });

        task.setOnFailed(event -> {
            if (closed || saveTask != task) {
                return;
            }

            // Preserve unsaved changes for an explicit retry or the shutdown flush.
            List<File> waiting = new ArrayList<>(pendingDatabaseFiles);
            pendingDatabaseFiles.clear();
            pendingDatabaseFiles.addAll(batch);
            pendingDatabaseFiles.addAll(waiting);

            // A newer result for a path must win over a failed, older write.
            List<SavedTrack> waitingMetadata = List.copyOf(pendingDatabaseMetadata.values());
            pendingDatabaseMetadata.clear();
            retainMetadataChanges(pendingDatabaseMetadata, metadataBatch);
            retainMetadataChanges(pendingDatabaseMetadata, waitingMetadata);

            if (pendingPreferences == null) {
                pendingPreferences = preferencesBatch;
            }

            if (pendingPlaylists == null) {
                pendingPlaylists = playlistsBatch;
            }

            if (pendingFavoriteFiles == null) {
                pendingFavoriteFiles = favoritesBatch;
            }

            saveTask = null;
            activeDatabaseFiles = List.of();
            activeDatabaseMetadata = List.of();
            activePreferences = null;
            activePlaylists = null;
            activeFavoriteFiles = null;
            savingLibrary.set(false);
            librarySaveFailed.set(true);
            libraryStorageMessage.set(
                    "Library data or settings could not be saved. You can retry."
            );
            System.err.println(
                    "Library save failed for " + getLibraryDatabasePath()
                            + ": " + task.getException()
            );
        });

        databaseExecutor.execute(task);
    }

    public void prioritizeMetadata(File file) {
        if (closed) {
            return;
        }

        Track track = getTrack(file);

        // Even a loaded/failed Track can have been edited or repaired on disk.
        queueMetadataCheck(track, true);
        readNextMetadata();
    }

    private void queueMetadataCheck(Track track, boolean priority) {
        if (track == readingTrack) {
            return;
        }

        if (priority) {
            if (!queuedMetadata.add(track)) {
                pendingMetadata.remove(track);
            }

            pendingMetadata.addFirst(track);
        } else if (queuedMetadata.add(track)) {
            pendingMetadata.addLast(track);
        }
    }

    private void readNextMetadata() {
        if (closed || metadataTask != null) {
            return;
        }

        Track track = pendingMetadata.pollFirst();

        if (track == null) {
            writeNextFiles();
            return;
        }

        queuedMetadata.remove(track);
        File file = track.getFile();
        Path path = file.toPath();
        CachedMetadata cached = metadataCache.get(path);

        Task<MetadataReadResult> task = new Task<>() {
            @Override
            protected MetadataReadResult call() throws Exception {
                return readMetadata(file, cached);
            }
        };

        metadataTask = task;
        readingTrack = track;

        task.setOnSucceeded(event -> {
            if (closed || metadataTask != task) {
                return;
            }

            MetadataReadResult result = task.getValue();
            metadataCache.put(path, result.cached());
            track.setMetadata(result.cached().metadata());

            if (result.freshRead()) {
                queueMetadataSave(file, result.cached());
            }

            finishMetadataRead();
        });

        task.setOnFailed(event -> {
            if (closed || metadataTask != task) {
                return;
            }

            metadataCache.remove(path);
            track.setMetadata(new TrackMetadata(
                    file.getName(), "Unknown artist", "Unknown album"
            ));
            track.markMetadataFailed();
            queueMetadataSave(file, null);

            System.err.println(
                    "Metadata read failed for " + file
                            + ": " + task.getException()
            );

            finishMetadataRead();
        });

        track.markMetadataLoading();

        if (!closed) {
            metadataExecutor.execute(task);
        }
    }

    private MetadataReadResult readMetadata(File file, CachedMetadata cached)
            throws Exception {
        BasicFileAttributes before = readFileAttributes(file);

        if (cached != null
                && cached.fileSize() == before.size()
                && cached.modifiedTime().equals(before.lastModifiedTime().toString())) {
            return new MetadataReadResult(cached, false);
        }

        // Retry once if a tag editor changes/replaces the file during parsing.
        for (int attempt = 0; attempt < 2; attempt++) {
            TrackMetadata metadata = metadataService.read(file);
            BasicFileAttributes after = readFileAttributes(file);

            if (before.size() == after.size()
                    && before.lastModifiedTime().equals(after.lastModifiedTime())
                    && Objects.equals(before.fileKey(), after.fileKey())) {
                return new MetadataReadResult(new CachedMetadata(
                        metadata, after.size(), after.lastModifiedTime().toString()
                ), true);
            }

            before = after;
        }

        throw new IOException("The file kept changing while its tags were read. "
                + "Select or reimport it after the edit finishes.");
    }

    private static BasicFileAttributes readFileAttributes(File file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                file.toPath(), BasicFileAttributes.class
        );

        if (!attributes.isRegularFile()) {
            throw new IOException("Track path is not a regular file: " + file);
        }

        return attributes;
    }

    private void queueMetadataSave(File file, CachedMetadata cached) {
        if (!persistenceEnabled) {
            return;
        }

        pendingDatabaseMetadata.put(file.toPath(), new SavedTrack(file, cached));

        // Coalesce tag results during a longer import; the final result or close
        // flushes a smaller batch as well. Paths themselves are saved immediately.
        if (pendingDatabaseMetadata.size() >= 32 || pendingMetadata.isEmpty()) {
            writeNextFiles();
        }
    }

    private static void retainMetadataChanges(
            Map<Path, SavedTrack> destination, List<SavedTrack> changes
    ) {
        for (SavedTrack change : changes) {
            destination.put(change.file().toPath(), change);
        }
    }

    private void finishMetadataRead() {
        metadataTask = null;
        readingTrack = null;

        readNextMetadata();
    }

    public ReadOnlyBooleanProperty folderScanningProperty() {
        return folderScanning.getReadOnlyProperty();
    }

    public ReadOnlyStringProperty folderScanMessageProperty() {
        return folderScanMessage.getReadOnlyProperty();
    }

    public void scanFolder(File folder) {
        if (closed || restoringLibrary.get() || folderScanTask != null) {
            return;
        }

        FolderScanTask task = new FolderScanTask(folder);
        folderScanTask = task;

        task.setOnSucceeded(event -> {
            if (closed || folderScanTask != task) {
                return;
            }

            FolderScanResult result = task.getValue();

            int previousSize = tracks.size();
            addFiles(result.files());
            int added = tracks.size() - previousSize;

            String message = "Added " + added + " new tracks from "
                    + result.files().size() + " MP3 files.";

            if (result.skippedEntries() > 0) {
                message += " Skipped " + result.skippedEntries()
                        + " unreadable entries.";
            }

            finishFolderScan(task, message);
        });

        task.setOnCancelled(event -> finishFolderScan(
                task,
                "Folder scan cancelled. No tracks were added from this scan."
        ));

        task.setOnFailed(event -> {
            if (closed || folderScanTask != task) {
                return;
            }

            System.err.println(
                    "Folder scan failed for " + folder
                            + ": " + task.getException()
            );

            finishFolderScan(
                    task,
                    "Could not scan this folder. Check that it is accessible."
            );
        });

        folderScanMessage.bind(task.messageProperty());
        folderScanning.set(true);

        if (!closed && folderScanTask == task) {
            folderScanExecutor.execute(task);
        }
    }

    public void cancelFolderScan() {
        if (folderScanTask != null) {
            folderScanTask.cancel();
        }
    }

    private void finishFolderScan(FolderScanTask task, String message) {
        if (closed || folderScanTask != task) {
            return;
        }

        folderScanMessage.unbind();
        folderScanTask = null;

        folderScanMessage.set(message);
        folderScanning.set(false);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        closed = true;
        pendingMetadata.clear();
        queuedMetadata.clear();
        metadataCache.clear();

        Task<MetadataReadResult> task = metadataTask;
        metadataTask = null;
        readingTrack = null;

        if (task != null) {
            task.cancel();
        }

        metadataExecutor.shutdownNow();

        FolderScanTask scan = folderScanTask;
        folderScanTask = null;

        folderScanMessage.unbind();
        folderScanning.set(false);

        if (scan != null) {
            scan.cancel();
        }

        folderScanExecutor.shutdownNow();

        // Include an active batch even if its completion handler has not run.
        // Re-inserting committed paths is harmless because the SQL is idempotent.
        Set<File> finalFiles = new LinkedHashSet<>();

        finalFiles.addAll(activeDatabaseFiles);
        finalFiles.addAll(pendingDatabaseFiles);
        Map<Path, SavedTrack> finalMetadata = new LinkedHashMap<>();
        retainMetadataChanges(finalMetadata, activeDatabaseMetadata);
        retainMetadataChanges(finalMetadata, List.copyOf(pendingDatabaseMetadata.values()));
        PlaybackPreferences finalPreferences = pendingPreferences != null
                ? pendingPreferences : activePreferences;
        List<Playlist> finalPlaylists = pendingPlaylists != null
                ? pendingPlaylists : activePlaylists;
        Set<File> finalFavorites = pendingFavoriteFiles != null
                ? pendingFavoriteFiles : activeFavoriteFiles;
        pendingDatabaseFiles.clear();
        pendingDatabaseMetadata.clear();
        pendingPreferences = null;
        pendingPlaylists = null;
        pendingFavoriteFiles = null;
        restoreTask = null;
        saveTask = null;
        activeDatabaseFiles = List.of();
        activeDatabaseMetadata = List.of();
        activePreferences = null;
        activePlaylists = null;
        activeFavoriteFiles = null;
        savingLibrary.set(false);
        restoringLibrary.set(false);

        if (persistenceEnabled && (!finalFiles.isEmpty() || !finalMetadata.isEmpty()
                || finalPreferences != null || finalPlaylists != null
                || finalFavorites != null)) {
            List<File> finalBatch = List.copyOf(finalFiles);
            List<SavedTrack> finalMetadataBatch = List.copyOf(finalMetadata.values());
            databaseExecutor.execute(() -> {
                try {
                    libraryRepository.saveChanges(
                            finalBatch, finalMetadataBatch, finalPreferences,
                            finalPlaylists, finalFavorites
                    );
                } catch (Exception error) {
                    System.err.println(
                            "Final library save failed for " + getLibraryDatabasePath()
                                    + ": " + error
                    );
                }
            });
        }

        // Do not cancel database tasks or wait for them on the JavaFX thread.
        databaseExecutor.shutdown();
    }
}
