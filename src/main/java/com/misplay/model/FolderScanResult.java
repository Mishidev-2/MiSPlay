package com.misplay.model;

import java.io.File;
import java.util.List;

public record FolderScanResult(List<File> files, int skippedEntries) {
    public FolderScanResult {
        files = List.copyOf(files);
    }
}