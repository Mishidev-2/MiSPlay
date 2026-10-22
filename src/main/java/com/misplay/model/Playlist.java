package com.misplay.model;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** An immutable manual-playlist snapshot. Each library path appears at most once. */
public record Playlist(String id, String name, List<File> files, List<File> addedFiles) {
    public static final int MAX_NAME_LENGTH = 100;

    public Playlist {
        Objects.requireNonNull(id);
        Objects.requireNonNull(name);
        Objects.requireNonNull(files);
        Objects.requireNonNull(addedFiles);

        if (id.isBlank()) {
            throw new IllegalArgumentException("A playlist needs an ID.");
        }

        name = name.strip();

        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Enter a playlist name with 1–" + MAX_NAME_LENGTH + " characters."
            );
        }

        files = normalize(files);
        addedFiles = normalize(addedFiles);
        Set<File> unique = new HashSet<>(files);

        if (unique.size() != files.size()
                || addedFiles.size() != files.size()
                || !new HashSet<>(addedFiles).equals(unique)) {
            throw new IllegalArgumentException(
                    "Both playlist orders must contain the same songs exactly once."
            );
        }
    }

    /** Existing callers establish the current order as the initial addition order. */
    public Playlist(String id, String name, List<File> files) {
        this(id, name, files, files);
    }

    private static List<File> normalize(List<File> files) {
        return files.stream()
                .map(file -> file.toPath().toAbsolutePath().normalize().toFile())
                .toList();
    }

    @Override
    public String toString() {
        return name;
    }
}
