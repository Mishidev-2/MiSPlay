package com.misplay.model;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Immutable text/header data. Null numbers/duration mean that they are unknown. */
public record TrackMetadata(
        String title,
        List<String> artists,
        String album,
        List<String> albumArtists,
        Integer trackNumber,
        Integer discNumber,
        Long durationMillis
) {
    public TrackMetadata {
        title = requireText(title);
        album = requireText(album);
        artists = copyNames(artists);
        albumArtists = copyNames(albumArtists);
        if (artists.isEmpty()) {
            throw new IllegalArgumentException("A track needs an artist or its fallback.");
        }
        if ((trackNumber != null && trackNumber <= 0)
                || (discNumber != null && discNumber <= 0)
                || (durationMillis != null && durationMillis <= 0)) {
            throw new IllegalArgumentException("Known track/disc numbers and duration must be positive.");
        }
    }

    /** Existing loading/error callers still use the three-field fallback form. */
    public TrackMetadata(String title, String artist, String album) {
        this(title, List.of(artist), album, List.of(), null, null, null);
    }

    public String artist() {
        return String.join("; ", artists);
    }

    public String albumArtist() {
        return String.join("; ", albumArtists);
    }

    public String durationText() {
        if (durationMillis == null) {
            return "—";
        }
        long seconds = durationMillis / 1_000;
        return seconds >= 3_600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3_600,
                        (seconds / 60) % 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    public String details() {
        String text = artist() + " — " + album;
        if (discNumber != null) {
            text += " · Disc " + discNumber;
        }
        if (trackNumber != null) {
            text += " · Track " + trackNumber;
        }
        if (durationMillis != null) {
            text += " · " + durationText();
        }
        return text;
    }

    private static String requireText(String value) {
        String text = Objects.requireNonNull(value).strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Metadata text cannot be blank.");
        }
        return text;
    }

    private static List<String> copyNames(List<String> names) {
        return Objects.requireNonNull(names).stream()
                .map(TrackMetadata::requireText).distinct().toList();
    }
}
