package com.misplay.model;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** A session-only queue. IDs distinguish repeated occurrences of the same file. */
public final class PlaybackQueue {
    public record Entry(long id, File file) {
        public Entry {
            Objects.requireNonNull(file);
        }
    }

    private long nextId;
    private List<Entry> originalTracks = List.of();
    private List<Entry> tracks = List.of();
    private int currentIndex = -1;
    private RepeatMode repeatMode = RepeatMode.OFF;
    private boolean shuffleEnabled;

    public RepeatMode getRepeatMode() { return repeatMode; }
    public void setRepeatMode(RepeatMode mode) { repeatMode = Objects.requireNonNull(mode); }
    public boolean isShuffleEnabled() { return shuffleEnabled; }
    public List<Entry> entries() { return tracks; }
    public Entry current() { return currentIndex < 0 ? null : tracks.get(currentIndex); }
    public int currentIndex() { return currentIndex; }

    public void setShuffleEnabled(boolean enabled) {
        if (shuffleEnabled == enabled) return;
        shuffleEnabled = enabled;
        if (currentIndex < 0) return;
        long currentId = current().id();
        if (enabled) {
            // Preserve already-played occurrences and the current song. Only the
            // upcoming portion is randomized; repeated toggles must not rely on
            // a shuffle that can randomly reproduce the identical order.
            List<Entry> order = new ArrayList<>(tracks);
            List<Entry> upcoming = order.subList(currentIndex + 1, order.size());
            if (upcoming.size() > 1) {
                List<Entry> previousOrder = List.copyOf(upcoming);
                Collections.shuffle(upcoming);
                if (upcoming.equals(previousOrder)) Collections.rotate(upcoming, 1);
            }
            tracks = List.copyOf(order);
        } else {
            tracks = originalTracks;
            currentIndex = indexOf(tracks, currentId);
        }
    }

    public void start(List<File> files, File firstTrack) {
        List<File> snapshot = List.copyOf(files);
        int index = snapshot.indexOf(Objects.requireNonNull(firstTrack));
        if (index < 0) throw new IllegalArgumentException("The starting track must be in the queue.");
        List<Entry> entries = new ArrayList<>();
        for (File file : snapshot) entries.add(new Entry(++nextId, file));
        originalTracks = List.copyOf(entries);
        if (shuffleEnabled) {
            Entry selected = entries.remove(index);
            Collections.shuffle(entries);
            entries.add(0, selected);
            currentIndex = 0;
        } else {
            currentIndex = index;
        }
        tracks = List.copyOf(entries);
    }

    /** Appends even when idle. The first queued song can be started explicitly. */
    public List<Entry> addToEnd(List<File> files) { return insert(files, false); }
    public List<Entry> addNext(List<File> files) { return insert(files, true); }

    private List<Entry> insert(List<File> files, boolean next) {
        if (files.isEmpty()) return List.of();
        List<Entry> added = new ArrayList<>();
        for (File file : files) added.add(new Entry(++nextId, Objects.requireNonNull(file)));
        List<Entry> order = new ArrayList<>(tracks);
        List<Entry> original = new ArrayList<>(originalTracks);
        int target = next && currentIndex >= 0 ? currentIndex + 1 : order.size();
        order.addAll(target, added);
        int originalTarget = next && currentIndex >= 0
                ? indexOf(original, current().id()) + 1 : original.size();
        original.addAll(originalTarget, added);
        tracks = List.copyOf(order);
        originalTracks = List.copyOf(original);
        return List.copyOf(added);
    }

    public File play(Entry entry) {
        Objects.requireNonNull(entry);
        int index = indexOf(tracks, entry.id());
        if (index < 0) return null;
        if (currentIndex < 0 && shuffleEnabled) {
            Entry selected = tracks.get(index);
            List<Entry> order = new ArrayList<>(tracks);
            order.remove(index);
            Collections.shuffle(order);
            order.add(0, selected);
            tracks = List.copyOf(order);
            currentIndex = 0;
        } else {
            currentIndex = index;
        }
        return tracks.get(currentIndex).file();
    }

    /** Keeps the playing entry/audio intact. */
    public boolean remove(Entry entry) {
        Objects.requireNonNull(entry);
        int index = indexOf(tracks, entry.id());
        if (index < 0 || index == currentIndex) return false;
        List<Entry> order = new ArrayList<>(tracks);
        order.remove(index);
        tracks = List.copyOf(order);
        originalTracks = originalTracks.stream().filter(e -> e.id() != entry.id()).toList();
        if (index < currentIndex) currentIndex--;
        return true;
    }

    public int clearUpcoming() {
        if (currentIndex < 0) {
            int count = tracks.size();
            tracks = List.of();
            originalTracks = List.of();
            return count;
        }
        int count = tracks.size() - currentIndex - 1;
        var removed = tracks.subList(currentIndex + 1, tracks.size())
                .stream().map(Entry::id).collect(java.util.stream.Collectors.toSet());
        tracks = List.copyOf(tracks.subList(0, currentIndex + 1));
        originalTracks = originalTracks.stream().filter(e -> !removed.contains(e.id())).toList();
        return count;
    }

    /** Only an upcoming entry may move; editing commits the shown order as the base order. */
    public boolean moveUpcoming(Entry entry, int direction) {
        Objects.requireNonNull(entry);
        if (direction != -1 && direction != 1) throw new IllegalArgumentException("Direction must be -1 or 1.");
        int index = indexOf(tracks, entry.id());
        int destination = index + direction;
        if (index < 0 || index <= currentIndex || destination <= currentIndex
                || destination < 0 || destination >= tracks.size()) return false;
        List<Entry> order = new ArrayList<>(tracks);
        Collections.swap(order, index, destination);
        tracks = List.copyOf(order);
        originalTracks = tracks;
        return true;
    }

    public boolean hasPrevious() {
        return currentIndex >= 0 && tracks.size() > 1
                && (currentIndex > 0 || repeatMode == RepeatMode.ALL);
    }
    public boolean hasNext() {
        return currentIndex >= 0 && tracks.size() > 1
                && (currentIndex < tracks.size() - 1 || repeatMode == RepeatMode.ALL);
    }
    public File previous() {
        if (!hasPrevious()) return null;
        currentIndex = currentIndex == 0 ? tracks.size() - 1 : currentIndex - 1;
        return current().file();
    }
    public File next() {
        if (!hasNext()) return null;
        currentIndex = (currentIndex + 1) % tracks.size();
        return current().file();
    }
    public File nextAfterCompletion() {
        if (currentIndex < 0) return null;
        if (repeatMode == RepeatMode.ONE || (repeatMode == RepeatMode.ALL && tracks.size() == 1))
            return current().file();
        return next();
    }
    private static int indexOf(List<Entry> entries, long id) {
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).id() == id) return i;
        return -1;
    }
}
