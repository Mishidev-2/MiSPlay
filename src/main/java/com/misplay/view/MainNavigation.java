package com.misplay.view;

import java.util.EnumMap;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * The four prototype navigation destinations. All routing stays in MiSPlayApp;
 * this component owns only selection styling and the existing search field.
 */
public final class MainNavigation extends HBox {
    public enum Section { LIBRARY, ALBUMS, ARTISTS, PLAYLISTS }

    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");
    private final ObjectProperty<Section> selectedSection =
            new SimpleObjectProperty<>(Section.LIBRARY);
    private final EnumMap<Section, Button> buttons = new EnumMap<>(Section.class);
    private boolean playlistDetailVisible;
    private final TextField search;

    public MainNavigation(TextField searchField) {
        this.search = searchField;
        setId("main-navigation");
        getStyleClass().add("main-navigation");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(8);

        for (Section section : Section.values()) {
            String title = switch (section) {
                case LIBRARY -> "Library";
                case ALBUMS -> "Albums";
                case ARTISTS -> "Artists";
                case PLAYLISTS -> "Playlists";
            };
            Button button = new Button(title);
            button.setId("nav-" + section.name().toLowerCase(java.util.Locale.ROOT));
            button.getStyleClass().add("navigation-tab");
            button.setAccessibleText("Show " + title);
            button.setOnAction(event -> select(section));
            buttons.put(section, button);
            getChildren().add(button);
        }

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        searchField.getStyleClass().add("navigation-search");
        searchField.setPrefWidth(270);
        searchField.setMinWidth(145);
        searchField.setMaxWidth(325);
        HBox.setHgrow(searchField, Priority.SOMETIMES);
        getChildren().addAll(spacer, searchField);
        selectedSection.addListener((observable, previous, current) -> {
            refreshSelection();
            // Music-wide search is not a playlist-collection search. Avoid
            // showing an input that appears to filter the overview but does not.
            boolean showSearch = current != Section.PLAYLISTS || playlistDetailVisible;
            searchField.setVisible(showSearch);
            searchField.setManaged(showSearch);
        });
        refreshSelection();
    }

    public void select(Section section) {
        if (section == null) throw new IllegalArgumentException("Section required");
        selectedSection.set(section);
    }

    /** Show music search on an opened playlist, but not the collection overview. */
    public void setPlaylistDetailVisible(boolean visible) {
        playlistDetailVisible = visible;
        boolean showSearch = getSelectedSection() != Section.PLAYLISTS || visible;
        search.setVisible(showSearch);
        search.setManaged(showSearch);
    }

    public Section getSelectedSection() {
        return selectedSection.get();
    }

    public ReadOnlyObjectProperty<Section> selectedSectionProperty() {
        return selectedSection;
    }

    private void refreshSelection() {
        Section selected = getSelectedSection();
        for (var entry : buttons.entrySet()) {
            boolean active = entry.getKey() == selected;
            entry.getValue().pseudoClassStateChanged(ACTIVE, active);
        }
    }
}
