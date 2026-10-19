package com.misplay.service;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class LibraryService {
    private final ObservableList<File> tracks =
            FXCollections.observableArrayList();

    private final ObservableList<File> readOnlyTracks =
            FXCollections.unmodifiableObservableList(tracks);

    private final Set<Path> knownPaths = new HashSet<>();

    public ObservableList<File> getTracks() {
        return readOnlyTracks;
    }

    public void addFiles(List<File> files) {
        List<File> additions = new ArrayList<>();

        for (File file : files) {
            Path path = file.toPath().toAbsolutePath().normalize();

            if (knownPaths.add(path)) {
                additions.add(path.toFile());
            }
        }

        tracks.addAll(additions);
    }
}