package com.misplay.service;

import javafx.scene.media.Media;
import javafx.scene.media.MediaException;
import javafx.scene.media.MediaPlayer;

import java.io.File;
import java.util.function.Consumer;

public class AudioService {

    private MediaPlayer player;
    private final Consumer<String> errorHandler;

    public AudioService(Consumer<String> errorHandler) {
        this.errorHandler = errorHandler;
    }

    public void loadAndPlay(File file) {
        dispose();

        try {
            Media media = new Media(file.toURI().toString());
            MediaPlayer nextPlayer = new MediaPlayer(media);
            player = nextPlayer;

            media.setOnError(() ->
                    handleError(nextPlayer, media.getError()));

            nextPlayer.setOnError(() ->
                    handleError(nextPlayer, nextPlayer.getError()));

            nextPlayer.setOnEndOfMedia(nextPlayer::stop);
            nextPlayer.setVolume(0.5);
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
        if (player != null) {
            MediaPlayer previousPlayer = player;
            player = null;
            previousPlayer.dispose();
        }
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