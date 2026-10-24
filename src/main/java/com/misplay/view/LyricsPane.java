package com.misplay.view;

import com.misplay.model.LyricLine;
import com.misplay.service.AudioService;
import com.misplay.service.LyricsService;
import com.misplay.service.LyricsExportService;
import javafx.animation.PauseTransition;
import javafx.beans.binding.Bindings;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.text.TextAlignment;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tooltip;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.function.Function;
import java.util.function.Predicate;

/** Lyrics follow the audio clock. Selection alone never changes playback. */
public final class LyricsPane extends VBox implements AutoCloseable {
    private final LyricsService lyrics;
    private final AudioService audio;
    private final ListView<LyricLine> list = new ListView<>();
    private final ListView<String> plainList = new ListView<>();
    private final Label status = new Label();
    private final Button reload = new Button("Reload lyrics");
    private final Button export = new Button("Export lyrics");
    private final LyricsExportService exporter = new LyricsExportService();
    private final Function<LyricsExportService.Snapshot, File> chooseExportFile;
    private final Predicate<File> confirmReplacement;
    private final IntegerProperty activeIndex = new SimpleIntegerProperty(-1);
    private final PauseTransition followDelay = new PauseTransition(Duration.millis(60));
    private final ChangeListener<Duration> clockListener;
    private final ChangeListener<javafx.collections.ObservableList<LyricLine>> linesListener;
    private final ChangeListener<javafx.collections.ObservableList<String>> plainLinesListener;
    private double elapsedMillis;
    private boolean playbackAvailable;
    private boolean closed;

    public LyricsPane(LyricsService lyrics, AudioService audio) {
        this(lyrics, audio, null, null);
    }

