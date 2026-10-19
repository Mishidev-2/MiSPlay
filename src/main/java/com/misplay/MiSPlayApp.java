package com.misplay;

import com.misplay.model.PlaybackQueue;
import com.misplay.service.AudioService;
import com.misplay.model.RepeatMode;
import com.misplay.service.LibraryService;

import javafx.scene.Scene;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;

import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Priority;

import java.util.List;
import java.util.function.Consumer;
import java.util.Locale;

import javafx.collections.transformation.FilteredList;

import javafx.application.Application;

import javafx.geometry.Insets;
import javafx.geometry.Pos;

import javafx.stage.FileChooser;
import javafx.stage.Stage;

import javafx.beans.binding.Bindings;
import javafx.util.Duration;

import java.io.File;

public class MiSPlayApp extends Application {

    private AudioService audioService;
    private boolean updatingSeekSlider;
    private final LibraryService libraryService = new LibraryService();
    private final PlaybackQueue playbackQueue = new PlaybackQueue();

    @Override
    public void start(Stage stage) {
        Label trackLabel = new Label("Open an MP3 to begin.");
        Label errorLabel = new Label();
        errorLabel.setWrapText(true);

        Button openButton = new Button("Open MP3");
        Button addButton = new Button("Add MP3s");
        Button playButton = new Button("Play");
        Button pauseButton = new Button("Pause");
        Button previousButton = new Button("Previous");
        Button nextButton = new Button("Next");
        Button repeatButton = new Button();
        ToggleButton shuffleButton = new ToggleButton();

        Runnable updateQueueButtons = () -> {
            previousButton.setDisable(!playbackQueue.hasPrevious());
            nextButton.setDisable(!playbackQueue.hasNext());

            repeatButton.setText(
                    "Repeat: " + playbackQueue.getRepeatMode()
            );

            shuffleButton.setSelected(playbackQueue.isShuffleEnabled());

            shuffleButton.setText(
                    playbackQueue.isShuffleEnabled()
                            ? "Shuffle: ON"
                            : "Shuffle: OFF"
            );
        };

        updateQueueButtons.run();

        shuffleButton.setOnAction(event -> {
            playbackQueue.setShuffleEnabled(shuffleButton.isSelected());
            updateQueueButtons.run();
        });

        repeatButton.setOnAction(event -> {
            RepeatMode mode = switch (playbackQueue.getRepeatMode()) {
                case OFF -> RepeatMode.ALL;
                case ALL -> RepeatMode.ONE;
                case ONE -> RepeatMode.OFF;
            };

            playbackQueue.setRepeatMode(mode);
            updateQueueButtons.run();
        });

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
        Slider seekSlider = new Slider(0, 1, 0);
        seekSlider.setMaxWidth(420);
        seekSlider.setBlockIncrement(5);

// Refresh the slider when playback time or duration changes.
        audioService.currentTimeProperty().addListener(
                (observable, oldTime, newTime) ->
                        updateSeekSlider(seekSlider)
        );

        audioService.durationProperty().addListener(
                (observable, oldDuration, newDuration) ->
                        updateSeekSlider(seekSlider)
        );

// Handle clicks on the slider track and keyboard adjustments.
        seekSlider.valueProperty().addListener(
                (observable, oldValue, newValue) -> {
                    if (!updatingSeekSlider
                            && !seekSlider.isValueChanging()
                            && !seekSlider.isDisabled()) {
                        audioService.seek(
                                Duration.seconds(newValue.doubleValue())
                        );
                    }
                }
        );

// Seek once when the user finishes dragging the handle.
        seekSlider.valueChangingProperty().addListener(
                (observable, wasChanging, isChanging) -> {
                    if (wasChanging
                            && !isChanging
                            && !updatingSeekSlider
                            && !seekSlider.isDisabled()) {
                        audioService.seek(
                                Duration.seconds(seekSlider.getValue())
                        );
                    }
                }
        );

        updateSeekSlider(seekSlider);


        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open an MP3");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("MP3 audio", "*.mp3")
        );

        FilteredList<File> filteredTracks =
                new FilteredList<>(libraryService.getTracks(), file -> true);

        ListView<File> songList = new ListView<>(filteredTracks);

        Consumer<File> playFile = file -> {
            trackLabel.setText(file.getName());
            errorLabel.setText("");
            playButton.setDisable(false);
            pauseButton.setDisable(false);

            // Highlight the playing file when it is visible.
            int visibleIndex = filteredTracks.indexOf(file);

            songList.getSelectionModel().clearSelection();

            if (visibleIndex >= 0) {
                songList.getSelectionModel().select(visibleIndex);
                songList.scrollTo(visibleIndex);
            }

            audioService.loadAndPlay(file);
            updateQueueButtons.run();
        };

