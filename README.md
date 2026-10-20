# MiSPlay

A personal Windows 11 desktop music player built with Java 25 and JavaFX. The project is developed in small increments, with an emphasis on understandable Java OOP, responsive controls and measured resource use.

**Status:** working MP3 prototype with saved library paths, background metadata loading, folder importing and embedded album artwork. The developer has reported the SQLite library checkpoint working. Persistent metadata caching and Windows packaging are upcoming work.

## Current features

### Playback

- Open a local MP3 and start a single-song playback queue.
- Pause and resume playback, or replace the current song by opening another file.
- Adjust application volume from 0% to 100%; the setting carries across track changes during the session.
- Display elapsed time and duration, with a placeholder when duration is unavailable.
- Seek by dragging, clicking the slider track or using keyboard controls, including while paused or after completion.
- Navigate with Previous and Next through a queue captured from the visible library results.
- Advance automatically, with Repeat Off, Repeat All and Repeat One modes.
- Shuffle with consistent Previous/Next navigation and restoration of the captured order when shuffle is disabled.
- Preserve the active queue during searches and imports.
- Display playback errors and dispose of the previous audio player when switching files or closing the application.

### Library and metadata

- Add multiple MP3s without changing playback.
- Import folders and subfolders using a cancellable background scan, with file counts and an import summary.
- Match MP3 extensions without regard to case during folder scanning, skip unreadable child entries and prevent duplicate normalized paths.
- Save imported paths and their library order in SQLite, then restore them in the background after restart without starting playback.
- Show library loading/saving status and offer Retry save when a write fails.
- Browse a virtualized song list with reusable fixed-height rows; start a song by double-clicking or pressing Enter.
- Read title, artist and album tags for imported tracks in the background, without requiring playback.
- Prioritize the selected song's pending metadata read after the current read finishes.
- Display filename and Unknown artist/album fallbacks when tags are missing.
- Search filenames and cached titles, artists and albums, with results updating as metadata arrives.
- Reuse successful metadata reads for the current session, including across queue changes and duplicate imports.

### Album artwork

- Display the playing song's first stored embedded cover beside its metadata.
- Load artwork on a background worker and keep an eight-entry cache of thumbnails fitted within 192 by 192 pixels.
- Show placeholders for missing or unreadable covers and prevent an older result from replacing the selected song's artwork.

## Technology

| Component | Current choice |
| --- | --- |
| Language and runtime | Java 25; development environment uses Eclipse Temurin 25.0.3 |
| UI and audio | JavaFX 25.0.2: `javafx-controls` and `javafx-media` |
| MP3 tags and embedded artwork | Jaudiotagger 3.0.1 |
| Saved library paths | Xerial SQLite JDBC 3.53.4.0 |
| SQLite logging binding | `slf4j-nop` 1.7.36 at runtime |
| Build and dependency management | Maven; IntelliJ IDEA's bundled Maven works |
| Compiler plugin | `maven-compiler-plugin` 3.14.1 |
| Application launcher | `javafx-maven-plugin` 0.0.8 |
| Version control | Git |

The UI is constructed directly in Java; FXML is not used. The project has no `module-info.java`. SQLite runs locally through the JDBC dependency and does not need a separately installed database server.

## Requirements

- Windows 11 is the current development and verification target.
- A JDK 25 installation.
- IntelliJ IDEA with Maven support, or a standalone Maven installation.
- Internet access for the initial Maven dependency downloads. Normal playback, metadata and artwork reading use local files.

## Run in IntelliJ IDEA

1. Open the project folder containing `pom.xml`.
2. Select JDK 25 as the project SDK and the Maven Runner JRE.
3. Open the Maven tool window and reload/sync the Maven project. Wait for dependencies to download.
4. Expand **Plugins â†’ javafx** and run **javafx:run**.
5. Wait for the saved library to load; import controls are disabled during restoration. Use **Open MP3** to start a single song, or **Add MP3s** / **Add Folder** to populate the library, then double-click a row or press Enter. Restoring saved tracks does not start audio.

Maven's run process remains active while the application window is open. Closing the window stops audio and background readers; the database worker finishes accepted saves before the run exits.

If standalone Maven is available on your terminal's `PATH`, run this from the project root:

```powershell
mvn javafx:run
```

IntelliJ's bundled Maven does not automatically make `mvn` available in PowerShell. A Maven Wrapper has not been added to this project yet.

## Saved library

The database is `%LOCALAPPDATA%\MiSPlay\library.db` on Windows. If `LOCALAPPDATA` is unavailable, MiSPlay uses `.misplay/library.db` under Java's `user.home` directory. The directory and database are created automatically when needed.

