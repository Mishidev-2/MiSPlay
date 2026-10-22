package com.misplay.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Album/artist indexes built from cached metadata and canonical library Tracks. */
public final class LibraryBrowse {
    private LibraryBrowse() {}

    public enum Mode {
        SONGS("Songs"), ALBUMS("Albums"), ARTISTS("Artists");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Structured keys avoid collisions between punctuation and multiple credits. */
    public record Key(Mode mode, String name, List<String> credits) {
        public Key {
            Objects.requireNonNull(mode);
            name = fold(Objects.requireNonNull(name));
            credits = Objects.requireNonNull(credits).stream()
                    .map(LibraryBrowse::fold).toList();
        }
    }

    /** An immutable index snapshot; its Track objects retain live library identity. */
    public record Group(Key key, String name, List<String> credits, List<Track> tracks) {
        public Group {
            Objects.requireNonNull(key);
            Objects.requireNonNull(name);
            credits = List.copyOf(credits);
            tracks = List.copyOf(tracks);
        }

        public String summary() {
            String count = tracks.size() + (tracks.size() == 1 ? " song" : " songs");
            return credits.isEmpty() ? count : count + " · " + String.join("; ", credits);
        }

        public boolean matches(String query) {
            String normalized = fold(query.strip());
            return normalized.isEmpty() || fold(name).contains(normalized)
                    || credits.stream().anyMatch(credit -> fold(credit).contains(normalized));
        }
    }

    private static final class Members {
        private final String name;
        private final List<String> credits;
        private final LinkedHashSet<Track> tracks = new LinkedHashSet<>();

        private Members(String name, List<String> credits) {
            this.name = name;
            this.credits = credits;
        }
    }

    public static List<Group> groups(List<Track> tracks, Mode mode) {
        Objects.requireNonNull(tracks);
        Objects.requireNonNull(mode);
        Map<Key, Members> groups = new LinkedHashMap<>();

        for (Track track : tracks) {
            TrackMetadata metadata = track.getMetadata();
            if (mode == Mode.ALBUMS) {
                List<String> credits = metadata.albumArtists().isEmpty()
                        ? metadata.artists() : metadata.albumArtists();
                Key key = new Key(mode, metadata.album(), credits);
                groups.computeIfAbsent(key, ignored -> new Members(metadata.album(), credits))
                        .tracks.add(track);
            } else if (mode == Mode.ARTISTS) {
                // Each stored artist is a group. Combined tag values are not split.
                for (String artist : metadata.artists()) {
                    Key key = new Key(mode, artist, List.of());
                    groups.computeIfAbsent(key, ignored -> new Members(artist, List.of()))
                            .tracks.add(track);
                }
            }
        }

        List<Group> result = new ArrayList<>();
        groups.forEach((key, members) -> result.add(new Group(
                key, members.name, members.credits, List.copyOf(members.tracks)
        )));
        result.sort(Comparator.comparing(Group::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(group -> String.join("; ", group.credits()),
                        String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    /** Simple Unicode case folding, without guessing separators or transliteration. */
    private static String fold(String text) {
        StringBuilder result = new StringBuilder();
        text.codePoints().map(code -> Character.toLowerCase(Character.toUpperCase(code)))
                .forEach(result::appendCodePoint);
        return result.toString();
    }
}
