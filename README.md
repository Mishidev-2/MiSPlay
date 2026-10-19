# MiSPlay

A personal Windows 11 desktop music player built with Java 25 and JavaFX. The project is being developed incrementally, with an emphasis on understandable Java OOP, responsive UI behavior and measured resource use.

**Status:** working MP3 playback prototype. Development tools and basic playback have been reported working on Windows 11. Library management and packaging are planned work.

## Current features

- Select a local MP3 using a file chooser.
- Automatically play selected MP3 files using the current volume setting.
- Adjust volume from 0% to 100%, retaining the setting across track changes during the current session.
- Pause and resume playback.
- Replace the current track when another MP3 is opened.
- Display the selected filename and playback errors.
- Dispose of the previous audio player when switching files or closing the application.
- Display elapsed playback time and track duration, with a placeholder while duration is unavailable.

## Technology

| Component | Current choice |
| --- | --- |
| Language and runtime | Java 25; development environment uses Eclipse Temurin 25.0.3 |
| UI and audio | JavaFX 25.0.2: `javafx-controls` and `javafx-media` |
| Build and dependency management | Maven; IntelliJ IDEA's bundled Maven works |
| Compiler plugin | `maven-compiler-plugin` 3.14.1 |
| Application launcher | `javafx-maven-plugin` 0.0.8 |
| Version control | Git |

FXML, Jaudiotagger and SQLite are not dependencies of the current prototype. They will be introduced when the corresponding features are implemented.

## Requirements

- Windows 11 is the current development and verification target.
- A JDK 25 installation.
- IntelliJ IDEA with Maven support, or a standalone Maven installation.
- Internet access for the initial Maven dependency downloads. Normal playback uses local files.

## Run in IntelliJ IDEA

1. Open the project folder containing `pom.xml`.
2. Select JDK 25 as the project SDK and the Maven Runner JRE.
3. Open the Maven tool window and reload/sync the Maven project. Wait for dependencies to download.
4. Expand **Plugins → javafx** and run **javafx:run**.
5. Click **Open MP3** and select a local file.

Maven's run process remains active while the application window is open. Closing the window should stop audio playback and finish the run.

If standalone Maven is available on your terminal's `PATH`, the equivalent command from the project root is:

```powershell
mvn javafx:run
```

IntelliJ's bundled Maven does not automatically make `mvn` available in PowerShell. A Maven Wrapper has not been added to this project yet.

## Project layout

| Path | Responsibility |
| --- | --- |
| `pom.xml` | Java version, dependencies and Maven plugins |
| `src/main/java/com/misplay/MiSPlayApp.java` | Window, controls, displayed messages and application shutdown |
| `src/main/java/com/misplay/service/AudioService.java` | Audio loading, playback, errors and player disposal |
| `docs/DEVELOPMENT.md` | Architecture, manual checks and development conventions |
| `docs/GIT_SETUP.md` | Initial commit and remote repository setup |
| `CHANGELOG.md` | Human-readable record of changes |
| `.gitignore` | Generated files, local IDE configuration and personal audio excluded from Git |
| `.gitattributes` | Consistent text line endings |

## Verification

Run the [manual playback checks](docs/DEVELOPMENT.md#manual-checks) before committing playback changes. Automated tests have not been introduced yet. The current working playback checkpoint has been confirmed by the developer; the complete manual-check matrix has not been individually recorded.

## Next milestone

- Seeking without fighting playback-driven slider updates.

## Planned features

- A virtualized song library with background folder scanning.
- Metadata and artwork reading with Jaudiotagger.
- Bounded thumbnail caching and refresh when files change.
- SQLite persistence, search and manual playlists.
- Playback queues, shuffle and repeat.
- Synchronized LRC lyrics, Unicode support and plain embedded lyrics.
- Embedded timed-lyrics support and LRC export where the source contains usable timestamps.
- System tray controls and an optional mini-player.
- A bundled Windows runtime and executable/installer through `jpackage`.

The future Python LRC generator is a separate project. AI models are not part of normal music playback.

## Performance

Low memory use, smooth scrolling and low idle CPU are goals. This prototype has not been profiled, and no memory target is claimed as achieved. Measurements should distinguish the application process from the IDE and Maven, and should record the JDK, workload and measurement method.

## Development

See the [development guide](docs/DEVELOPMENT.md) for implementation principles and commit checkpoints, and the [changelog](CHANGELOG.md) for completed work.

JavaFX dependencies and launcher configuration follow the [OpenJFX Maven guide](https://openjfx.io/openjfx-docs/maven).
