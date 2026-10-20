package com.misplay.service;

import com.misplay.model.MetadataStatus;
import com.misplay.model.Track;
import com.misplay.model.TrackMetadata;
import javafx.beans.Observable;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.misplay.model.FolderScanResult;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

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
        if (closed || folderScanTask != null) {
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
    }
}