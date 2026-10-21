package com.misplay;

import com.misplay.model.PlaybackQueue;
import com.misplay.model.PlaybackPreferences;
import com.misplay.service.AudioService;
import com.misplay.service.ArtworkService;
import com.misplay.model.RepeatMode;
import com.misplay.service.LibraryService;
import com.misplay.model.TrackMetadata;
import com.misplay.model.Track;

import java.util.ArrayList;

import javafx.beans.binding.StringBinding;

import javafx.scene.Scene;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.layout.StackPane;
import javafx.scene.image.ImageView;
import javafx.scene.text.TextAlignment;

import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;

import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Priority;

import javafx.stage.DirectoryChooser;

import java.util.List;
import java.util.function.Consumer;
import java.util.Locale;

import javafx.collections.transformation.FilteredList;

import javafx.application.Application;
import javafx.animation.PauseTransition;

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
    private boolean applyingPlaybackPreferences;
    private boolean stopping;
    private final PauseTransition preferencesSaveDelay =
            new PauseTransition(Duration.millis(250));
    private final LibraryService libraryService = new LibraryService();
    private final PlaybackQueue playbackQueue = new PlaybackQueue();
    private final ArtworkService artworkService = new ArtworkService();

    private StringBinding titleBinding;
    private StringBinding detailsBinding;


    @Override
    public void start(Stage stage) {
        Label trackLabel = new Label("Select a song to begin.");
        trackLabel.setWrapText(true);
        Label metadataLabel = new Label();
        metadataLabel.setWrapText(true);
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

        openButton.disableProperty().bind(libraryService.restoringLibraryProperty());
        addButton.disableProperty().bind(libraryService.restoringLibraryProperty());
        repeatButton.disableProperty().bind(libraryService.restoringLibraryProperty());
        shuffleButton.disableProperty().bind(libraryService.restoringLibraryProperty());

        Button addFolderButton = new Button("Add Folder");

        addFolderButton.disableProperty().bind(
                libraryService.folderScanningProperty().or(
                        libraryService.restoringLibraryProperty()
                )
        );

        DirectoryChooser folderChooser = new DirectoryChooser();
        folderChooser.setTitle("Add music folder");

        addFolderButton.setOnAction(event -> {
            File folder = folderChooser.showDialog(stage);

            if (folder != null) {
                libraryService.scanFolder(folder);
            }
        });

        ProgressIndicator folderProgress = new ProgressIndicator();
        folderProgress.setPrefSize(18, 18);
        folderProgress.setMaxSize(18, 18);

        folderProgress.visibleProperty().bind(
                libraryService.folderScanningProperty()
        );

        folderProgress.managedProperty().bind(
                folderProgress.visibleProperty()
        );

        Label folderStatusLabel = new Label();
        folderStatusLabel.setWrapText(true);

        folderStatusLabel.textProperty().bind(
                libraryService.folderScanMessageProperty()
        );

        HBox.setHgrow(folderStatusLabel, Priority.ALWAYS);

        Button cancelScanButton = new Button("Cancel scan");

        cancelScanButton.visibleProperty().bind(
                libraryService.folderScanningProperty()
        );

        cancelScanButton.managedProperty().bind(
                cancelScanButton.visibleProperty()
        );

        cancelScanButton.setOnAction(
                event -> libraryService.cancelFolderScan()
        );

        ProgressIndicator libraryProgress = new ProgressIndicator();
        libraryProgress.setPrefSize(18, 18);
        libraryProgress.setMaxSize(18, 18);
        libraryProgress.visibleProperty().bind(
                libraryService.restoringLibraryProperty().or(
                        libraryService.savingLibraryProperty()
                )
        );
        libraryProgress.managedProperty().bind(libraryProgress.visibleProperty());

        Label libraryStatusLabel = new Label();
        libraryStatusLabel.setWrapText(true);
        libraryStatusLabel.textProperty().bind(
                libraryService.libraryStorageMessageProperty()
        );
        HBox.setHgrow(libraryStatusLabel, Priority.ALWAYS);

        Button retrySaveButton = new Button("Retry save");
        retrySaveButton.visibleProperty().bind(
                libraryService.librarySaveFailedProperty()
        );
        retrySaveButton.managedProperty().bind(retrySaveButton.visibleProperty());
        retrySaveButton.setOnAction(event -> libraryService.retryLibrarySave());

        HBox libraryStatusRow = new HBox(
                8, libraryProgress, libraryStatusLabel, retrySaveButton
        );
        libraryStatusRow.setAlignment(Pos.CENTER_LEFT);

        HBox folderStatusRow = new HBox(
                8,
                folderProgress,
                folderStatusLabel,
                cancelScanButton
        );

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
            requestPlaybackPreferencesSave();
        });

        repeatButton.setOnAction(event -> {
            RepeatMode mode = switch (playbackQueue.getRepeatMode()) {
                case OFF -> RepeatMode.ALL;
                case ALL -> RepeatMode.ONE;
                case ONE -> RepeatMode.OFF;
            };

            playbackQueue.setRepeatMode(mode);
            updateQueueButtons.run();
            requestPlaybackPreferencesSave();
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

        FilteredList<Track> filteredTracks = new FilteredList<>(
                libraryService.getTracks(),
                track -> track.matchesSearch("")
        );

        ListView<Track> songList = new ListView<>(filteredTracks);

        Consumer<File> playFile = file -> {
            loadMetadata(file, trackLabel, metadataLabel);
            artworkService.showArtwork(file);
            errorLabel.setText("");
            playButton.setDisable(false);
            pauseButton.setDisable(false);

            // Highlight the playing file when it is visible.
            int visibleIndex = filteredTracks.indexOf(
                    libraryService.getTrack(file)
            );

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

        Consumer<Track> startQueue = track -> {
            List<File> queueFiles = new ArrayList<>();

            for (Track visibleTrack : filteredTracks) {
                queueFiles.add(visibleTrack.getFile());
            }

            playbackQueue.start(queueFiles, track.getFile());
            playFile.accept(track.getFile());
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

        searchField.setPromptText(
                "Search filename, title, artist or album..."
        );

        searchField.textProperty().addListener(
                (observable, oldText, newText) -> {
                    String query = newText == null
                            ? ""
                            : newText.strip().toLowerCase(Locale.ROOT);

                    filteredTracks.setPredicate(
                            track -> track.matchesSearch(query)
                    );
                }
        );

        Label emptyLabel = new Label();

        emptyLabel.textProperty().bind(
                Bindings.createStringBinding(
                        () -> libraryService.restoringLibraryProperty().get()
                                ? "Loading saved library..."
                                : libraryService.getTracks().isEmpty()
                                        ? "Use Add MP3s or Add Folder to build your song list."
                                        : "No songs match your search.",
                        libraryService.getTracks(),
                        libraryService.restoringLibraryProperty()
                )
        );

        songList.setPlaceholder(emptyLabel);

        songList.setFixedCellSize(32);
        songList.setMinHeight(120);

        songList.setCellFactory(listView -> {
            ListCell<Track> cell = new ListCell<>() {
                private StringBinding rowText;

                @Override
                protected void updateItem(Track track, boolean empty) {
                    super.updateItem(track, empty);

                    textProperty().unbind();

                    if (rowText != null) {
                        rowText.dispose();
                        rowText = null;
                    }

                    setGraphic(null);

                    if (empty || track == null) {
                        setText(null);
                        return;
                    }

                    rowText = Bindings.createStringBinding(
                            () -> {
                                TrackMetadata metadata = track.getMetadata();

                                return metadata.title()
                                        + " — "
                                        + metadata.artist();
                            },
                            track.metadataProperty()
                    );

                    textProperty().bind(rowText);
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
                Track selectedTrack =
                        songList.getSelectionModel().getSelectedItem();

                if (selectedTrack != null) {
                    startQueue.accept(selectedTrack);
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
        volumeSlider.disableProperty().bind(libraryService.restoringLibraryProperty());

        Label volumeValue = new Label(
                Math.round(volumeSlider.getValue()) + "%"
        );
        volumeValue.setMinWidth(45);

        volumeSlider.valueProperty().addListener(
                (observable, oldValue, newValue) -> {
                    double percentage = newValue.doubleValue();

                    audioService.setVolume(percentage / 100.0);
                    volumeValue.setText(Math.round(percentage) + "%");
                    requestPlaybackPreferencesSave();
                }
        );

        preferencesSaveDelay.setOnFinished(event -> savePlaybackPreferences());

        libraryService.restoringLibraryProperty().addListener(
                (observable, wasRestoring, isRestoring) -> {
                    if (isRestoring || stopping) {
                        return;
                    }

                    PlaybackPreferences preferences = libraryService.getPlaybackPreferences();
                    applyingPlaybackPreferences = true;

                    try {
                        audioService.setVolume(preferences.volume());
                        volumeSlider.setValue(preferences.volume() * 100.0);
                        playbackQueue.setRepeatMode(preferences.repeatMode());
                        playbackQueue.setShuffleEnabled(preferences.shuffleEnabled());
                        updateQueueButtons.run();
                    } finally {
                        applyingPlaybackPreferences = false;
                    }
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
                addFolderButton,
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

        ImageView coverView = new ImageView();
        coverView.setFitWidth(128);
        coverView.setFitHeight(128);
        coverView.setPreserveRatio(true);
        coverView.setSmooth(true);
        coverView.imageProperty().bind(artworkService.imageProperty());

        Label coverPlaceholder = new Label();
        coverPlaceholder.setWrapText(true);
        coverPlaceholder.setMaxWidth(112);
        coverPlaceholder.setAlignment(Pos.CENTER);
        coverPlaceholder.setTextAlignment(TextAlignment.CENTER);
        coverPlaceholder.textProperty().bind(artworkService.messageProperty());
        coverPlaceholder.visibleProperty().bind(
                artworkService.imageProperty().isNull()
        );
        coverPlaceholder.managedProperty().bind(
                coverPlaceholder.visibleProperty()
        );

        StackPane coverPane = new StackPane(coverPlaceholder, coverView);
        coverPane.setMinSize(128, 128);
        coverPane.setPrefSize(128, 128);
        coverPane.setMaxSize(128, 128);
        coverPane.setStyle(
                "-fx-background-color: -fx-control-inner-background;"
                        + "-fx-border-color: -fx-box-border;"
                        + "-fx-background-radius: 6;"
                        + "-fx-border-radius: 6;"
        );

        VBox nowPlayingText = new VBox(6, trackLabel, metadataLabel);
        nowPlayingText.setAlignment(Pos.CENTER_LEFT);
        nowPlayingText.setMinWidth(0);
        HBox.setHgrow(nowPlayingText, Priority.ALWAYS);

        HBox nowPlayingRow = new HBox(16, coverPane, nowPlayingText);
        nowPlayingRow.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(
                12,
                searchField,
                libraryStatusRow,
                songList,
                nowPlayingRow,
                timeLabel,
                seekSlider,
                controls,
                folderStatusRow,
                playbackOptions,
                volumeControls,
                errorLabel
        );

        VBox.setVgrow(songList, Priority.ALWAYS);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(20));

        stage.setTitle("MiSPlay");
        stage.setScene(new Scene(root, 760, 640));
        stage.show();
        libraryService.restoreLibrary();
    }

    private void requestPlaybackPreferencesSave() {
        if (!applyingPlaybackPreferences && !stopping
                && !libraryService.restoringLibraryProperty().get()) {
            preferencesSaveDelay.playFromStart();
        }
    }

    private void savePlaybackPreferences() {
        if (audioService != null && !applyingPlaybackPreferences && !stopping
                && !libraryService.restoringLibraryProperty().get()) {
            libraryService.setPlaybackPreferences(new PlaybackPreferences(
                    audioService.getVolume(), playbackQueue.getRepeatMode(),
                    playbackQueue.isShuffleEnabled()
            ));
        }
    }

    private void loadMetadata(
            File file,
            Label titleLabel,
            Label detailsLabel
    ) {
        Track track = libraryService.getTrack(file);

        titleLabel.textProperty().unbind();
        detailsLabel.textProperty().unbind();

        if (titleBinding != null) {
            titleBinding.dispose();
        }

        if (detailsBinding != null) {
            detailsBinding.dispose();
        }

        titleBinding = Bindings.createStringBinding(
                () -> track.getMetadata().title(),
                track.metadataProperty()
        );

        detailsBinding = Bindings.createStringBinding(
                () -> switch (track.getMetadataStatus()) {
                    case PENDING -> "Waiting for tags...";
                    case LOADING -> "Reading tags...";
                    case FAILED -> "Tags unavailable — showing filename.";
                    case LOADED -> track.getMetadata().artist()
                            + " — " + track.getMetadata().album();
                },
                track.metadataProperty(),
                track.metadataStatusProperty()
        );

        titleLabel.textProperty().bind(titleBinding);
        detailsLabel.textProperty().bind(detailsBinding);

        libraryService.prioritizeMetadata(file);
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
        preferencesSaveDelay.stop();
        // Capture a change made less than 250 ms before closing.
        savePlaybackPreferences();
        stopping = true;

        if (titleBinding != null) {
            titleBinding.dispose();
        }

        if (detailsBinding != null) {
            detailsBinding.dispose();
        }

        artworkService.close();
        libraryService.close();

        if (audioService != null) {
            audioService.dispose();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