Only normalized absolute track paths and their insertion order are saved. Audio files stay in their original locations. Metadata is read again in the background on each launch; the playback queue, volume, repeat/shuffle settings and artwork cache remain session-only.

Imports appear immediately and new paths are saved in background batches. If a save fails, the tracks remain available for the current session and **Retry save** retries the pending batch. Normal shutdown drains accepted writes, but a forced stop or an unresolved storage error can leave changes unsaved.

If the saved library cannot be opened, MiSPlay shows a status message and allows temporary imports/playback without saving them for that launch. It does not reset an unreadable database or a schema from a newer version. Fix the storage problem and restart to try loading again.

Missing, moved or temporarily unavailable files remain listed and saved. Their metadata or playback may fail until the original path is available again; automatic path repair is future work.

## Project layout

| Path | Responsibility |
| --- | --- |
| `pom.xml` | Java version, dependencies and Maven plugins |
| `src/main/java/com/misplay/MiSPlayApp.java` | Window, controls, bindings, file/folder choosers and application shutdown |
| `src/main/java/com/misplay/service/AudioService.java` | Audio loading, playback, errors and player disposal |
| `src/main/java/com/misplay/service/LibraryService.java` | Observable library, path lookup, metadata/folder workers and background restore/save coordination |
| `src/main/java/com/misplay/persistence/LibraryRepository.java` | SQLite schema, ordered path loading and transactional path inserts |
| `src/main/java/com/misplay/service/MetadataService.java` | Reading title, artist and album strings with fallbacks |
| `src/main/java/com/misplay/service/FolderScanTask.java` | Background folder traversal, counts and cancellation checks |
| `src/main/java/com/misplay/service/ArtworkService.java` | Selected-song artwork loading, thumbnail caching and display properties |
| `src/main/java/com/misplay/model/` | Track metadata/status, folder-scan results and playback queue/repeat models |
| `docs/DEVELOPMENT.md` | Architecture, manual checks and development conventions |
| `docs/GIT_SETUP.md` | Initial commit and remote repository setup |
| `CHANGELOG.md` | Human-readable record of completed changes |
| `.gitignore` | Generated files, local IDE configuration and personal audio excluded from Git |
| `.gitattributes` | Consistent text line endings |

## Verification

Run the [manual checks](docs/DEVELOPMENT.md#manual-checks) relevant to each change. Working checkpoints have been reported by the developer; the complete per-check results have not been recorded. Automated project tests and a memory/CPU profiling baseline have not been introduced yet.

The development guide distinguishes the manual checklist from development-time artwork, SQLite repository, library-coordination and UI checks. Those checks used a separate headless environment and some service substitutes; they do not establish a full Windows playback regression result. A successful Maven validation alone does not compile the application or verify playback.

## Next milestone

- Save cached metadata in SQLite and detect changed files before reusing it.

## Planned features

- Persistent metadata caching and refresh when files change.
- Manual playlists and favorites, including saved ordering.
- Additional metadata such as track number and improved handling of multiple artists.
- Synchronized sidecar LRC lyrics with line highlighting and seeking.
- Embedded lyric display and LRC export when usable timestamps are present.
- System tray controls and an optional mini-player/overlay.
- A bundled Windows runtime and executable/installer through `jpackage`.
- Manual or startup update checks.

The future Python LRC generator is a separate project. AI models are not part of normal music playback.

## Known limitations

- MP3 is the current import/playback format; other audio formats are not part of this checkpoint.
- Library paths and order persist; the playback queue, volume, repeat/shuffle settings and caches are session-only. Volume starts at 50%, repeat/shuffle start OFF and no song starts automatically.
- Restored paths are not checked for existence or repaired. Metadata is reread each launch, and moved/deleted files remain in the library.
- Metadata uses the first stored title, artist and album values. Yaezakura by HoyoMix displays a shortened artist/details line; the cause remains undiagnosed.
- Cached metadata and artwork do not detect file edits. Failed metadata reads are not automatically retried during the current launch.
- Artwork uses the first stored embedded cover. Linked URLs and external cover files are not loaded, and images that cannot be decoded into readable pixels use the unavailable placeholder.
- Artwork payloads larger than 8 MiB are rejected after tag parsing. The thumbnail cache limit does not cap temporary parser/decoder allocations or total process memory.

## Performance

Low memory use, smooth scrolling and low idle CPU remain goals. The prototype has not been profiled, and no process-memory target is claimed as achieved. Measure the application separately from the IDE and Maven, and record the JDK, workload and measurement method.

## Development

See the [development guide](docs/DEVELOPMENT.md) for implementation principles and commit checkpoints, and the [changelog](CHANGELOG.md) for completed work.

JavaFX dependencies and launcher configuration follow the [OpenJFX Maven guide](https://openjfx.io/openjfx-docs/maven).
