package com.misplay.service;

import com.misplay.model.LyricLine;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyListProperty;
import javafx.beans.property.ReadOnlyListWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads a selected song's local sidecar. Call public methods on the JavaFX thread. */
public final class LyricsService implements AutoCloseable {
    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private final ReadOnlyListWrapper<LyricLine> lines = new ReadOnlyListWrapper<>(
            FXCollections.unmodifiableObservableList(FXCollections.observableArrayList())
    );
    private final ReadOnlyStringWrapper message =
            new ReadOnlyStringWrapper("Play a song to load its matching .lrc file.");
    private final ReadOnlyBooleanWrapper loading = new ReadOnlyBooleanWrapper();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread worker = new Thread(runnable, "misplay-lyrics");
        worker.setDaemon(true);
        return worker;
    });

    private Path selectedPath;
    private Path pendingPath;
    private long request;
    private Task<LoadResult> activeTask;
    private boolean closed;

    private record LoadResult(List<LyricLine> lines, String message) {}

    public ReadOnlyListProperty<LyricLine> linesProperty() {
        return lines.getReadOnlyProperty();
    }

    public ReadOnlyStringProperty messageProperty() {
        return message.getReadOnlyProperty();
    }

    public ReadOnlyBooleanProperty loadingProperty() {
        return loading.getReadOnlyProperty();
    }

    public void showLyrics(File audioFile) {
        if (closed) {
            return;
        }
        selectedPath = audioFile.toPath().toAbsolutePath().normalize();
        reload();
    }

    public void reload() {
        if (closed || selectedPath == null) {
            return;
        }
        request++;
        pendingPath = selectedPath;
        publishLines(List.of());
        message.set("Loading lyrics...");
        loading.set(true);
        readNext();
    }

    /** Greatest adjusted timestamp at or before the clock; -1 before the first line. */
    public int indexAt(double elapsedMillis) {
        if (!Double.isFinite(elapsedMillis) || elapsedMillis < 0) {
            return -1;
        }
        int low = 0;
        int high = lines.size() - 1;
        int result = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (lines.get(middle).timeMillis() <= elapsedMillis) {
                result = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return result;
    }

    private void readNext() {
        if (closed || activeTask != null || pendingPath == null) {
            return;
        }
        Path audioPath = pendingPath;
        long readingRequest = request;
        pendingPath = null;
        Task<LoadResult> task = new Task<>() {
            @Override
            protected LoadResult call() throws Exception {
                return readSidecar(audioPath);
            }
        };
        activeTask = task;
        task.setOnSucceeded(event -> finish(task, readingRequest, task.getValue()));
        task.setOnFailed(event -> {
            Throwable error = task.getException();
            String detail = error instanceof CharacterCodingException
                    ? "Save the .lrc file as UTF-8, then Reload lyrics."
                    : error instanceof IllegalArgumentException ? error.getMessage()
                    : "Check the matching .lrc file, then Reload lyrics.";
            finish(task, readingRequest, new LoadResult(List.of(), "Lyrics unavailable. " + detail));
        });
        executor.execute(task);
    }

    private void finish(Task<LoadResult> task, long readingRequest, LoadResult result) {
        if (closed || activeTask != task) {
            return;
        }
        activeTask = null;
        if (readingRequest == request) {
            publishLines(result.lines());
            message.set(result.message());
            loading.set(false);
        }
        readNext();
    }

    private void publishLines(List<LyricLine> value) {
        // Replace the whole immutable view; consumers cannot mutate loaded results.
        lines.set(FXCollections.unmodifiableObservableList(FXCollections.observableArrayList(value)));
    }

    private static LoadResult readSidecar(Path audioPath) throws IOException {
        String name = audioPath.getFileName().toString();
        int dot = name.lastIndexOf('.');
        Path sidecar = audioPath.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + ".lrc");
        byte[] bytes;
        try {
            if (!Files.readAttributes(sidecar, BasicFileAttributes.class).isRegularFile()) {
                throw new IOException("Sidecar is not a regular file.");
            }
            try (var input = Files.newInputStream(sidecar)) {
                bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            }
        } catch (NoSuchFileException missing) {
            return new LoadResult(List.of(), "No matching .lrc file. Place "
                    + sidecar.getFileName() + " beside the MP3, then Reload lyrics.");
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("The .lrc file exceeds 1 MiB.");
        }
        String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        List<LyricLine> parsed = LrcParser.parse(text);
        return new LoadResult(parsed, parsed.isEmpty()
                ? "No valid timed lines in " + sidecar.getFileName() + "."
                : "Lyrics: " + sidecar.getFileName());
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        request++;
        pendingPath = null;
        if (activeTask != null) {
            activeTask.cancel(true);
            activeTask = null;
        }
        executor.shutdownNow();
    }
}
