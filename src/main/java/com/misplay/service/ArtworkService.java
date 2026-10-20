package com.misplay.service;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads only requested covers. Call public methods on the JavaFX thread. */
public final class ArtworkService implements AutoCloseable {
    private static final int COVER_SIZE = 192;
    private static final int CACHE_LIMIT = 8;
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;

    private final ReadOnlyObjectWrapper<Image> image =
            new ReadOnlyObjectWrapper<>();
    private final ReadOnlyStringWrapper message =
            new ReadOnlyStringWrapper("No song selected");

    // Access order makes the least recently used entry the first entry.
    // Optional.empty() remembers that a file has no embedded cover.
    private final Map<Path, Optional<Image>> cache =
            new LinkedHashMap<>(16, 0.75f, true);

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread worker = new Thread(runnable, "misplay-artwork");
                worker.setDaemon(true);
                return worker;
            });

    private Path selectedPath;
    private Path pendingPath;
    private Path readingPath;
    private Task<Image> activeTask;
    private boolean closed;

    public ReadOnlyObjectProperty<Image> imageProperty() {
        return image.getReadOnlyProperty();
    }

    public ReadOnlyStringProperty messageProperty() {
        return message.getReadOnlyProperty();
    }

    public void showArtwork(File file) {
        if (closed) {
            return;
        }

        Path path = file.toPath().toAbsolutePath().normalize();
        selectedPath = path;
        pendingPath = null;
        image.set(null);

        Optional<Image> cached = cache.get(path);

        if (cached != null) {
            display(cached.orElse(null));
            return;
        }

        message.set("Loading cover...");

        if (!path.equals(readingPath)) {
            // Rapid skipping replaces this one waiting request.
            pendingPath = path;
        }

        readNext();
    }

    private void readNext() {
        if (closed || activeTask != null || pendingPath == null) {
            return;
        }

        Path path = pendingPath;
        pendingPath = null;

        Task<Image> task = new Task<>() {
            @Override
            protected Image call() throws Exception {
                return readArtwork(path.toFile());
            }
        };

        activeTask = task;
        readingPath = path;

        task.setOnSucceeded(event -> {
            if (closed || activeTask != task) {
                return;
            }

            Image result = task.getValue();
            cache.put(path, Optional.ofNullable(result));

            while (cache.size() > CACHE_LIMIT) {
                cache.remove(cache.keySet().iterator().next());
            }

            if (path.equals(selectedPath)) {
                display(result);
            }

            finishRead();
        });

        task.setOnFailed(event -> {
            if (closed || activeTask != task) {
                return;
            }

            System.err.println(
                    "Artwork read failed for " + path
                            + ": " + task.getException()
            );

            if (path.equals(selectedPath)) {
                image.set(null);
                message.set("Cover unavailable");
            }

            finishRead();
        });

        executor.execute(task);
    }

    private void display(Image value) {
        message.set(value == null ? "No embedded cover" : "");
        image.set(value);
    }

    private void finishRead() {
        activeTask = null;
        readingPath = null;
        readNext();
    }

    private static Image readArtwork(File file) throws Exception {
        Tag tag = AudioFileIO.read(file).getTag();

        if (tag == null) {
            return null;
        }

        Artwork artwork = tag.getFirstArtwork();

        if (artwork == null || artwork.isLinked()) {
            return null;
        }

        byte[] data = artwork.getBinaryData();

        if (data == null || data.length == 0) {
            return null;
        }

        if (data.length > MAX_IMAGE_BYTES) {
            throw new IOException("Embedded cover exceeds 8 MiB.");
        }

        Image result;

        try (ByteArrayInputStream input = new ByteArrayInputStream(data)) {
            result = new Image(input, COVER_SIZE, COVER_SIZE, true, true);
        }

        if (result.isError() || result.getPixelReader() == null) {
            throw new IOException(
                    "Could not decode embedded cover.",
                    result.getException()
            );
        }

        // Copy only resized pixels. The decoded Image can retain its input
        // stream, so caching it directly could also retain the original bytes.
        return new WritableImage(
                result.getPixelReader(),
                (int) result.getWidth(),
                (int) result.getHeight()
        );
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        closed = true;
        selectedPath = null;
        pendingPath = null;
        readingPath = null;

        Task<Image> task = activeTask;
        activeTask = null;

        if (task != null) {
            task.cancel();
        }

        executor.shutdownNow();
        cache.clear();
        image.set(null);
        message.set("No song selected");
    }
}
