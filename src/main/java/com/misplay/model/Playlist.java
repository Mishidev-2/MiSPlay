package com.misplay.model;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** An immutable manual-playlist snapshot. Each library path appears at most once. */
public record Playlist(String id, String name, List<File> files) {
    public static final int MAX_NAME_LENGTH = 100;

    public Playlist {
        Objects.requireNonNull(id);
        Objects.requireNonNull(name);
        Objects.requireNonNull(files);

        if (id.isBlank()) {
            throw new IllegalArgumentException("A playlist needs an ID.");
        }

        name = name.strip();

        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Enter a playlist name with 1–" + MAX_NAME_LENGTH + " characters."
            );
        }

        files = files.stream()
                .map(file -> file.toPath().toAbsolutePath().normalize().toFile())
                .toList();
        Set<File> unique = new HashSet<>(files);

        if (unique.size() != files.size()) {
            throw new IllegalArgumentException("A song can appear only once in a playlist.");
        }
    }

    @Override
    public String toString() {
        return name;
    }
}
