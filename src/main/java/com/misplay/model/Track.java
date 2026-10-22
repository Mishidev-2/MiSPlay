package com.misplay.model;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;

import java.io.File;
import java.util.Objects;
import java.util.Locale;

public final class Track {
    private final File file;
    private final ReadOnlyObjectWrapper<TrackMetadata> metadata;
    private final ReadOnlyObjectWrapper<MetadataStatus> metadataStatus =
            new ReadOnlyObjectWrapper<>(MetadataStatus.PENDING);

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

    public MetadataStatus getMetadataStatus() {
        return metadataStatus.get();
    }

    public ReadOnlyObjectProperty<MetadataStatus> metadataStatusProperty() {
        return metadataStatus.getReadOnlyProperty();
    }

    public void markMetadataLoading() {
        metadataStatus.set(MetadataStatus.LOADING);
    }

    public void markMetadataFailed() {
        metadataStatus.set(MetadataStatus.FAILED);
    }

    public boolean isMetadataLoaded() {
        return getMetadataStatus() == MetadataStatus.LOADED;
    }

    public void setMetadata(TrackMetadata value) {
        TrackMetadata checkedValue = Objects.requireNonNull(value);

        metadataStatus.set(MetadataStatus.LOADED);
        metadata.set(checkedValue);
    }

    public boolean matchesSearch(String normalizedQuery) {
        TrackMetadata values = getMetadata();

        return normalizedQuery.isEmpty()
                || file.getName().toLowerCase(Locale.ROOT)
                .contains(normalizedQuery)
                || values.title().toLowerCase(Locale.ROOT)
                .contains(normalizedQuery)
                || values.artist().toLowerCase(Locale.ROOT)
                .contains(normalizedQuery)
                || values.album().toLowerCase(Locale.ROOT)
                .contains(normalizedQuery)
                || values.albumArtist().toLowerCase(Locale.ROOT)
                .contains(normalizedQuery);
    }

}