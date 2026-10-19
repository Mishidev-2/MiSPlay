package com.misplay;

import com.misplay.service.AudioService;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.scene.control.Slider;

import javafx.beans.binding.Bindings;
import javafx.util.Duration;

import java.io.File;

public class MiSPlayApp extends Application {

    private AudioService audioService;

    @Override
    public void start(Stage stage) {
        Label trackLabel = new Label("Open an MP3 to begin.");
        Label errorLabel = new Label();
        errorLabel.setWrapText(true);

        Button openButton = new Button("Open MP3");
        Button playButton = new Button("Play");
        Button pauseButton = new Button("Pause");

        playButton.setDisable(true);
        pauseButton.setDisable(true);

        audioService = new AudioService(message -> {
            errorLabel.setText(message);
            playButton.setDisable(true);
            pauseButton.setDisable(true);
        });

        Label timeLabel = new Label();

        timeLabel.textProperty().bind(
                Bindings.createStringBinding(
                        () -> formatTime(audioService.currentTimeProperty().get())
                                + " / "
                                + formatTime(audioService.durationProperty().get()),
                        audioService.currentTimeProperty(),
                        audioService.durationProperty()
                )
        );

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open an MP3");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("MP3 audio", "*.mp3")
        );

        openButton.setOnAction(event -> {
            File selectedFile = chooser.showOpenDialog(stage);

            if (selectedFile == null) {
                return;
            }

            trackLabel.setText(selectedFile.getName());
            errorLabel.setText("");

            playButton.setDisable(false);
            pauseButton.setDisable(false);

            audioService.loadAndPlay(selectedFile);
        });

        playButton.setOnAction(event -> audioService.play());
        pauseButton.setOnAction(event -> audioService.pause());

        Slider volumeSlider = new Slider(
                0,
                100,
                audioService.getVolume() * 100
        );
        volumeSlider.setPrefWidth(220);

        Label volumeValue = new Label(
                Math.round(volumeSlider.getValue()) + "%"
        );
        volumeValue.setMinWidth(45);

        volumeSlider.valueProperty().addListener(
                (observable, oldValue, newValue) -> {
                    double percentage = newValue.doubleValue();

                    audioService.setVolume(percentage / 100.0);
                    volumeValue.setText(Math.round(percentage) + "%");
                }
        );

        HBox volumeControls = new HBox(
                10,
                new Label("Volume"),
                volumeSlider,
                volumeValue
        );
        volumeControls.setAlignment(Pos.CENTER);

        HBox controls = new HBox(10, openButton, playButton, pauseButton);
        controls.setAlignment(Pos.CENTER);

        VBox root = new VBox(
                15,
                trackLabel,
                timeLabel,
                controls,
                volumeControls,
                errorLabel
        );
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(20));

        stage.setTitle("MiSPlay");
        stage.setScene(new Scene(root, 640, 360));
        stage.show();
    }

    private static String formatTime(Duration time) {
        if (time == null
                || time.isUnknown()
                || time.isIndefinite()
                || time.lessThan(Duration.ZERO)) {
            return "--:--";
        }

        long totalSeconds = (long) time.toSeconds();
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }

        return String.format("%d:%02d", minutes, seconds);
    }

    @Override
    public void stop() {
        if (audioService != null) {
            audioService.dispose();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}