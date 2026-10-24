package com.misplay.view;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * The resizable main-window shell. The app owns controls and their behavior;
 * this view only positions them and switches existing queue/lyrics nodes.
 * No media, queue, persistence or lyric state is created here.
 */
public final class PlayerShell extends BorderPane {
    private static final double SIDEBAR_WIDTH = 350;

    public PlayerShell(
            Region library,
            Node queue,
            Node lyrics,
            Node artwork,
            Label title,
            Label metadata,
            Node importToolbar,
            Node mainNavigation,
            Node playbackToolbar,
            Node statusArea) {
        setId("player-shell");
        getStyleClass().add("player-shell");

        HBox header = new HBox(16);
        header.getStyleClass().add("app-header");
        header.setAlignment(Pos.CENTER_LEFT);
        Label identity = new Label("MiSPlay");
        identity.getStyleClass().add("app-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        header.getChildren().addAll(identity, spacer, importToolbar);
        VBox headerAndNavigation = new VBox(0, header, mainNavigation);
        setTop(headerAndNavigation);

        library.setMinSize(0, 0);
        library.getStyleClass().add("library-workspace");
        setCenter(library);
        BorderPane.setMargin(library, new Insets(14, 0, 14, 0));

        setRight(createSidebar(artwork, title, metadata, queue, lyrics));

        VBox footer = new VBox(8, playbackToolbar, statusArea);
        footer.setId("player-footer");
        footer.getStyleClass().add("player-footer");
        setBottom(footer);
    }

    private VBox createSidebar(Node artwork, Label title, Label metadata,
            Node queue, Node lyrics) {
        VBox sidebar = new VBox(10);
        sidebar.setId("playback-sidebar");
        sidebar.getStyleClass().add("playback-sidebar");
        sidebar.setPrefWidth(SIDEBAR_WIDTH);
        sidebar.setMinWidth(310);
        sidebar.setMaxWidth(SIDEBAR_WIDTH);
        VBox.setVgrow(sidebar, Priority.ALWAYS);
        BorderPane.setMargin(sidebar, new Insets(14, 0, 14, 14));

        StackPane coverRow = new StackPane(artwork);
        coverRow.getStyleClass().add("sidebar-artwork");
        coverRow.setAlignment(Pos.CENTER);

        title.setMaxWidth(Double.MAX_VALUE);
        metadata.setMaxWidth(Double.MAX_VALUE);
        VBox trackInfo = new VBox(5, title, metadata);
        trackInfo.getStyleClass().add("sidebar-track-info");

        ToggleButton lyricsButton = new ToggleButton("Lyrics");
        lyricsButton.setId("show-lyrics");
        ToggleButton queueButton = new ToggleButton("Queue");
        queueButton.setId("show-queue");
        ToggleGroup modes = new ToggleGroup();
        lyricsButton.setToggleGroup(modes);
        queueButton.setToggleGroup(modes);
        lyricsButton.getStyleClass().add("sidebar-mode");
        queueButton.getStyleClass().add("sidebar-mode");
        lyricsButton.setMaxWidth(Double.MAX_VALUE);
        queueButton.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(lyricsButton, Priority.ALWAYS);
        HBox.setHgrow(queueButton, Priority.ALWAYS);
        HBox switches = new HBox(6, lyricsButton, queueButton);
        switches.getStyleClass().add("sidebar-switches");

        // Keep both view nodes alive while switching: listeners, scroll state and
        // queue selection must not be reconstructed on every mode change.
        queue.setVisible(false);
        queue.setManaged(false);
        lyrics.setVisible(true);
        lyrics.setManaged(true);
        lyricsButton.setSelected(true);
        modes.selectedToggleProperty().addListener((observable, previous, selected) -> {
            // A click on the active button must not leave the sidebar blank.
            if (selected == null) {
                modes.selectToggle(previous == null ? lyricsButton : previous);
                return;
            }
            boolean showQueue = selected == queueButton;
            queue.setVisible(showQueue);
            queue.setManaged(showQueue);
            lyrics.setVisible(!showQueue);
            lyrics.setManaged(!showQueue);
        });

        if (queue instanceof Region region) {
            region.setMinHeight(0);
        }
        if (lyrics instanceof Region region) {
            region.setMinHeight(0);
        }
        StackPane switchContent = new StackPane(lyrics, queue);
        switchContent.setId("sidebar-content");
        switchContent.getStyleClass().add("sidebar-content");
        switchContent.setMinSize(0, 0);
        VBox.setVgrow(switchContent, Priority.ALWAYS);

        sidebar.getChildren().addAll(coverRow, trackInfo, switches, switchContent);
        return sidebar;
    }
}