    /** Hosts/tests may supply dialog callbacks; the normal app uses native Save As. */
    public LyricsPane(LyricsService lyrics, AudioService audio,
            Function<LyricsExportService.Snapshot, File> chooseExportFile,
            Predicate<File> confirmReplacement) {
        super(8);
        this.lyrics = lyrics;
        this.audio = audio;
        this.chooseExportFile = chooseExportFile == null ? this::showExportDialog : chooseExportFile;
        this.confirmReplacement = confirmReplacement == null ? this::confirmReplacement : confirmReplacement;
        setPadding(new Insets(10, 0, 0, 0));
        setMinHeight(0);
        status.setId("lyrics-status");
        status.setWrapText(true);
        status.setMinWidth(0);
        status.setMaxWidth(Double.MAX_VALUE);
        status.textProperty().bind(lyrics.messageProperty());
        HBox.setHgrow(status, Priority.ALWAYS);
        reload.setId("reload-lyrics");
        reload.setMinWidth(VBox.USE_PREF_SIZE);
        reload.setDisable(true);
        reload.setOnAction(event -> lyrics.reload());
        export.setId("export-lyrics");
        export.setMinWidth(VBox.USE_PREF_SIZE);
        export.disableProperty().bind(lyrics.loadingProperty().or(exporter.savingProperty()).or(
                lyrics.linesProperty().emptyProperty().and(lyrics.plainLinesProperty().emptyProperty())));
        export.setOnAction(event -> exportLyrics());
        HBox header = new HBox(10, status, reload, export);
        header.setAlignment(Pos.CENTER_LEFT);

        Label hint = new Label();
        hint.setId("lyrics-hint");
        hint.textProperty().bind(Bindings.when(lyrics.plainLinesProperty().emptyProperty())
                .then("Click a timed line to seek, or select it and press Enter.")
                .otherwise("These lyrics have no timestamps. Scroll to read them."));
        hint.setWrapText(true);
        list.setId("lyrics-list");
        list.setMinHeight(80);
        list.itemsProperty().bind(lyrics.linesProperty());
        Label placeholder = new Label("Lyrics will appear here.");
        list.setPlaceholder(placeholder);
        list.setAccessibleText("Timed lyrics for the playing song");
        list.setTooltip(new Tooltip("The accented row follows playback; selection is independent."));
        list.setCellFactory(view -> new ListCell<>() {
            private final Label text = new Label();
            {
                text.setWrapText(true);
                text.setAlignment(Pos.CENTER);
                text.setTextAlignment(TextAlignment.CENTER);
                text.maxWidthProperty().bind(widthProperty().subtract(28));
                text.prefWidthProperty().bind(text.maxWidthProperty());
                text.textFillProperty().bind(textFillProperty());
                styleProperty().bind(Bindings.createStringBinding(
                        () -> !isEmpty() && getIndex() == activeIndex.get()
                                ? "-fx-background-color: -fx-accent;"
                                        + "-fx-text-fill: -fx-selection-bar-text; -fx-font-weight: bold;"
                                : "", indexProperty(), emptyProperty(), activeIndex
                ));
                setOnMouseClicked(event -> {
                    if (event.getButton() == MouseButton.PRIMARY
                            && event.getClickCount() == 1 && !isEmpty()) {
                        seekTo(getItem());
                    }
                });
            }

            @Override
            protected void updateItem(LyricLine line, boolean empty) {
                super.updateItem(line, empty);
                setText(null);
                if (empty || line == null) {
                    setGraphic(null);
                    setAccessibleText(null);
                } else {
                    text.setText(line.text().isEmpty() ? "♪" : line.text());
                    setGraphic(text);
                    setAccessibleText(text.getText());
                }
            }
        });
        list.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                seekTo(list.getSelectionModel().getSelectedItem());
                event.consume();
            }
        });
        plainList.setId("plain-lyrics-list");
        plainList.setMinHeight(80);
        plainList.setAccessibleText("Plain lyrics without timestamps");
        plainList.itemsProperty().bind(lyrics.plainLinesProperty());
        plainList.setCellFactory(view -> new ListCell<>() {
            private final Label text = new Label();
            {
                text.setWrapText(true);
                text.setAlignment(Pos.CENTER);
                text.setTextAlignment(TextAlignment.CENTER);
                text.maxWidthProperty().bind(widthProperty().subtract(28));
                text.prefWidthProperty().bind(text.maxWidthProperty());
                text.textFillProperty().bind(textFillProperty());
            }

            @Override
            protected void updateItem(String line, boolean empty) {
                super.updateItem(line, empty);
                setText(null);
                text.setText(empty || line == null ? "" : line);
                setGraphic(empty ? null : text);
                setAccessibleText(empty ? null : line);
            }
        });
        plainList.visibleProperty().bind(lyrics.plainLinesProperty().emptyProperty().not());
        plainList.managedProperty().bind(plainList.visibleProperty());
        list.visibleProperty().bind(plainList.visibleProperty().not());
        list.managedProperty().bind(list.visibleProperty());
        StackPane lyricViews = new StackPane(list, plainList);
        lyricViews.setMinHeight(80);
        clockListener = (observable, previous, current) -> {
            elapsedMillis = current == null ? Double.NaN : current.toMillis();
            updateActiveLine();
        };
        linesListener = (observable, previous, current) -> {
            list.getSelectionModel().clearSelection();
            activeIndex.set(-1);
            updateActiveLine();
        };
        audio.currentTimeProperty().addListener(clockListener);
        lyrics.linesProperty().addListener(linesListener);
        plainLinesListener = (observable, previous, current) -> {
            plainList.getSelectionModel().clearSelection();
            if (!current.isEmpty()) {
                plainList.scrollTo(0);
            }
        };
        lyrics.plainLinesProperty().addListener(plainLinesListener);
        followDelay.setOnFinished(event -> {
            if (!closed && activeIndex.get() >= 0) {
                list.scrollTo(activeIndex.get());
            }
        });
        // Let wrapped rows acquire their new heights before following after a resize.
        list.widthProperty().addListener(observable -> followAfterLayout());
        list.heightProperty().addListener(observable -> followAfterLayout());
        Label exportStatus = new Label();
        exportStatus.setId("lyrics-export-status");
        exportStatus.setMinWidth(0);
        exportStatus.setMaxWidth(Double.MAX_VALUE);
        exportStatus.textProperty().bind(exporter.messageProperty());
        Tooltip exportDetails = new Tooltip();
        exportDetails.textProperty().bind(exporter.messageProperty());
        exportStatus.setTooltip(exportDetails);
        exportStatus.visibleProperty().bind(exportStatus.textProperty().isNotEmpty());
        exportStatus.managedProperty().bind(exportStatus.visibleProperty());
        getChildren().addAll(header, hint, lyricViews, exportStatus);
        VBox.setVgrow(lyricViews, Priority.ALWAYS);
    }

    private void exportLyrics() {
        if (closed || export.isDisabled()) {
            return;
        }
        // Native dialogs run a nested event loop: capture before playback can advance.
        LyricsExportService.Snapshot snapshot = lyrics.snapshotForExport();
        File chosen = chooseExportFile.apply(snapshot);
        if (chosen == null || closed) {
            return;
        }
        try {
            File target = LyricsExportService.targetPath(snapshot, chosen).toFile();
            boolean replace = target.exists();
            if (replace && !confirmReplacement.test(target)) {
                return;
            }
            if (!closed) {
                exporter.export(snapshot, target, replace);
            }
        } catch (IllegalArgumentException invalid) {
            Alert error = new Alert(Alert.AlertType.ERROR, invalid.getMessage(), ButtonType.OK);
            if (getScene() != null) {
                error.initOwner(getScene().getWindow());
            }
            error.setHeaderText("Choose a lyric filename");
            error.showAndWait();
        }
    }

    private File showExportDialog(LyricsExportService.Snapshot snapshot) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export lyrics");
        chooser.setInitialFileName(snapshot.suggestedFileName());
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                snapshot.isTimed() ? "LRC lyrics (*.lrc)" : "Text lyrics (*.txt)", "*" + snapshot.extension()));
        return chooser.showSaveDialog(getScene() == null ? null : getScene().getWindow());
    }

    private boolean confirmReplacement(File target) {
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                "Replace " + target.getName() + "?", ButtonType.YES, ButtonType.NO);
        if (getScene() != null) {
            confirmation.initOwner(getScene().getWindow());
        }
        confirmation.setHeaderText("A lyric file already exists");
        return confirmation.showAndWait().orElse(ButtonType.NO) == ButtonType.YES;
    }

    /** Called before loading each audio file, including repeat and queue navigation. */
    public void beginTrack() {
        playbackAvailable = true;
        reload.setDisable(false);
        elapsedMillis = 0;
        activeIndex.set(-1);
    }

    public void playbackFailed() {
        playbackAvailable = false;
        activeIndex.set(-1);
    }

    private void updateActiveLine() {
        int index = playbackAvailable ? lyrics.indexAt(elapsedMillis) : -1;
        if (activeIndex.get() != index) {
            activeIndex.set(index);
            if (index >= 0) {
                list.scrollTo(index);
                followAfterLayout();
            }
        }
    }

    private void followAfterLayout() {
        if (!closed && activeIndex.get() >= 0) {
            followDelay.playFromStart();
        }
    }

    private void seekTo(LyricLine line) {
        Duration total = audio.durationProperty().get();
        double totalMillis = total == null ? Double.NaN : total.toMillis();
        if (playbackAvailable && line != null && !lyrics.loadingProperty().get()
                && lyrics.plainLinesProperty().isEmpty() && lyrics.linesProperty().contains(line)
                && Double.isFinite(totalMillis) && totalMillis > 0
                && line.timeMillis() <= totalMillis) {
            audio.seek(Duration.millis(line.timeMillis()));
        }
    }

    @Override
    public void close() {
        closed = true;
        export.disableProperty().unbind();
        export.setDisable(true);
        exporter.close();
        followDelay.stop();
        playbackAvailable = false;
        audio.currentTimeProperty().removeListener(clockListener);
        lyrics.linesProperty().removeListener(linesListener);
        lyrics.plainLinesProperty().removeListener(plainLinesListener);
        list.itemsProperty().unbind();
        plainList.itemsProperty().unbind();
        plainList.visibleProperty().unbind();
        plainList.managedProperty().unbind();
        list.visibleProperty().unbind();
        list.managedProperty().unbind();
        plainList.setItems(FXCollections.emptyObservableList());
        status.textProperty().unbind();
        reload.setDisable(true);
    }
}
