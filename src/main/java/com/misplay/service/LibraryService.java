package com.misplay.service;

import com.misplay.model.Track;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LibraryService {
    private final ObservableList<File> tracks =
            FXCollections.observableArrayList();

    private final ObservableList<File> readOnlyTracks =
            FXCollections.unmodifiableObservableList(tracks);

    private final Map<Path, Track> tracksByPath = new HashMap<>();

    public ObservableList<File> getTracks() {
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
        List<File> additions = new ArrayList<>();

        for (File file : files) {
            Path path = file.toPath().toAbsolutePath().normalize();

            if (!tracksByPath.containsKey(path)) {
                File normalizedFile = path.toFile();

                tracksByPath.put(
                        path,
                        new Track(normalizedFile)
                );

                additions.add(normalizedFile);
            }
        }

        tracks.addAll(additions);
    }
}