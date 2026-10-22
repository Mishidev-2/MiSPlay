package com.misplay;

import com.misplay.model.PlaybackQueue;
import com.misplay.model.PlaybackQueue.Entry;
import com.misplay.model.PlaybackPreferences;
import com.misplay.model.Playlist;
import com.misplay.service.AudioService;
import com.misplay.service.ArtworkService;
import com.misplay.model.RepeatMode;
import com.misplay.service.LibraryService;
import com.misplay.service.LyricsService;
import com.misplay.view.LyricsPane;
import com.misplay.model.TrackMetadata;
import com.misplay.model.Track;
import com.misplay.model.TrackSort;
import com.misplay.model.LibraryBrowse.Mode;
import com.misplay.view.LibraryBrowser;

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
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;

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
import javafx.util.StringConverter;



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
    private final LyricsService lyricsService = new LyricsService();
    private LyricsPane lyricsPane;
    private LibraryBrowser libraryBrowser;

    private StringBinding titleBinding;
    private StringBinding detailsBinding;


    @Override
    public void start(Stage stage) {
        Label trackLabel = new Label("Select a song to begin.");
        trackLabel.setWrapText(true);
        trackLabel.setId("now-playing-title");
        Label metadataLabel = new Label();
        metadataLabel.setId("now-playing-metadata");
        metadataLabel.setWrapText(true);
        metadataLabel.setMaxHeight(76);
        Tooltip metadataDetails = new Tooltip();
        metadataDetails.textProperty().bind(metadataLabel.textProperty());
        metadataLabel.setTooltip(metadataDetails);
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

        ListView<Entry> queueList = new ListView<>();
        queueList.setId("queue-list");
        queueList.setFixedCellSize(52);
        Label queueEmpty = new Label("Your queue is empty. Use a song's menu to add songs.");
        queueEmpty.setWrapText(true);
        queueList.setPlaceholder(queueEmpty);
        Runnable refreshQueue = () -> {
            queueList.getItems().setAll(playbackQueue.entries());
            Entry current = playbackQueue.current();
            queueList.getSelectionModel().select(current);
        };

        Runnable updateQueueButtons = () -> {
            refreshQueue.run();
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
            if (lyricsPane != null) {
                lyricsPane.playbackFailed();
            }
        });
        lyricsPane = new LyricsPane(lyricsService, audioService);

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
        searchField.setPromptText("Search filename, title, artists or album...");

        Playlist allSongs = new Playlist("all-songs", "All songs", List.of());
        Playlist favoritesView = new Playlist("favorites", "Favorites", List.of());
        var playlistChoices = FXCollections.observableArrayList(allSongs, favoritesView);
        ComboBox<Playlist> playlistChooser = new ComboBox<>(playlistChoices);
        playlistChooser.setConverter(new StringConverter<>() {
            @Override
            public String toString(Playlist playlist) {
                if (playlist == null) {
                    return "";
                }

                return playlist.id().equals(allSongs.id())
                        || playlist.id().equals(favoritesView.id())
                        ? playlist.name() : "Playlist: " + playlist.name();
            }

            @Override
            public Playlist fromString(String text) {
                return playlistChoices.stream().filter(choice -> toString(choice).equals(text))
                        .findFirst().orElse(null);
            }
        });
        playlistChooser.setId("playlist-view");
        playlistChooser.setValue(allSongs);
        playlistChooser.setPrefWidth(260);
        playlistChooser.setMinWidth(120);
        playlistChooser.setMaxWidth(Double.MAX_VALUE);
        playlistChooser.setAccessibleText("Library view");
        playlistChooser.disableProperty().bind(libraryService.restoringLibraryProperty());
        HBox.setHgrow(playlistChooser, Priority.ALWAYS);

        ComboBox<TrackSort> sortChooser = new ComboBox<>();
        sortChooser.setId("library-sort");
        sortChooser.setPrefWidth(220);
        sortChooser.setAccessibleText("Sort the current library view");
        sortChooser.setTooltip(new Tooltip(
                "Changes this view. Starting a song captures its displayed order."
        ));
        sortChooser.disableProperty().bind(libraryService.restoringLibraryProperty());
        Map<LibraryBrowser.Context, TrackSort> viewSorts = new HashMap<>();
        boolean[] changingSort = {false};
        HBox sortControls = new HBox(8);
        sortControls.setAlignment(Pos.CENTER_LEFT);

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

        libraryBrowser = new LibraryBrowser(libraryService.getTracks(), songList);
        libraryBrowser.disableProperty().bind(libraryService.restoringLibraryProperty());
        libraryBrowser.getModeChooser().disableProperty().bind(
                libraryService.restoringLibraryProperty()
        );
        sortControls.getChildren().setAll(new Label("Browse:"), libraryBrowser.getModeChooser(),
                new Label("Sort songs:"), sortChooser);

        BooleanBinding allSongsSelected = Bindings.createBooleanBinding(
                () -> playlistChooser.getValue() == null
                        || playlistChooser.getValue().id().equals(allSongs.id()),
                playlistChooser.valueProperty()
        );
        BooleanBinding favoritesSelected = Bindings.createBooleanBinding(
                () -> playlistChooser.getValue() != null
                        && playlistChooser.getValue().id().equals(favoritesView.id()),
                playlistChooser.valueProperty()
        );
        BooleanBinding manualPlaylistSelected = allSongsSelected.or(favoritesSelected).not();

        Runnable configureSortChoices = () -> {
            Playlist view = playlistChooser.getValue();
            String id = view == null ? allSongs.id() : view.id();
            boolean manual = manualPlaylistSelected.get();
            changingSort[0] = true;
            try {
                sortChooser.getItems().setAll(java.util.Arrays.stream(TrackSort.values())
                        .filter(mode -> manual || mode != TrackSort.PLAYLIST_ORDER).toList());
                TrackSort initial = switch (libraryBrowser.getMode()) {
                    case SONGS -> TrackSort.ADDED_ORDER;
                    case ALBUMS -> TrackSort.ALBUM_ASC;
                    case ARTISTS -> TrackSort.TITLE_ASC;
                };
                TrackSort remembered = viewSorts.getOrDefault(libraryBrowser.contextFor(id), initial);
                sortChooser.setValue(!manual && remembered == TrackSort.PLAYLIST_ORDER
                        ? TrackSort.ADDED_ORDER : remembered);
            } finally {
                changingSort[0] = false;
            }
        };
        configureSortChoices.run();

        boolean[] applyingLibraryView = {false};
        Runnable applyLibraryView = () -> {
            if (applyingLibraryView[0]) {
                return;
            }
            applyingLibraryView[0] = true;
            try {
                var selection = java.util.Set.copyOf(songList.getSelectionModel().getSelectedItems());
                Track selected = songList.getSelectionModel().getSelectedItem();
                Playlist playlist = playlistChooser.getValue();
                boolean showAll = allSongsSelected.get();
                boolean showFavorites = favoritesSelected.get();
                boolean showPlaylist = manualPlaylistSelected.get();
                var favorites = libraryService.getFavoriteFiles();
                Map<File, Integer> positions = new HashMap<>();

                if (showPlaylist) {
                    for (int index = 0; index < playlist.files().size(); index++) {
                        positions.put(playlist.files().get(index), index);
                    }
                }

                java.util.function.Predicate<Track> inScope = track -> showAll
                        || (showFavorites && favorites.contains(track.getFile()))
                        || (showPlaylist && positions.containsKey(track.getFile()));
                String id = playlist == null ? allSongs.id() : playlist.id();
                libraryBrowser.showScope(id, libraryService.getTracks().stream()
                        .filter(inScope).toList());
                var groupTracks = libraryBrowser.getSelectedTracks();
                String query = searchField.getText().strip().toLowerCase(Locale.ROOT);
                filteredTracks.setPredicate(track -> inScope.test(track)
                        && (groupTracks == null || groupTracks.contains(track))
                        && track.matchesSearch(query));
                List<File> addedFiles = showPlaylist ? playlist.addedFiles()
                        : libraryService.getTracks().stream().map(Track::getFile).toList();
                TrackSort mode = sortChooser.getValue() == null
                        ? TrackSort.ADDED_ORDER : sortChooser.getValue();
                visibleTracks.setComparator(mode.comparator(addedFiles,
                        showPlaylist ? playlist.files() : addedFiles));

                songList.getSelectionModel().clearSelection();
                int[] retained = java.util.stream.IntStream.range(0, visibleTracks.size())
                        .filter(index -> selection.contains(visibleTracks.get(index))).toArray();
                if (retained.length > 0) {
                    songList.getSelectionModel().selectIndices(retained[0],
                            java.util.Arrays.copyOfRange(retained, 1, retained.length));
                }
                int lead = selected == null ? -1 : visibleTracks.indexOf(selected);
                if (lead >= 0) {
                    songList.getSelectionModel().select(lead);
                }
            } finally {
                applyingLibraryView[0] = false;
            }
        };
        libraryBrowser.selectedTracksProperty().addListener(
                (observable, previous, current) -> applyLibraryView.run()
        );
        libraryBrowser.modeProperty().addListener((observable, previous, current) -> {
            configureSortChoices.run();
            applyLibraryView.run();
        });

        // Edits replace immutable snapshots. Keep the view on the same playlist ID.
        boolean[] refreshingPlaylistChoices = {false};
        playlistChooser.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (!refreshingPlaylistChoices[0]) {
                configureSortChoices.run();
                applyLibraryView.run();
            }
        });
        libraryService.getPlaylists().addListener((ListChangeListener<Playlist>) change -> {
            Playlist selected = playlistChooser.getValue();
            refreshingPlaylistChoices[0] = true;

            try {
                playlistChoices.setAll(allSongs, favoritesView);
                playlistChoices.addAll(libraryService.getPlaylists());
                playlistChooser.setValue(playlistChoices.stream()
                        .filter(playlist -> selected != null
                                && playlist.id().equals(selected.id()))
                        .findFirst().orElse(allSongs));
            } finally {
                refreshingPlaylistChoices[0] = false;
            }

            configureSortChoices.run();
            applyLibraryView.run();
        });
        sortChooser.valueProperty().addListener((observable, previous, current) -> {
            if (!changingSort[0] && current != null) {
                Playlist view = playlistChooser.getValue();
                viewSorts.put(libraryBrowser.contextFor(view == null ? allSongs.id() : view.id()), current);
                applyLibraryView.run();
            }
        });
        libraryService.getTracks().addListener((ListChangeListener<Track>) change -> {
            boolean membershipChanged = false;
            while (change.next()) {
                membershipChanged |= change.wasAdded() || change.wasRemoved()
                        || change.wasPermutated();
            }
            if (membershipChanged) {
                applyLibraryView.run();
            }
        });
        libraryService.favoriteFilesProperty().addListener((observable, previous, current) -> {
            if (favoritesSelected.get()) {
                applyLibraryView.run();
            }
        });

        Consumer<File> playFile = file -> {
            loadMetadata(file, trackLabel, metadataLabel);
            artworkService.showArtwork(file);
            lyricsPane.beginTrack();
            lyricsService.showLyrics(file);
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
                        () -> {
                            if (libraryService.restoringLibraryProperty().get()) {
                                return "Loading saved library...";
                            }

                            if (favoritesSelected.get() && libraryService.getFavoriteFiles().isEmpty()) {
                                return "No favorites yet. Select songs in All songs and click Favorite.";
                            }

                            if (manualPlaylistSelected.get() && playlistChooser.getValue().files().isEmpty()) {
                                return "This playlist is empty. Select All songs to add library songs.";
                            }

                            if (allSongsSelected.get() && libraryService.getTracks().isEmpty()) {
                                return "Use Add MP3s or Add Folder to build your song list.";
                            }

                            if (libraryBrowser.getMode() != Mode.SONGS
                                    && (libraryBrowser.getSelectedTracks() == null
                                    || libraryBrowser.getSelectedTracks().isEmpty())) {
                                return "Choose an album or artist from the group list.";
                            }

                            return "No songs match your search.";
                        },
                        libraryService.getTracks(),
                        playlistChooser.valueProperty(),
                        allSongsSelected,
                        favoritesSelected,
                        manualPlaylistSelected,
                        libraryBrowser.modeProperty(),
                        libraryBrowser.selectedTracksProperty(),
                        libraryService.favoriteFilesProperty(),
                        libraryService.restoringLibraryProperty()
                )
        );

        songList.setPlaceholder(emptyLabel);

        songList.setFixedCellSize(52);
        songList.setMinHeight(120);

        songList.setCellFactory(listView -> {
            ListCell<Track> cell = new ListCell<>() {
                private StringBinding rowText;
                private final Tooltip details = new Tooltip();

                @Override
                protected void updateItem(Track track, boolean empty) {
                    super.updateItem(track, empty);

                    textProperty().unbind();
                    details.textProperty().unbind();

                    if (rowText != null) {
                        rowText.dispose();
                        rowText = null;
                    }

                    setGraphic(null);

                    if (empty || track == null) {
                        setText(null);
                        setTooltip(null);
                        return;
                    }

                    rowText = Bindings.createStringBinding(
                            () -> {
                                TrackMetadata metadata = track.getMetadata();

                                return (libraryService.isFavorite(track) ? "★ " : "")
                                        + metadata.title()
                                        + "\n" + metadata.details();
                            },
                            track.metadataProperty(), libraryService.favoriteFilesProperty()
                    );

                    textProperty().bind(rowText);
                    details.textProperty().bind(rowText);
                    setTooltip(details);
                }
            };

            // Let VirtualFlow size rows to the viewport, including long credits.
            cell.setPrefWidth(0);
            cell.setOnMousePressed(event -> {
                if (event.getButton() == MouseButton.SECONDARY && !cell.isEmpty()
                        && !songList.getSelectionModel().isSelected(cell.getIndex())) {
                    songList.getSelectionModel().clearAndSelect(cell.getIndex());
                }
            });
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
                libraryService.restoringLibraryProperty().or(manualPlaylistSelected.not())
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
        Button favoriteButton = new Button("Favorite");
        Button unfavoriteButton = new Button("Unfavorite");
        favoriteButton.setTooltip(new Tooltip("Add the selected songs to Favorites."));
        unfavoriteButton.setTooltip(new Tooltip("Remove the selected songs from Favorites."));
        favoriteButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> libraryService.restoringLibraryProperty().get()
                        || songList.getSelectionModel().getSelectedItems().isEmpty()
                        || songList.getSelectionModel().getSelectedItems().stream()
                                .allMatch(libraryService::isFavorite),
                libraryService.restoringLibraryProperty(), libraryService.favoriteFilesProperty(),
                songList.getSelectionModel().getSelectedItems()
        ));
        unfavoriteButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> libraryService.restoringLibraryProperty().get()
                        || songList.getSelectionModel().getSelectedItems().stream()
                                .noneMatch(libraryService::isFavorite),
                libraryService.restoringLibraryProperty(), libraryService.favoriteFilesProperty(),
                songList.getSelectionModel().getSelectedItems()
        ));
        favoriteButton.setOnAction(event -> {
            int changed = libraryService.setFavorites(
                    List.copyOf(songList.getSelectionModel().getSelectedItems()), true
            );
            playlistMessage.setText("Added " + changed + " song(s) to Favorites.");
        });
        unfavoriteButton.setOnAction(event -> {
            int changed = libraryService.setFavorites(
                    List.copyOf(songList.getSelectionModel().getSelectedItems()), false
            );
            playlistMessage.setText("Removed " + changed + " song(s) from Favorites.");
        });
        addToPlaylistButton.disableProperty().bind(
                libraryService.restoringLibraryProperty()
                        .or(Bindings.isEmpty(libraryService.getPlaylists()))
                        .or(Bindings.isEmpty(songList.getSelectionModel().getSelectedItems()))
        );
        removeFromPlaylistButton.disableProperty().bind(
                libraryService.restoringLibraryProperty().or(manualPlaylistSelected.not())
                        .or(Bindings.isEmpty(songList.getSelectionModel().getSelectedItems()))
        );
        moveUpButton.setId("playlist-move-up");
        moveDownButton.setId("playlist-move-down");
        moveUpButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> removeFromPlaylistButton.isDisabled()
                        || sortChooser.getValue() != TrackSort.PLAYLIST_ORDER
                        || libraryBrowser.getMode() != Mode.SONGS
                        || !searchField.getText().isBlank()
                        || songList.getSelectionModel().getSelectedItems().size() != 1
                        || songList.getSelectionModel().getSelectedIndex() <= 0,
                removeFromPlaylistButton.disableProperty(), searchField.textProperty(),
                sortChooser.valueProperty(), libraryBrowser.modeProperty(),
                songList.getSelectionModel().getSelectedItems(),
                songList.getSelectionModel().selectedIndexProperty()
        ));
        moveDownButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> removeFromPlaylistButton.isDisabled()
                        || sortChooser.getValue() != TrackSort.PLAYLIST_ORDER
                        || libraryBrowser.getMode() != Mode.SONGS
                        || !searchField.getText().isBlank()
                        || songList.getSelectionModel().getSelectedItems().size() != 1
                        || songList.getSelectionModel().getSelectedIndex() >= visibleTracks.size() - 1,
                removeFromPlaylistButton.disableProperty(), searchField.textProperty(),
                sortChooser.valueProperty(), libraryBrowser.modeProperty(),
                songList.getSelectionModel().getSelectedItems(),
                songList.getSelectionModel().selectedIndexProperty(), visibleTracks
        ));
        moveUpButton.setTooltip(new Tooltip("Choose Songs and Playlist order, select one song and clear search to reorder."));
        moveDownButton.setTooltip(new Tooltip("Choose Songs and Playlist order, select one song and clear search to reorder."));

        addToPlaylistButton.setOnAction(event -> {
            List<Track> selected = songList.getSelectionModel().getSelectedIndices().stream()
                    .map(visibleTracks::get).toList();
            List<Playlist> destinations = List.copyOf(libraryService.getPlaylists());
            Playlist initial = manualPlaylistSelected.get()
                    ? playlistChooser.getValue() : destinations.get(0);
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

            if (selected != null && manualPlaylistSelected.get()
                    && sortChooser.getValue() == TrackSort.PLAYLIST_ORDER
                    && libraryBrowser.getMode() == Mode.SONGS
                    && searchField.getText().isBlank()) {
                libraryService.movePlaylistTrack(playlistChooser.getValue().id(), selected, direction);
                songList.getSelectionModel().clearSelection();
                songList.getSelectionModel().select(selected);
                songList.scrollTo(selected);
            }
        };
        moveUpButton.setOnAction(event -> moveSelected.accept(-1));
        moveDownButton.setOnAction(event -> moveSelected.accept(1));

        // Context actions operate on the current multi-selection in visible order.
        java.util.function.Supplier<List<Track>> selectedInOrder = () -> {
            var selected = java.util.Set.copyOf(songList.getSelectionModel().getSelectedItems());
            return visibleTracks.stream().filter(selected::contains).toList();
        };
        MenuItem playSelection = new MenuItem("Play");
        MenuItem playNextItem = new MenuItem("Play next");
        MenuItem addQueueItem = new MenuItem("Add to queue");
        MenuItem addPlaylistItem = new MenuItem("Add to playlist...");
        MenuItem favoriteItem = new MenuItem("Favorite");
        MenuItem unfavoriteItem = new MenuItem("Unfavorite");
        MenuItem removePlaylistItem = new MenuItem("Remove from playlist");
        ContextMenu songMenu = new ContextMenu(playSelection, playNextItem, addQueueItem,
                addPlaylistItem, favoriteItem, unfavoriteItem, removePlaylistItem);
        songMenu.setOnShowing(event -> {
            boolean empty = selectedInOrder.get().isEmpty();
            playSelection.setDisable(empty);
            playNextItem.setDisable(empty);
            addQueueItem.setDisable(empty);
            addPlaylistItem.setDisable(addToPlaylistButton.isDisabled());
            favoriteItem.setDisable(favoriteButton.isDisabled());
            unfavoriteItem.setDisable(unfavoriteButton.isDisabled());
            removePlaylistItem.setDisable(removeFromPlaylistButton.isDisabled());
        });
        playSelection.setOnAction(event -> {
            Track lead = songList.getSelectionModel().getSelectedItem();
            if (lead != null) startQueue.accept(lead);
        });
        playNextItem.setOnAction(event -> {
            List<File> files = selectedInOrder.get().stream().map(Track::getFile).toList();
            if (files.isEmpty()) return;
            if (playbackQueue.current() == null) {
                playbackQueue.start(files, files.get(0));
                playFile.accept(files.get(0));
            } else {
                playbackQueue.addNext(files);
                updateQueueButtons.run();
            }
        });
        addQueueItem.setOnAction(event -> {
            playbackQueue.addToEnd(selectedInOrder.get().stream().map(Track::getFile).toList());
            updateQueueButtons.run();
        });
        addPlaylistItem.setOnAction(event -> addToPlaylistButton.fire());
        favoriteItem.setOnAction(event -> favoriteButton.fire());
        unfavoriteItem.setOnAction(event -> unfavoriteButton.fire());
        removePlaylistItem.setOnAction(event -> removeFromPlaylistButton.fire());
        songList.setContextMenu(songMenu);

        HBox playlistSongControls = new HBox(
                8, addToPlaylistButton, removeFromPlaylistButton, moveUpButton, moveDownButton,
                favoriteButton, unfavoriteButton
        );
        playlistSongControls.setAlignment(Pos.CENTER_LEFT);

        queueList.setCellFactory(view -> {
            ListCell<Entry> cell = new ListCell<>() {
                @Override protected void updateItem(Entry entry, boolean empty) {
                    super.updateItem(entry, empty);
                    if (empty || entry == null) {
                        setText(null);
                        setTooltip(null);
                        return;
                    }
                    Track track = libraryService.getTrack(entry.file());
                    String title = track.getMetadata().title();
                    String detail = track.getMetadata().details();
                    String label = (entry.equals(playbackQueue.current()) ? "▶ " : "")
                            + title + "\n" + detail;
                    setText(label);
                    setTooltip(new Tooltip(label));
                }
            };
            cell.setPrefWidth(0);
            cell.setOnMousePressed(event -> {
                if (event.getButton() == MouseButton.SECONDARY && !cell.isEmpty())
                    queueList.getSelectionModel().clearAndSelect(cell.getIndex());
            });
            cell.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2
                        && !cell.isEmpty() && cell.getItem() != null) {
                    File file = playbackQueue.play(cell.getItem());
                    if (file != null) playFile.accept(file);
                    event.consume();
                }
            });
            return cell;
        });
        libraryService.getTracks().addListener((ListChangeListener<Track>) change -> {
            if (!queueList.getItems().isEmpty()) queueList.refresh();
        });
        queueList.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                Entry selected = queueList.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    File file = playbackQueue.play(selected);
                    if (file != null) playFile.accept(file);
                }
                event.consume();
            }
        });
        Button removeQueueButton = new Button("Remove entry");
        removeQueueButton.setId("queue-remove");
        Button moveQueueUp = new Button("Move earlier");
        moveQueueUp.setId("queue-move-up");
        Button moveQueueDown = new Button("Move later");
        moveQueueDown.setId("queue-move-down");
        Button clearUpcomingButton = new Button("Clear upcoming");
        clearUpcomingButton.setId("queue-clear-upcoming");
        Runnable updateQueueActions = () -> {
            Entry selected = queueList.getSelectionModel().getSelectedItem();
            int index = queueList.getItems().indexOf(selected);
            int currentIndex = playbackQueue.currentIndex();
            removeQueueButton.setDisable(selected == null || index == currentIndex);
            moveQueueUp.setDisable(selected == null || index <= currentIndex + 1 || index <= 0);
            moveQueueDown.setDisable(selected == null || index <= currentIndex
                    || index >= queueList.getItems().size() - 1);
            clearUpcomingButton.setDisable(queueList.getItems().size() <= currentIndex + 1);
        };
        queueList.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, item) -> updateQueueActions.run());
        queueList.getItems().addListener((ListChangeListener<Entry>) change -> updateQueueActions.run());
        removeQueueButton.setOnAction(event -> {
            if (playbackQueue.remove(queueList.getSelectionModel().getSelectedItem())) updateQueueButtons.run();
        });
        moveQueueUp.setOnAction(event -> {
            Entry selected = queueList.getSelectionModel().getSelectedItem();
            if (playbackQueue.moveUpcoming(selected, -1)) {
                updateQueueButtons.run();
                queueList.getSelectionModel().select(selected);
            }
        });
        moveQueueDown.setOnAction(event -> {
            Entry selected = queueList.getSelectionModel().getSelectedItem();
            if (playbackQueue.moveUpcoming(selected, 1)) {
                updateQueueButtons.run();
                queueList.getSelectionModel().select(selected);
            }
        });
        clearUpcomingButton.setOnAction(event -> {
            playbackQueue.clearUpcoming();
            updateQueueButtons.run();
        });
        MenuItem queuePlay = new MenuItem("Play entry");
        MenuItem queueRemove = new MenuItem("Remove entry");
        MenuItem queueMoveUp = new MenuItem("Move up");
        MenuItem queueMoveDown = new MenuItem("Move down");
        MenuItem queueClear = new MenuItem("Clear upcoming");
        ContextMenu queueMenu = new ContextMenu(queuePlay, queueRemove, queueMoveUp,
                queueMoveDown, queueClear);
        queueMenu.setOnShowing(event -> {
            updateQueueActions.run();
            queuePlay.setDisable(queueList.getSelectionModel().getSelectedItem() == null);
            queueRemove.setDisable(removeQueueButton.isDisabled());
            queueMoveUp.setDisable(moveQueueUp.isDisabled());
            queueMoveDown.setDisable(moveQueueDown.isDisabled());
            queueClear.setDisable(clearUpcomingButton.isDisabled());
        });
        queuePlay.setOnAction(event -> {
            Entry entry = queueList.getSelectionModel().getSelectedItem();
            if (entry != null) {
                File file = playbackQueue.play(entry);
                if (file != null) playFile.accept(file);
            }
        });
        queueRemove.setOnAction(event -> removeQueueButton.fire());
        queueMoveUp.setOnAction(event -> moveQueueUp.fire());
        queueMoveDown.setOnAction(event -> moveQueueDown.fire());
        queueClear.setOnAction(event -> clearUpcomingButton.fire());
        queueList.setContextMenu(queueMenu);
        HBox queueActions = new HBox(8, removeQueueButton, moveQueueUp,
                moveQueueDown, clearUpcomingButton);
        VBox queuePane = new VBox(8, queueList, queueActions);
        queuePane.setPadding(new Insets(10, 0, 0, 0));
        VBox.setVgrow(queueList, Priority.ALWAYS);
        updateQueueActions.run();

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

        VBox libraryPane = new VBox(
                6,
                libraryViewControls,
                sortControls,
                searchField,
                libraryStatusRow,
                libraryBrowser,
                playlistSongControls,
                playlistMessage
        );
        libraryPane.setPadding(new Insets(10, 0, 0, 0));
        VBox.setVgrow(libraryBrowser, Priority.ALWAYS);
        TabPane mainTabs = new TabPane(
                new Tab("Library", libraryPane), new Tab("Queue", queuePane),
                new Tab("Lyrics", lyricsPane)
        );
        mainTabs.setId("main-tabs");
        mainTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        mainTabs.setMinHeight(280);

        VBox root = new VBox(
                8,
                mainTabs,
                nowPlayingRow,
                timeLabel,
                seekSlider,
                controls,
                folderStatusRow,
                playbackOptions,
                volumeControls,
                errorLabel
        );

        VBox.setVgrow(mainTabs, Priority.ALWAYS);
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
                    case LOADED -> track.getMetadata().details();
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

        if (libraryBrowser != null) {
            libraryBrowser.close();
        }
        artworkService.close();
        if (lyricsPane != null) {
            lyricsPane.close();
        }
        lyricsService.close();
        libraryService.close();

        if (audioService != null) {
            audioService.dispose();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
