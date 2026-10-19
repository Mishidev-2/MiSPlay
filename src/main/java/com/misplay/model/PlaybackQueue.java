package com.misplay.model;

import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.ArrayList;
import java.util.Collections;

public class PlaybackQueue {
    private List<File> originalTracks = List.of();
    private List<File> tracks = List.of();
    private int currentIndex = -1;
    private RepeatMode repeatMode = RepeatMode.OFF;
    private boolean shuffleEnabled;

    public RepeatMode getRepeatMode() {
        return repeatMode;
    }

    public void setRepeatMode(RepeatMode mode) {
        repeatMode = Objects.requireNonNull(mode);
    }

    public boolean isShuffleEnabled() {
        return shuffleEnabled;
    }

    public void setShuffleEnabled(boolean enabled) {
        if (shuffleEnabled == enabled) {
            return;
        }

        shuffleEnabled = enabled;

        if (currentIndex < 0) {
            return;
        }

        if (enabled) {
            shuffleUpcoming();
        } else {
            File currentTrack = tracks.get(currentIndex);
            tracks = originalTracks;
            currentIndex = tracks.indexOf(currentTrack);
        }
    }

    private void shuffleUpcoming() {
        List<File> order = new ArrayList<>(tracks);

        Collections.shuffle(
                order.subList(currentIndex + 1, order.size())
        );

        tracks = List.copyOf(order);
    }

    public void start(List<File> files, File firstTrack) {
        List<File> snapshot = List.copyOf(files);
        int index = snapshot.indexOf(firstTrack);

        if (index < 0) {
            throw new IllegalArgumentException(
                    "The starting track must be in the queue."
            );
        }

        originalTracks = snapshot;

        if (shuffleEnabled) {
            List<File> order = new ArrayList<>(snapshot);

            File selectedTrack = order.remove(index);
            Collections.shuffle(order);
            order.add(0, selectedTrack);

            tracks = List.copyOf(order);
            currentIndex = 0;
        } else {
            tracks = snapshot;
            currentIndex = index;
        }
    }

    public boolean hasPrevious() {
        return currentIndex >= 0
                && tracks.size() > 1
                && (currentIndex > 0 || repeatMode == RepeatMode.ALL);
    }

    public boolean hasNext() {
        return currentIndex >= 0
                && tracks.size() > 1
                && (currentIndex < tracks.size() - 1
                || repeatMode == RepeatMode.ALL);
    }

    public File previous() {
        if (!hasPrevious()) {
            return null;
        }

        currentIndex = currentIndex == 0
                ? tracks.size() - 1
                : currentIndex - 1;

        return tracks.get(currentIndex);
    }

    public File next() {
        if (!hasNext()) {
            return null;
        }

        currentIndex = (currentIndex + 1) % tracks.size();
        return tracks.get(currentIndex);
    }

    public File nextAfterCompletion() {
        if (currentIndex < 0) {
            return null;
        }

        if (repeatMode == RepeatMode.ONE
                || (repeatMode == RepeatMode.ALL && tracks.size() == 1)) {
            return tracks.get(currentIndex);
        }

        return next();
    }
}