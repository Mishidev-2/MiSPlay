package com.misplay.model;

import java.io.File;
import java.util.List;

public class PlaybackQueue {
    private List<File> tracks = List.of();
    private int currentIndex = -1;

    public void start(List<File> files, File firstTrack) {
        List<File> snapshot = List.copyOf(files);
        int index = snapshot.indexOf(firstTrack);

        if (index < 0) {
            throw new IllegalArgumentException(
                    "The starting track must be in the queue."
            );
        }

        tracks = snapshot;
        currentIndex = index;
    }

    public boolean hasPrevious() {
        return currentIndex > 0;
    }

    public boolean hasNext() {
        return currentIndex >= 0 && currentIndex < tracks.size() - 1;
    }

    public File previous() {
        if (!hasPrevious()) {
            return null;
        }

        currentIndex--;
        return tracks.get(currentIndex);
    }

    public File next() {
        if (!hasNext()) {
            return null;
        }

        currentIndex++;
        return tracks.get(currentIndex);
    }
}