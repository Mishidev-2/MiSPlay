package com.misplay.service;

import com.misplay.model.LyricLine;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Call export/close on JavaFX. Accepted file writes finish on normal close. */
public final class LyricsExportService implements AutoCloseable {
    private static final int MAX_BYTES = 1024 * 1024;

    /** Captured before opening a dialog; later reloads/track changes cannot alter it. */
    public record Snapshot(Path audioPath, List<LyricLine> timedLines, List<String> plainLines) {
        public Snapshot {
            audioPath = Objects.requireNonNull(audioPath, "audioPath").toAbsolutePath().normalize();
            timedLines = List.copyOf(timedLines);
            plainLines = List.copyOf(plainLines);
            if (timedLines.isEmpty() == plainLines.isEmpty()) {
                throw new IllegalArgumentException("Choose one nonempty lyric format to export.");
            }
        }

        public boolean isTimed() {
            return !timedLines.isEmpty();
        }

        public String extension() {
            return isTimed() ? ".lrc" : ".txt";
        }

        public String suggestedFileName() {
            String name = audioPath.getFileName().toString();
            int dot = name.lastIndexOf('.');
            return (dot > 0 ? name.substring(0, dot) : name) + extension();
        }
    }

    private final ReadOnlyBooleanWrapper saving = new ReadOnlyBooleanWrapper();
    private final ReadOnlyStringWrapper message = new ReadOnlyStringWrapper("");
    private final ExecutorService executor = Executors.newSingleThreadExecutor(action -> {
        Thread worker = new Thread(action, "misplay-lyrics-export");
        worker.setDaemon(false);
        return worker;
    });
    private Task<Path> activeTask;
    private boolean closed;

    public ReadOnlyBooleanProperty savingProperty() {
        return saving.getReadOnlyProperty();
    }

    public ReadOnlyStringProperty messageProperty() {
        return message.getReadOnlyProperty();
    }

    /** Pure filename validation; do this before checking/confirming an existing target. */
    public static Path targetPath(Snapshot snapshot, File chosen) {
        Path path = chosen.toPath().toAbsolutePath().normalize();
        if (path.getFileName() == null) {
            throw new IllegalArgumentException("Choose a lyric filename.");
        }
        String name = path.getFileName().toString();
        if (name.lastIndexOf('.') <= 0) {
            path = path.resolveSibling(name + snapshot.extension());
        } else if (!name.toLowerCase(Locale.ROOT).endsWith(snapshot.extension())) {
            throw new IllegalArgumentException("Use a " + snapshot.extension()
                    + " filename for these lyrics.");
        }
        return path;
    }

    public void export(Snapshot snapshot, File chosen, boolean replaceExisting) {
        if (closed || activeTask != null) {
            throw new IllegalStateException("Another export is active or export has closed.");
        }
        Objects.requireNonNull(snapshot, "snapshot");
        Path target = targetPath(snapshot, Objects.requireNonNull(chosen, "chosen"));
        Task<Path> task = new Task<>() {
            @Override
            protected Path call() throws Exception {
                try {
                    return write(snapshot, target, replaceExisting);
                } catch (Exception error) {
                    // The UI may already have closed while the accepted write finishes.
                    System.err.println("Lyrics export failed: " + error);
                    throw error;
                }
            }
        };
        activeTask = task;
        saving.set(true);
        message.set("Exporting " + target.getFileName() + "...");
        task.setOnSucceeded(event -> finish(task, "Exported " + task.getValue().getFileName() + "."));
        task.setOnFailed(event -> {
            Throwable error = task.getException();
            String detail = error instanceof FileAlreadyExistsException
                    ? "The file already exists. Export again to choose replacement or another name."
                    : error instanceof ExportException ? error.getMessage()
                    : "The destination could not be written. Choose another location and try again.";
            finish(task, "Export failed. " + detail);
        });
        executor.execute(task);
    }

    private void finish(Task<Path> task, String text) {
        if (!closed && activeTask == task) {
            activeTask = null;
            saving.set(false);
            message.set(text);
        }
    }

    private static Path write(Snapshot snapshot, Path target, boolean replaceExisting) throws IOException {
        if (target.equals(snapshot.audioPath())
                || (Files.exists(target) && Files.exists(snapshot.audioPath())
                        && Files.isSameFile(target, snapshot.audioPath()))) {
            throw new ExportException("Choose a destination different from the music file.");
        }
        checkTarget(target, replaceExisting);
        Path temporary = Files.createTempFile(target.getParent(), ".misplay-lyrics-", ".tmp");
        try {
            var encoder = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try (var writer = new BufferedWriter(new OutputStreamWriter(
                    new BoundedOutputStream(Files.newOutputStream(temporary)), encoder))) {
                writeContent(snapshot, writer);
            }
            // Recheck after writing; a destination may have appeared while it was prepared.
            checkTarget(target, replaceExisting);
            if (replaceExisting) {
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                // ATOMIC_MOVE alone may replace an existing target on some providers.
                Files.move(temporary, target);
            }
            return target;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void checkTarget(Path target, boolean replaceExisting) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            var attributes = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                throw new ExportException("Choose a regular lyric file, not a directory or symbolic link.");
            }
            if (!replaceExisting) {
                throw new FileAlreadyExistsException(target.toString());
            }
        }
    }

    private static void writeContent(Snapshot snapshot, BufferedWriter writer) throws IOException {
        int entries = 0;
        if (snapshot.isTimed()) {
            for (LyricLine line : snapshot.timedLines()) {
                long minutes = line.timeMillis() / 60_000;
                if (minutes > 999_999) {
                    throw new ExportException("A timestamp exceeds the supported LRC minute range.");
                }
                String stamp = String.format(Locale.ROOT, "[%02d:%02d.%03d]", minutes,
                        (line.timeMillis() / 1000) % 60, line.timeMillis() % 1000);
                // Each translation/line in a simultaneous group receives the same time.
                int start = 0;
                do {
                    int newline = line.text().indexOf('\n', start);
                    int end = newline < 0 ? line.text().length() : newline;
                    if (++entries > LrcParser.MAX_CUES || stamp.length() + end - start > LrcParser.MAX_LINE_LENGTH) {
                        throw new ExportException("Lyrics exceed the LRC line or 10,000-entry limit.");
                    }
                    writer.write(stamp);
                    writer.write(line.text(), start, end - start);
                    writer.write('\n');
                    if (newline < 0) {
                        break;
                    }
                    start = newline + 1;
                } while (true);
            }
        } else {
            for (String line : snapshot.plainLines()) {
                if (++entries > LrcParser.MAX_CUES || line.length() > LrcParser.MAX_LINE_LENGTH) {
                    throw new ExportException("Plain lyrics exceed the line or 10,000-entry limit.");
                }
                writer.write(line);
                writer.write('\n');
            }
        }
    }

    private static final class ExportException extends IOException {
        private ExportException(String text) {
            super(text);
        }
    }

    private static final class BoundedOutputStream extends FilterOutputStream {
        private int bytes;

        private BoundedOutputStream(OutputStream output) {
            super(output);
        }

        @Override
        public void write(int value) throws IOException {
            if (bytes >= MAX_BYTES) {
                throw new ExportException("The export exceeds 1 MiB; no partial lyric file was saved.");
            }
            out.write(value);
            bytes++;
        }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            if (length > MAX_BYTES - bytes) {
                throw new ExportException("The export exceeds 1 MiB; no partial lyric file was saved.");
            }
            out.write(data, offset, length);
            bytes += length;
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            // One accepted export drains without blocking the JavaFX thread.
            executor.shutdown();
        }
    }
}
