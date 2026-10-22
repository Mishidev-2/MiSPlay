package com.misplay.view;

import com.misplay.model.LibraryBrowse;
import com.misplay.model.LibraryBrowse.Group;
import com.misplay.model.LibraryBrowse.Key;
import com.misplay.model.LibraryBrowse.Mode;
import com.misplay.model.Track;
import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Cached-metadata group browsing. All methods and listeners run on the FX thread. */
public final class LibraryBrowser extends BorderPane implements AutoCloseable {
    public record Context(String viewId, Mode mode) {}

    private static final class State {
        private String query = "";
        private boolean descending;
        private Key selected;
    }

    private final ObservableList<Track> libraryTracks;
    private final ComboBox<Mode> mode = new ComboBox<>(
            FXCollections.observableArrayList(Mode.values())
    );
    private final TextField groupSearch = new TextField();
    private final Button groupOrder = new Button("A–Z");
    private final ListView<Group> groups = new ListView<>();
    private final Label heading = new Label();
    private final VBox groupPane;
    private final VBox trackPane;
    private final SplitPane split = new SplitPane();
    private final Map<Context, State> states = new HashMap<>();
    // Null means Songs mode, with no additional group-membership filter.
    private final ReadOnlyObjectWrapper<Set<Track>> selectedTracks =
            new ReadOnlyObjectWrapper<>();
    private final PauseTransition refreshDelay = new PauseTransition(Duration.millis(100));
    private final ListChangeListener<Track> metadataListener;
    private List<Track> scopedTracks = List.of();
    private List<Group> index = List.of();
    private Context context = new Context("all-songs", Mode.SONGS);
    private boolean changingControls;
    private boolean refreshingGroups;
    private boolean closed;

