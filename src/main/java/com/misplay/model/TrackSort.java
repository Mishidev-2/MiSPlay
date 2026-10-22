package com.misplay.model;

import java.io.File;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** View ordering only. Comparisons use cached metadata and never read a file. */
public enum TrackSort {
    ADDED_ORDER("Added order"),
    PLAYLIST_ORDER("Playlist order"),
    TITLE_ASC("Title A–Z"),
    TITLE_DESC("Title Z–A"),
    ARTIST_ASC("Artist A–Z"),
    ARTIST_DESC("Artist Z–A"),
    ALBUM_ASC("Album A–Z"),
    ALBUM_DESC("Album Z–A");

    private final String label;

    TrackSort(String label) {
        this.label = label;
    }

    /** The canonical file snapshots supply stable ties and both playlist orders. */
    public Comparator<Track> comparator(List<File> addedFiles, List<File> playlistFiles) {
        Map<File, Integer> added = positions(addedFiles);
        Map<File, Integer> manual = positions(playlistFiles);
        Comparator<Track> addition = Comparator.comparingInt(
                track -> added.getOrDefault(track.getFile(), Integer.MAX_VALUE));
        Comparator<Track> title = Comparator.comparing(
                track -> track.getMetadata().title(), String.CASE_INSENSITIVE_ORDER);
        Comparator<Track> artist = Comparator.comparing(
                track -> track.getMetadata().artist(), String.CASE_INSENSITIVE_ORDER);
        Comparator<Track> album = Comparator.comparing(
                track -> track.getMetadata().album(), String.CASE_INSENSITIVE_ORDER);
        Comparator<Track> order = switch (this) {
            case ADDED_ORDER -> addition;
            case PLAYLIST_ORDER -> Comparator.comparingInt(
                    track -> manual.getOrDefault(track.getFile(), Integer.MAX_VALUE));
            case TITLE_ASC -> title;
            case TITLE_DESC -> title.reversed();
            case ARTIST_ASC -> artist.thenComparing(title);
            case ARTIST_DESC -> artist.reversed().thenComparing(title);
            case ALBUM_ASC, ALBUM_DESC -> (this == ALBUM_ASC ? album : album.reversed())
                    .thenComparing(track -> track.getMetadata().albumArtist(),
                            String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(track -> track.getMetadata().discNumber(),
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(track -> track.getMetadata().trackNumber(),
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(title);
        };
        return order.thenComparing(addition);
    }

    private static Map<File, Integer> positions(List<File> files) {
        Map<File, Integer> positions = new HashMap<>();
        for (int index = 0; index < files.size(); index++) {
            positions.putIfAbsent(files.get(index), index);
        }
        return positions;
    }

    @Override
    public String toString() {
        return label;
    }
}
