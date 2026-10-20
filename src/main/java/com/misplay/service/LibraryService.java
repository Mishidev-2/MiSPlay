package com.misplay.service;

import com.misplay.model.FolderScanResult;
import com.misplay.model.MetadataStatus;
import com.misplay.model.Track;
import com.misplay.model.TrackMetadata;
import com.misplay.persistence.LibraryRepository;
import javafx.beans.Observable;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private final Deque<Track> pendingMetadata = new ArrayDeque<>();
    private final MetadataService metadataService = new MetadataService();

    private final ExecutorService metadataExecutor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread worker = new Thread(runnable, "misplay-metadata");
                worker.setDaemon(true);
                return worker;
            });

    private Task<TrackMetadata> metadataTask;
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
            new ReadOnlyStringWrapper("Loading saved library...");

    // A single worker owns all database operations. It drains accepted writes
    // on normal shutdown, so this thread deliberately is not a daemon.
    private final ExecutorService databaseExecutor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread worker = new Thread(runnable, "misplay-library-db");
                worker.setDaemon(false);
                return worker;
            });

    private final Set<File> pendingDatabaseFiles = new LinkedHashSet<>();
    private Task<List<File>> restoreTask;
    private Task<Void> saveTask;
    private List<File> activeDatabaseFiles = List.of();
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

    // Call once from Application.start, after the UI bindings are installed.
    public void restoreLibrary() {
        if (closed || restoreStarted) {
            return;
        }

        restoreStarted = true;

        Task<List<File>> task = new Task<>() {
            @Override
            protected List<File> call() throws Exception {
                return libraryRepository.loadFiles();
            }
        };

        restoreTask = task;

        task.setOnSucceeded(event -> {
            if (closed || restoreTask != task) {
                return;
            }

            registerFiles(task.getValue());
            persistenceEnabled = true;
            restoreTask = null;
            libraryStorageMessage.set(
                    tracks.isEmpty()
                            ? "Library ready. Imported tracks will be saved."
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
                    "Saved library could not be opened. "
                            + "New imports will not be saved this session."
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

            if (!tracksByPath.containsKey(path)) {
                Track track = new Track(path.toFile());

                tracksByPath.put(path, track);
                additions.add(track);
            }
        }

        tracks.addAll(additions);

        pendingMetadata.addAll(additions);
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
                || librarySaveFailed.get() || pendingDatabaseFiles.isEmpty()) {
            return;
        }

        List<File> batch = List.copyOf(pendingDatabaseFiles);
        pendingDatabaseFiles.clear();

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                libraryRepository.addFiles(batch);
                return null;
            }
        };

        saveTask = task;
        activeDatabaseFiles = batch;
        savingLibrary.set(true);
        libraryStorageMessage.set("Saving library...");

        task.setOnSucceeded(event -> {
            if (closed || saveTask != task) {
                return;
            }

            saveTask = null;
            activeDatabaseFiles = List.of();
            savingLibrary.set(false);
            libraryStorageMessage.set("Library saved.");
            writeNextFiles();
        });

        task.setOnFailed(event -> {
            if (closed || saveTask != task) {
                return;
            }

            // Preserve unsaved paths for an explicit retry or the shutdown flush.
            List<File> waiting = new ArrayList<>(pendingDatabaseFiles);
            pendingDatabaseFiles.clear();
            pendingDatabaseFiles.addAll(batch);
            pendingDatabaseFiles.addAll(waiting);
            saveTask = null;
            activeDatabaseFiles = List.of();
            savingLibrary.set(false);
            librarySaveFailed.set(true);
            libraryStorageMessage.set(
                    "Library changes could not be saved. You can retry."
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

        if (track == readingTrack
                || track.isMetadataLoaded()
                || track.getMetadataStatus() == MetadataStatus.FAILED) {
            return;
        }

        if (pendingMetadata.remove(track)) {
            pendingMetadata.addFirst(track);
        }

        readNextMetadata();
    }

    private void readNextMetadata() {
        if (closed || metadataTask != null) {
            return;
        }

        Track track = pendingMetadata.pollFirst();

        if (track == null) {
            return;
        }

        File file = track.getFile();

        Task<TrackMetadata> task = new Task<>() {
            @Override
            protected TrackMetadata call() throws Exception {
                return metadataService.read(file);
            }
        };

        metadataTask = task;
        readingTrack = track;

        task.setOnSucceeded(event -> {
            if (closed || metadataTask != task) {
                return;
            }

            track.setMetadata(task.getValue());
            finishMetadataRead();
        });

        task.setOnFailed(event -> {
            if (closed || metadataTask != task) {
                return;
            }

            track.markMetadataFailed();

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

        Task<TrackMetadata> task = metadataTask;
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
        pendingDatabaseFiles.clear();
        restoreTask = null;
        saveTask = null;
        activeDatabaseFiles = List.of();
        savingLibrary.set(false);
        restoringLibrary.set(false);

        if (persistenceEnabled && !finalFiles.isEmpty()) {
            List<File> finalBatch = List.copyOf(finalFiles);
            databaseExecutor.execute(() -> {
                try {
                    libraryRepository.addFiles(finalBatch);
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