    public LibraryBrowser(ObservableList<Track> libraryTracks, ListView<Track> songList) {
        this.libraryTracks = Objects.requireNonNull(libraryTracks);
        setId("library-browser");
        setMinSize(0, 0);
        mode.setId("library-browse");
        mode.setPrefWidth(130);
        mode.setAccessibleText("Browse songs, albums or artists");
        mode.setValue(Mode.SONGS);
        groupSearch.setId("browse-search");
        groupSearch.setMinWidth(0);
        groupSearch.setAccessibleText("Find an album or artist group");
        groupOrder.setId("browse-order");
        groupOrder.setMinWidth(48);
        groupOrder.setTooltip(new Tooltip("Sort the album or artist list A–Z or Z–A."));
        HBox indexControls = new HBox(4, groupSearch, groupOrder);
        HBox.setHgrow(groupSearch, Priority.ALWAYS);

        groups.setId("browse-groups");
        groups.setFixedCellSize(52);
        groups.setTooltip(new Tooltip("Choose a group. Press Enter to move to its songs."));
        groups.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Group group, boolean empty) {
                super.updateItem(group, empty);
                setGraphic(null);
                String text = empty || group == null ? null
                        : group.name() + "\n" + group.summary();
                setText(text);
                setTooltip(text == null ? null : new Tooltip(text));
                setPrefWidth(0);
            }
        });
        groups.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER && !songList.getItems().isEmpty()) {
                songList.requestFocus();
                if (songList.getSelectionModel().isEmpty()) {
                    songList.getSelectionModel().selectFirst();
                }
                event.consume();
            }
        });
        heading.setId("browse-heading");
        heading.setMinWidth(0);
        heading.setMaxWidth(Double.MAX_VALUE);
        heading.setMinHeight(Region.USE_PREF_SIZE);
        heading.setMaxHeight(40);
        Tooltip details = new Tooltip();
        details.textProperty().bind(heading.textProperty());
        heading.setTooltip(details);
        groupPane = new VBox(4, indexControls, groups);
        groupPane.setMinSize(135, 0);
        groupPane.setPrefWidth(220);
        trackPane = new VBox(4, heading, songList);
        trackPane.setMinSize(0, 0);
        VBox.setVgrow(groups, Priority.ALWAYS);
        VBox.setVgrow(songList, Priority.ALWAYS);
        setCenter(split);
        updateLayout();

        mode.valueProperty().addListener((observable, previous, current) -> {
            if (closed) {
                return;
            }
            if (current == null) {
                mode.setValue(previous == null ? Mode.SONGS : previous);
                return;
            }
            rememberControls();
            context = new Context(context.viewId(), current);
            restoreControls();
            updateLayout();
            refreshNow();
        });
        groupSearch.textProperty().addListener((observable, previous, current) -> {
            if (!changingControls && !closed) {
                state().query = current;
                renderGroups();
            }
        });
        groupOrder.setOnAction(event -> {
            if (closed) {
                return;
            }
            state().descending = !state().descending;
            groupOrder.setText(state().descending ? "Z–A" : "A–Z");
            renderGroups();
        });
        groups.getSelectionModel().selectedItemProperty().addListener(
                (observable, previous, current) -> {
                    if (!refreshingGroups && !closed) {
                        publishSelection();
                    }
                }
        );
        refreshDelay.setOnFinished(event -> refreshNow());
        metadataListener = change -> {
            boolean updated = false;
            while (change.next()) {
                updated |= change.wasUpdated();
            }
            if (updated && !closed && getMode() != Mode.SONGS
                    && refreshDelay.getStatus() != PauseTransition.Status.RUNNING) {
                // Coalesce tag completions, while allowing progress during a long import.
                refreshDelay.playFromStart();
            }
        };
        libraryTracks.addListener(metadataListener);
    }

    public ComboBox<Mode> getModeChooser() {
        return mode;
    }

    public Mode getMode() {
        return mode.getValue();
    }

    public ReadOnlyObjectProperty<Mode> modeProperty() {
        return mode.valueProperty();
    }

    public Context contextFor(String viewId) {
        return new Context(viewId, getMode());
    }

    public Set<Track> getSelectedTracks() {
        return selectedTracks.get();
    }

    public ReadOnlyObjectProperty<Set<Track>> selectedTracksProperty() {
        return selectedTracks.getReadOnlyProperty();
    }

    public void showScope(String viewId, List<Track> tracks) {
        if (closed || (context.viewId().equals(viewId) && scopedTracks.equals(tracks))) {
            return;
        }
        rememberControls();
        boolean changedView = !context.viewId().equals(viewId);
        scopedTracks = List.copyOf(tracks);
        context = new Context(viewId, getMode());
        if (changedView) {
            restoreControls();
        }
        refreshNow();
    }

    private State state() {
        return states.computeIfAbsent(context, ignored -> new State());
    }

    private void rememberControls() {
        state().query = groupSearch.getText();
    }

    private void restoreControls() {
        changingControls = true;
        try {
            groupSearch.setText(state().query);
            groupOrder.setText(state().descending ? "Z–A" : "A–Z");
        } finally {
            changingControls = false;
        }
    }

    private void updateLayout() {
        boolean browsingGroups = getMode() != Mode.SONGS;
        heading.setVisible(browsingGroups);
        heading.setManaged(browsingGroups);
        if (browsingGroups) {
            split.getItems().setAll(groupPane, trackPane);
            split.setDividerPositions(0.30);
            groupSearch.setPromptText(getMode() == Mode.ALBUMS ? "Find albums..." : "Find artists...");
        } else {
            split.getItems().setAll(trackPane);
        }
    }

    private void refreshNow() {
        refreshDelay.stop();
        if (closed) {
            return;
        }
        index = getMode() == Mode.SONGS ? List.of()
                : LibraryBrowse.groups(scopedTracks, getMode());
        renderGroups();
    }

    private void renderGroups() {
        if (closed) {
            return;
        }
        if (getMode() == Mode.SONGS) {
            refreshingGroups = true;
            try {
                groups.getItems().clear();
            } finally {
                refreshingGroups = false;
            }
            selectedTracks.set(null);
            return;
        }
        Key wanted = state().selected;
        List<Group> visible = new ArrayList<>(index.stream()
                .filter(group -> group.matches(groupSearch.getText())).toList());
        if (state().descending) {
            Collections.reverse(visible);
        }
        refreshingGroups = true;
        try {
            groups.getItems().setAll(visible);
            groups.getSelectionModel().select(visible.stream()
                    .filter(group -> group.key().equals(wanted))
                    .findFirst().orElse(visible.isEmpty() ? null : visible.getFirst()));
            groups.setPlaceholder(new Label(index.isEmpty()
                    ? "No " + getMode().toString().toLowerCase(java.util.Locale.ROOT) + " in this view."
                    : "No matching groups."));
        } finally {
            refreshingGroups = false;
        }
        publishSelection();
    }

    private void publishSelection() {
        Group selected = groups.getSelectionModel().getSelectedItem();
        state().selected = selected == null ? null : selected.key();
        Set<Track> members = selected == null ? Set.of() : Set.copyOf(selected.tracks());
        heading.setText(selected == null ? "Choose an album or artist."
                : selected.name() + "\n" + selected.summary());
        if (!Objects.equals(members, selectedTracks.get())) {
            selectedTracks.set(members);
        }
    }

    @Override
    public void close() {
        closed = true;
        refreshDelay.stop();
        libraryTracks.removeListener(metadataListener);
    }
}