        Runnable playNext = () -> {
            File nextFile = playbackQueue.next();

            if (nextFile == null) {
                updateQueueButtons.run();
                return;
            }

            playFile.accept(nextFile);
        };

        audioService.setOnTrackFinished(() -> {
            File nextFile = playbackQueue.nextAfterCompletion();

            if (nextFile == null) {
                updateQueueButtons.run();
                return;
            }

            playFile.accept(nextFile);
        });

        Consumer<File> startQueue = file -> {
            playbackQueue.start(filteredTracks, file);
            playFile.accept(file);
        };

        openButton.setOnAction(event -> {
            chooser.setTitle("Open an MP3");

            File selectedFile = chooser.showOpenDialog(stage);

            if (selectedFile == null) {
                return;
            }

            libraryService.addFiles(List.of(selectedFile));

            playbackQueue.start(List.of(selectedFile), selectedFile);
            playFile.accept(selectedFile);
        });

        addButton.setOnAction(event -> {
            chooser.setTitle("Add MP3s");

            List<File> selectedFiles =
                    chooser.showOpenMultipleDialog(stage);

            if (selectedFiles == null) {
                return;
            }

            libraryService.addFiles(selectedFiles);
        });

        TextField searchField = new TextField();
        searchField.setPromptText("Search filenames...");

        searchField.textProperty().addListener(
                (observable, oldText, newText) -> {
                    String query = newText == null
                            ? ""
                            : newText.strip().toLowerCase(Locale.ROOT);

                    filteredTracks.setPredicate(file ->
                            query.isEmpty()
                                    || file.getName()
                                    .toLowerCase(Locale.ROOT)
                                    .contains(query)
                    );
                }
        );

        Label emptyLabel = new Label();

        emptyLabel.textProperty().bind(
                Bindings.createStringBinding(
                        () -> libraryService.getTracks().isEmpty()
                                ? "Use Add MP3s to build your song list."
                                : "No songs match your search.",
                        libraryService.getTracks()
                )
        );

        songList.setPlaceholder(emptyLabel);

        songList.setFixedCellSize(32);
        songList.setMinHeight(120);

        songList.setCellFactory(listView -> {
            ListCell<File> cell = new ListCell<>() {
                @Override
                protected void updateItem(File file, boolean empty) {
                    super.updateItem(file, empty);

                    setText(empty || file == null ? null : file.getName());
                    setGraphic(null);
                }
            };

            cell.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY
                        && event.getClickCount() == 2
                        && !cell.isEmpty()
                        && cell.getItem() != null) {
                    startQueue.accept(cell.getItem());
                    event.consume();
                }
            });

            return cell;
        });

        songList.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                File selectedFile =
                        songList.getSelectionModel().getSelectedItem();

                if (selectedFile != null) {
                    startQueue.accept(selectedFile);
                }

                event.consume();
            }
        });

        playButton.setOnAction(event -> audioService.play());
        pauseButton.setOnAction(event -> audioService.pause());
        previousButton.setOnAction(event -> {
            File file = playbackQueue.previous();

            if (file != null) {
                playFile.accept(file);
            }
        });

        nextButton.setOnAction(event -> playNext.run());
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

        HBox controls = new HBox(
                10,
                openButton,
                addButton,
                previousButton,
                playButton,
                pauseButton,
                nextButton
        );

        controls.setAlignment(Pos.CENTER);

        HBox playbackOptions = new HBox(
                10,
                repeatButton,
                shuffleButton
        );

        playbackOptions.setAlignment(Pos.CENTER);

        VBox root = new VBox(
                12,
                searchField,
                songList,
                trackLabel,
                timeLabel,
                seekSlider,
                controls,
                playbackOptions,
                volumeControls,
                errorLabel
        );

        VBox.setVgrow(songList, Priority.ALWAYS);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(20));

        stage.setTitle("MiSPlay");
        stage.setScene(new Scene(root, 760, 560));
        stage.show();
    }

    private void updateSeekSlider(Slider slider) {
        Duration total = audioService.durationProperty().get();

        double totalSeconds = total == null
                ? Double.NaN
                : total.toSeconds();

        boolean canSeek = Double.isFinite(totalSeconds)
                && totalSeconds > 0;

        updatingSeekSlider = true;

        try {
            slider.setDisable(!canSeek);
            slider.setMax(canSeek ? totalSeconds : 1);

            if (!canSeek) {
                slider.setValueChanging(false);
                slider.setValue(0);
            } else if (!slider.isValueChanging()) {
                Duration elapsed =
                        audioService.currentTimeProperty().get();

                double seconds = elapsed == null
                        ? 0
                        : elapsed.toSeconds();

                slider.setValue(
                        Double.isFinite(seconds) ? seconds : 0
                );
            }
        } finally {
            updatingSeekSlider = false;
        }
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