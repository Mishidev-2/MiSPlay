package com.misplay.view;

import com.misplay.model.Playlist;
import java.util.Objects;
import java.util.function.Consumer;
import javafx.beans.binding.Bindings;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * A lightweight playlist collection screen. The existing editor/list view
 * remains the destination when a playlist is opened; no snapshots are edited here.
 */
public final class PlaylistsOverview extends BorderPane {
    public PlaylistsOverview(ObservableList<Playlist> playlists,
            Consumer<Playlist> openPlaylist, Runnable createPlaylist) {
        Objects.requireNonNull(playlists);
        Objects.requireNonNull(openPlaylist);
        Objects.requireNonNull(createPlaylist);
        setId("playlists-overview");
        getStyleClass().add("playlists-overview");
        setMinSize(0, 0);

        Label title = new Label("Your playlists");
        title.getStyleClass().add("workspace-title");
        Label subtitle = new Label();
        subtitle.getStyleClass().add("workspace-subtitle");
        subtitle.textProperty().bind(Bindings.size(playlists).asString("%d playlists · Made by you"));
        VBox description = new VBox(5, title, subtitle);
        Button create = new Button("Create Playlist");
        create.setId("overview-create-playlist");
        create.getStyleClass().add("action-primary");
        create.setOnAction(event -> createPlaylist.run());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox heading = new HBox(12, description, spacer, create);
        heading.setAlignment(Pos.CENTER_LEFT);
        heading.getStyleClass().add("workspace-heading");
        setTop(heading);

        ListView<Playlist> list = new ListView<>(playlists);
        list.setId("playlist-overview-list");
        list.getStyleClass().add("playlist-overview-list");
        list.setFixedCellSize(70);
        Label empty = new Label("No playlists yet. Select Create Playlist to start one.");
        empty.setWrapText(true);
        list.setPlaceholder(empty);
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Playlist playlist, boolean isEmpty) {
                super.updateItem(playlist, isEmpty);
                if (isEmpty || playlist == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                Label name = new Label(playlist.name());
                name.getStyleClass().add("playlist-overview-name");
                Label details = new Label(playlist.files().size() + " songs");
                details.getStyleClass().add("workspace-subtitle");
                VBox contents = new VBox(5, name, details);
                contents.setPadding(new Insets(8, 12, 8, 12));
                setGraphic(contents);
                setText(null);
            }
        });
        list.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                Playlist selected = list.getSelectionModel().getSelectedItem();
                if (selected != null) openPlaylist.accept(selected);
            }
        });
        list.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                Playlist selected = list.getSelectionModel().getSelectedItem();
                if (selected != null) openPlaylist.accept(selected);
                event.consume();
            }
        });
        setCenter(list);
        BorderPane.setMargin(list, new Insets(10, 0, 0, 0));
    }
}
