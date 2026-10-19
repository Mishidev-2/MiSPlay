package com.misplay.service;

import javafx.scene.media.Media;
import javafx.scene.media.MediaException;
import javafx.scene.media.MediaPlayer;

import java.io.File;
import java.util.function.Consumer;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.util.Duration;

public class AudioService {

    private MediaPlayer player;
    private final Consumer<String> errorHandler;
    private double volume = 0.5;
    private final ReadOnlyObjectWrapper<Duration> currentTime =
            new ReadOnlyObjectWrapper<>(Duration.ZERO);

    private final ReadOnlyObjectWrapper<Duration> duration =
            new ReadOnlyObjectWrapper<>(Duration.UNKNOWN);

    public AudioService(Consumer<String> errorHandler) {
        this.errorHandler = errorHandler;
    }

    public void loadAndPlay(File file) {
        dispose();

        try {
            Media media = new Media(file.toURI().toString());
            MediaPlayer nextPlayer = new MediaPlayer(media);
            player = nextPlayer;

            currentTime.bind(nextPlayer.currentTimeProperty());
            duration.bind(media.durationProperty());

            media.setOnError(() ->
                    handleError(nextPlayer, media.getError()));

            nextPlayer.setOnError(() ->
                    handleError(nextPlayer, nextPlayer.getError()));

            nextPlayer.setOnEndOfMedia(nextPlayer::stop);
            nextPlayer.setVolume(volume);
            nextPlayer.play();

        } catch (RuntimeException error) {
            dispose();
            errorHandler.accept("Cannot open audio: " + error.getMessage());
        }
    }

    public void play() {
        if (player != null) {
            player.play();
        }
    }

    public void pause() {
        if (player != null) {
            player.pause();
        }
    }

    public void dispose() {
        MediaPlayer previousPlayer = player;
        player = null;

        currentTime.unbind();
        duration.unbind();

        currentTime.set(Duration.ZERO);
        duration.set(Duration.UNKNOWN);

        if (previousPlayer != null) {
            previousPlayer.dispose();
        }
    }

    public double getVolume() {
        return volume;
    }

    public void setVolume(double newVolume) {
        volume = Math.max(0.0, Math.min(1.0, newVolume));

        if (player != null) {
            player.setVolume(volume);
        }
    }

    public ReadOnlyObjectProperty<Duration> currentTimeProperty() {
        return currentTime.getReadOnlyProperty();
    }

    public ReadOnlyObjectProperty<Duration> durationProperty() {
        return duration.getReadOnlyProperty();
    }

    private void handleError(MediaPlayer source, MediaException error) {
        // Ignore errors from a player we have already replaced.
        if (source != player) {
            return;
        }

        dispose();

        String message = error == null
                ? "Playback failed."
                : "Playback error: " + error.getMessage();

        errorHandler.accept(message);
    }
}