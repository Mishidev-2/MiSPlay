package com.misplay.view;

import com.misplay.model.LyricLine;
import com.misplay.service.AudioService;
import com.misplay.service.LyricsService;
import javafx.animation.PauseTransition;
import javafx.beans.binding.Bindings;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/** Lyrics follow the audio clock. Selection alone never changes playback. */
public final class LyricsPane extends VBox implements AutoCloseable {
    private final LyricsService lyrics;
    private final AudioService audio;
    private final ListView<LyricLine> list = new ListView<>();
    private final Label status = new Label();
    private final Button reload = new Button("Reload lyrics");
    private final IntegerProperty activeIndex = new SimpleIntegerProperty(-1);
    private final PauseTransition followDelay = new PauseTransition(Duration.millis(60));
    private final ChangeListener<Duration> clockListener;
    private final ChangeListener<javafx.collections.ObservableList<LyricLine>> linesListener;
    private double elapsedMillis;
    private boolean playbackAvailable;
    private boolean closed;

    public LyricsPane(LyricsService lyrics, AudioService audio) {
        super(8);
        this.lyrics = lyrics;
        this.audio = audio;
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
        HBox header = new HBox(10, status, reload);
        header.setAlignment(Pos.CENTER_LEFT);

        Label hint = new Label("Click a timed line to seek, or select it and press Enter.");
        hint.setWrapText(true);
        list.setId("lyrics-list");
        list.setMinHeight(80);
        list.itemsProperty().bind(lyrics.linesProperty());
        Label placeholder = new Label("Timed lyrics will appear here.");
        list.setPlaceholder(placeholder);
        list.setAccessibleText("Timed lyrics for the playing song");
        list.setTooltip(new Tooltip("The accented row follows playback; selection is independent."));
        list.setCellFactory(view -> new ListCell<>() {
            private final Label text = new Label();
            {
                text.setWrapText(true);
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
        followDelay.setOnFinished(event -> {
            if (!closed && activeIndex.get() >= 0) {
                list.scrollTo(activeIndex.get());
            }
        });
        // Let wrapped rows acquire their new heights before following after a resize.
        list.widthProperty().addListener(observable -> followAfterLayout());
        list.heightProperty().addListener(observable -> followAfterLayout());
        getChildren().addAll(header, hint, list);
        VBox.setVgrow(list, Priority.ALWAYS);
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
                && Double.isFinite(totalMillis) && totalMillis > 0
                && line.timeMillis() <= totalMillis) {
            audio.seek(Duration.millis(line.timeMillis()));
        }
    }

    @Override
    public void close() {
        closed = true;
        followDelay.stop();
        playbackAvailable = false;
        audio.currentTimeProperty().removeListener(clockListener);
        lyrics.linesProperty().removeListener(linesListener);
        list.itemsProperty().unbind();
        status.textProperty().unbind();
        reload.setDisable(true);
    }
}
