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

        HBox controls = new HBox(10, openButton, playButton, pauseButton);
        controls.setAlignment(Pos.CENTER);

        VBox root = new VBox(15, trackLabel, controls, errorLabel);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(20));

        stage.setTitle("MiSPlay");
        stage.setScene(new Scene(root, 640, 360));
        stage.show();
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