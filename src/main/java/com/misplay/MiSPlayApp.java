package com.misplay;

import com.misplay.model.PlaybackQueue;
import com.misplay.model.PlaybackPreferences;
import com.misplay.model.Playlist;
import com.misplay.service.AudioService;
import com.misplay.service.ArtworkService;
import com.misplay.model.RepeatMode;
import com.misplay.service.LibraryService;
import com.misplay.model.TrackMetadata;
import com.misplay.model.Track;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import javafx.beans.binding.StringBinding;
import javafx.beans.binding.BooleanBinding;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.event.ActionEvent;

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
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tooltip;

import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Priority;

import javafx.stage.DirectoryChooser;

import java.util.List;
import java.util.function.Consumer;
import java.util.Locale;

import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;

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
        errorLabel.visibleProperty().bind(errorLabel.textProperty().isNotEmpty());
        errorLabel.managedProperty().bind(errorLabel.visibleProperty());

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
        folderStatusRow.visibleProperty().bind(
                libraryService.folderScanningProperty().or(
                        libraryService.folderScanMessageProperty().isNotEmpty()
                )
        );
        folderStatusRow.managedProperty().bind(folderStatusRow.visibleProperty());

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

        TextField searchField = new TextField();
        searchField.setId("library-search");
        searchField.setPromptText("Search filename, title, artist or album...");

        Playlist allSongs = new Playlist("all-songs", "All songs", List.of());
        var playlistChoices = FXCollections.observableArrayList(allSongs);
        ComboBox<Playlist> playlistChooser = new ComboBox<>(playlistChoices);
        playlistChooser.setId("playlist-view");
        playlistChooser.setValue(allSongs);
        playlistChooser.setPrefWidth(260);
        playlistChooser.setMinWidth(120);
        playlistChooser.setMaxWidth(Double.MAX_VALUE);
        playlistChooser.setAccessibleText("Library view");
        playlistChooser.disableProperty().bind(libraryService.restoringLibraryProperty());
        HBox.setHgrow(playlistChooser, Priority.ALWAYS);

        FilteredList<Track> filteredTracks = new FilteredList<>(
                libraryService.getTracks(),
                track -> track.matchesSearch("")
        );
        SortedList<Track> visibleTracks = new SortedList<>(filteredTracks);
        ListView<Track> songList = new ListView<>(visibleTracks);
        songList.setId("song-list");
        songList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        songList.setTooltip(new Tooltip(
                "Double-click or press Enter to play. Use Ctrl/Shift to select songs."
        ));

        BooleanBinding allSongsSelected = Bindings.createBooleanBinding(
                () -> playlistChooser.getValue() == null
                        || playlistChooser.getValue().id().equals(allSongs.id()),
                playlistChooser.valueProperty()
        );

        Runnable applyLibraryView = () -> {
            List<Track> selection = List.copyOf(songList.getSelectionModel().getSelectedItems());
            Track selected = songList.getSelectionModel().getSelectedItem();
            Playlist playlist = playlistChooser.getValue();
            boolean showAll = allSongsSelected.get();
            Map<File, Integer> positions = new HashMap<>();

            if (!showAll) {
                for (int index = 0; index < playlist.files().size(); index++) {
                    positions.put(playlist.files().get(index), index);
                }
            }

            String query = searchField.getText().strip().toLowerCase(Locale.ROOT);
            filteredTracks.setPredicate(track ->
                    (showAll || positions.containsKey(track.getFile()))
                            && track.matchesSearch(query)
            );
            visibleTracks.setComparator(showAll ? null : java.util.Comparator.comparingInt(
                    track -> positions.getOrDefault(track.getFile(), Integer.MAX_VALUE)
            ));

            songList.getSelectionModel().clearSelection();

            for (Track track : selection) {
                if (visibleTracks.contains(track)) {
                    songList.getSelectionModel().select(track);
                }
            }

            if (selected != null && visibleTracks.contains(selected)) {
                songList.getSelectionModel().select(selected);
            }
        };

        // Edits replace immutable snapshots. Keep the view on the same playlist ID.
        boolean[] refreshingPlaylistChoices = {false};
        playlistChooser.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (!refreshingPlaylistChoices[0]) {
                applyLibraryView.run();
            }
        });
        libraryService.getPlaylists().addListener((ListChangeListener<Playlist>) change -> {
            Playlist selected = playlistChooser.getValue();
            refreshingPlaylistChoices[0] = true;

            try {
                playlistChoices.setAll(allSongs);
                playlistChoices.addAll(libraryService.getPlaylists());
                playlistChooser.setValue(playlistChoices.stream()
                        .filter(playlist -> selected != null
                                && playlist.id().equals(selected.id()))
                        .findFirst().orElse(allSongs));
            } finally {
                refreshingPlaylistChoices[0] = false;
            }

            applyLibraryView.run();
        });

        Consumer<File> playFile = file -> {
            loadMetadata(file, trackLabel, metadataLabel);
            artworkService.showArtwork(file);
            errorLabel.setText("");
            playButton.setDisable(false);
            pauseButton.setDisable(false);

            // Highlight the playing file when it is visible.
            int visibleIndex = visibleTracks.indexOf(
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

            for (Track visibleTrack : visibleTracks) {
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

        searchField.textProperty().addListener(
                (observable, oldText, newText) -> applyLibraryView.run()
        );

        Label emptyLabel = new Label();

        emptyLabel.textProperty().bind(
                Bindings.createStringBinding(
                        () -> libraryService.restoringLibraryProperty().get()
                                ? "Loading saved library..."
                                : !allSongsSelected.get()
                                        && playlistChooser.getValue().files().isEmpty()
                                        ? "This playlist is empty. Select All songs to add library songs."
                                : allSongsSelected.get() && libraryService.getTracks().isEmpty()
                                        ? "Use Add MP3s or Add Folder to build your song list."
                                        : "No songs match your search.",
                        libraryService.getTracks(),
                        playlistChooser.valueProperty(),
                        allSongsSelected,
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

        Label playlistMessage = new Label();
        playlistMessage.setWrapText(true);
        playlistMessage.visibleProperty().bind(playlistMessage.textProperty().isNotEmpty());
        playlistMessage.managedProperty().bind(playlistMessage.visibleProperty());

        Button newPlaylistButton = new Button("New playlist");
        Button renamePlaylistButton = new Button("Rename");
        Button deletePlaylistButton = new Button("Delete playlist");
        newPlaylistButton.disableProperty().bind(libraryService.restoringLibraryProperty());
        renamePlaylistButton.disableProperty().bind(
                libraryService.restoringLibraryProperty().or(allSongsSelected)
        );
        deletePlaylistButton.disableProperty().bind(renamePlaylistButton.disableProperty());

        newPlaylistButton.setOnAction(event -> showPlaylistNameDialog(
                stage, "New playlist", "", name -> {
                    Playlist created = libraryService.createPlaylist(name);
                    playlistChooser.setValue(created);
                    playlistMessage.setText("Created playlist \"" + created.name() + "\".");
                }
        ));
        renamePlaylistButton.setOnAction(event -> {
            Playlist selected = playlistChooser.getValue();
            showPlaylistNameDialog(stage, "Rename playlist", selected.name(), name -> {
                libraryService.renamePlaylist(selected.id(), name);
                playlistMessage.setText("Playlist renamed.");
            });
        });
        deletePlaylistButton.setOnAction(event -> {
            Playlist selected = playlistChooser.getValue();
            Alert confirmation = new Alert(
                    Alert.AlertType.CONFIRMATION,
                    "Delete \"" + selected.name() + "\"? The songs will stay in All songs.",
                    ButtonType.OK, ButtonType.CANCEL
            );
            confirmation.initOwner(stage);
            confirmation.setTitle("Delete playlist");
            confirmation.setHeaderText(null);
            confirmation.showAndWait().filter(ButtonType.OK::equals).ifPresent(result -> {
                libraryService.deletePlaylist(selected.id());
                playlistMessage.setText("Deleted playlist \"" + selected.name() + "\".");
            });
        });

        HBox libraryViewControls = new HBox(
                8, new Label("View"), playlistChooser,
                newPlaylistButton, renamePlaylistButton, deletePlaylistButton
        );
        libraryViewControls.setAlignment(Pos.CENTER_LEFT);

        Button addToPlaylistButton = new Button("Add to playlist...");
        Button removeFromPlaylistButton = new Button("Remove from playlist");
        Button moveUpButton = new Button("Move up");
        Button moveDownButton = new Button("Move down");
        addToPlaylistButton.disableProperty().bind(
                libraryService.restoringLibraryProperty()
                        .or(Bindings.isEmpty(libraryService.getPlaylists()))
                        .or(Bindings.isEmpty(songList.getSelectionModel().getSelectedItems()))
        );
        removeFromPlaylistButton.disableProperty().bind(
                libraryService.restoringLibraryProperty().or(allSongsSelected)
                        .or(Bindings.isEmpty(songList.getSelectionModel().getSelectedItems()))
        );
        moveUpButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> removeFromPlaylistButton.isDisabled()
                        || !searchField.getText().isBlank()
                        || songList.getSelectionModel().getSelectedItems().size() != 1
                        || songList.getSelectionModel().getSelectedIndex() <= 0,
                removeFromPlaylistButton.disableProperty(), searchField.textProperty(),
                songList.getSelectionModel().getSelectedItems(),
                songList.getSelectionModel().selectedIndexProperty()
        ));
        moveDownButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> removeFromPlaylistButton.isDisabled()
                        || !searchField.getText().isBlank()
                        || songList.getSelectionModel().getSelectedItems().size() != 1
                        || songList.getSelectionModel().getSelectedIndex() >= visibleTracks.size() - 1,
                removeFromPlaylistButton.disableProperty(), searchField.textProperty(),
                songList.getSelectionModel().getSelectedItems(),
                songList.getSelectionModel().selectedIndexProperty(), visibleTracks
        ));
        moveUpButton.setTooltip(new Tooltip("Select one song and clear search to reorder."));
        moveDownButton.setTooltip(new Tooltip("Select one song and clear search to reorder."));

        addToPlaylistButton.setOnAction(event -> {
            List<Track> selected = songList.getSelectionModel().getSelectedIndices().stream()
                    .map(visibleTracks::get).toList();
            List<Playlist> destinations = List.copyOf(libraryService.getPlaylists());
            Playlist initial = allSongsSelected.get()
                    ? destinations.get(0) : playlistChooser.getValue();
            ChoiceDialog<Playlist> dialog = new ChoiceDialog<>(initial, destinations);
            dialog.initOwner(stage);
            dialog.setTitle("Add to playlist");
            dialog.setHeaderText("Add " + selected.size() + " selected song(s).");
            dialog.setContentText("Playlist:");
            dialog.showAndWait().ifPresent(destination -> {
                int added = libraryService.addToPlaylist(destination.id(), selected);
                playlistMessage.setText(added == 0
                        ? "Those songs are already in \"" + destination.name() + "\"."
                        : "Added " + added + " song(s) to \"" + destination.name() + "\".");
            });
        });
        removeFromPlaylistButton.setOnAction(event -> {
            Playlist selected = playlistChooser.getValue();
            int removed = libraryService.removeFromPlaylist(selected.id(),
                    List.copyOf(songList.getSelectionModel().getSelectedItems()));
            playlistMessage.setText("Removed " + removed + " song(s) from this playlist.");
        });
        Consumer<Integer> moveSelected = direction -> {
            Track selected = songList.getSelectionModel().getSelectedItem();

            if (selected != null && !allSongsSelected.get() && searchField.getText().isBlank()) {
                libraryService.movePlaylistTrack(playlistChooser.getValue().id(), selected, direction);
                songList.getSelectionModel().clearSelection();
                songList.getSelectionModel().select(selected);
                songList.scrollTo(selected);
            }
        };
        moveUpButton.setOnAction(event -> moveSelected.accept(-1));
        moveDownButton.setOnAction(event -> moveSelected.accept(1));

        HBox playlistSongControls = new HBox(
                8, addToPlaylistButton, removeFromPlaylistButton, moveUpButton, moveDownButton
        );
        playlistSongControls.setAlignment(Pos.CENTER_LEFT);

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
                10,
                libraryViewControls,
                searchField,
                libraryStatusRow,
                songList,
                playlistSongControls,
                playlistMessage,
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
        stage.setScene(new Scene(root, 820, 780));
        stage.setMinWidth(760);
        stage.setMinHeight(720);
        stage.show();
        libraryService.restoreLibrary();
    }

    private void showPlaylistNameDialog(
            Stage owner, String title, String initialName, Consumer<String> saveName
    ) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(title);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        TextField name = new TextField(initialName);
        Label validation = new Label();
        validation.setWrapText(true);
        validation.setMaxWidth(340);
        dialog.getDialogPane().setContent(new VBox(
                8, new Label("Name (1–" + Playlist.MAX_NAME_LENGTH + " characters)"),
                name, validation
        ));
        dialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(
                ActionEvent.ACTION, event -> {
                    try {
                        saveName.accept(name.getText());
                    } catch (IllegalArgumentException error) {
                        validation.setText(error.getMessage());
                        event.consume();
                    }
                }
        );
        dialog.setOnShown(event -> {
            name.requestFocus();
            name.selectAll();
        });
        dialog.showAndWait();
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
