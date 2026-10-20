package com.misplay.service;

import com.misplay.model.FolderScanResult;
import javafx.concurrent.Task;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class FolderScanTask extends Task<FolderScanResult> {
    private final Path root;

    // Used only by the scanner's background thread.
    private final List<File> foundFiles = new ArrayList<>();
    private int checkedFiles;
    private int skippedEntries;

    public FolderScanTask(File folder) {
        root = folder.toPath().toAbsolutePath().normalize();
    }

    @Override
    protected FolderScanResult call() throws IOException {
        BasicFileAttributes rootAttributes = Files.readAttributes(
                root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
        );

        if (!rootAttributes.isDirectory()) {
            throw new IOException("Not a regular directory: " + root);
        }

        updateMessage("Scanning for MP3 files...");

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(
                    Path directory, BasicFileAttributes attributes
            ) {
                return isCancelled()
                        ? FileVisitResult.TERMINATE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(
                    Path file, BasicFileAttributes attributes
            ) {
                if (isCancelled()) {
                    return FileVisitResult.TERMINATE;
                }

                checkedFiles++;

                if (attributes.isRegularFile()
                        && file.getFileName().toString()
                        .toLowerCase(Locale.ROOT).endsWith(".mp3")) {
                    foundFiles.add(file.toFile());
                }

                if (checkedFiles % 100 == 0) {
                    reportProgress();
                }

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(
                    Path file, IOException error
            ) throws IOException {
                if (isCancelled()) {
                    return FileVisitResult.TERMINATE;
                }

                if (file.equals(root)) {
                    throw error;
                }

                recordSkippedEntry(file, error);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(
                    Path directory, IOException error
            ) throws IOException {
                if (isCancelled()) {
                    return FileVisitResult.TERMINATE;
                }

                if (error != null) {
                    if (directory.equals(root)) {
                        throw error;
                    }

                    recordSkippedEntry(directory, error);
                }

                return FileVisitResult.CONTINUE;
            }
        });

        if (isCancelled()) {
            return null;
        }

        reportProgress();
        return new FolderScanResult(foundFiles, skippedEntries);
    }

    private void reportProgress() {
        updateMessage(
                "Checked " + checkedFiles + " files; found "
                        + foundFiles.size() + " MP3s."
        );
    }

    private void recordSkippedEntry(Path path, IOException error) {
        skippedEntries++;

        System.err.println(
                "Folder scan skipped " + path + ": " + error
        );

        reportProgress();
    }
}