package com.misplay.model;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;

import java.io.File;
import java.util.Objects;

public final class Track {
    private final File file;
    private final ReadOnlyObjectWrapper<TrackMetadata> metadata;
    private boolean metadataLoaded;

    public Track(File file) {
        this.file = Objects.requireNonNull(file);

        metadata = new ReadOnlyObjectWrapper<>(new TrackMetadata(
                file.getName(),
                "Unknown artist",
                "Unknown album"
        ));
    }

    public File getFile() {
        return file;
    }

    public TrackMetadata getMetadata() {
        return metadata.get();
    }

    public ReadOnlyObjectProperty<TrackMetadata> metadataProperty() {
        return metadata.getReadOnlyProperty();
    }

    public boolean isMetadataLoaded() {
        return metadataLoaded;
    }

    public void setMetadata(TrackMetadata value) {
        TrackMetadata checkedValue = Objects.requireNonNull(value);
        metadataLoaded = true;
        metadata.set(checkedValue);
    }
}