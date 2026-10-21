package com.misplay.model;

import java.util.Objects;

/** User choices only: this does not contain a queue or playback position. */
public record PlaybackPreferences(
        double volume,
        RepeatMode repeatMode,
        boolean shuffleEnabled
) {
    public PlaybackPreferences {
        if (!Double.isFinite(volume) || volume < 0.0 || volume > 1.0) {
            throw new IllegalArgumentException("Volume must be between 0.0 and 1.0.");
        }

        Objects.requireNonNull(repeatMode);
    }

    public static PlaybackPreferences defaults() {
        return new PlaybackPreferences(0.5, RepeatMode.OFF, false);
    }
}
