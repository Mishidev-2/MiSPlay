package com.misplay.model;

import java.util.Objects;

/** One line (or simultaneous group of lines), at its adjusted playback time. */
public record LyricLine(long timeMillis, String text) {
    public LyricLine {
        if (timeMillis < 0) {
            throw new IllegalArgumentException("Lyric time must not be negative.");
        }
        Objects.requireNonNull(text, "text");
    }
}
