# MiSPlay

A personal Windows 11 desktop music player built with Java 25 and JavaFX. The project is developed in small increments, with an emphasis on understandable Java OOP, responsive controls and measured resource use.

**Status:** MP3 prototype with sidecar LRC lyrics, saved Favorites, manual playlists, library paths, metadata and playback preferences, background file-change checks, folder importing and embedded album artwork. The developer has accepted the Favorites checkpoint. The new LRC implementation passed development-harness checks; its complete Java 25/JavaFX 25 Windows build and real-audio checks are pending locally. Embedded lyrics and Windows packaging remain upcoming work.

## Current features

### Playback

- Open a local MP3 and start a single-song playback queue.
- Pause and resume playback, or replace the current song by opening another file.
- Adjust application volume from 0% to 100%; the setting carries across track changes and is restored on later launches, including mute at 0%.
- Display elapsed time and duration, with a placeholder when duration is unavailable.
- Seek by dragging, clicking the slider track or using keyboard controls, including while paused or after completion.
- Navigate with Previous and Next through a queue captured from the visible All songs, Favorites or manual-playlist results.
- Advance automatically, with Repeat Off, Repeat All and Repeat One modes.
- Shuffle with consistent Previous/Next navigation and restoration of the captured order when shuffle is disabled.
- Save volume, repeat mode and the shuffle switch, then restore them at startup while playback waits for a song selection.
- Combine rapid control changes into a save after about 250 ms and capture the latest values on normal close.
- Preserve the active queue during searches, imports, view changes, favorite edits and playlist edits/deletion.
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
- Reuse successful metadata across launches when file size and modification time still match, including successful missing-tag fallback values.
- Check metadata on startup, playback requests and reimport; refresh edited files and allow repaired files to retry without restarting.

### Album artwork

- Display the playing song's first stored embedded cover beside its metadata.
- Load artwork on a background worker and keep an eight-entry cache of thumbnails fitted within 192 by 192 pixels.
- Show placeholders for missing or unreadable covers and prevent an older result from replacing the selected song's artwork.

### Manual playlists

- Create, rename and delete playlists with saved names, membership and independent song order, including empty playlists.
- Switch between All songs and named playlists through the View dropdown; search within the selected view using the same library Track objects.
- Use Ctrl/Shift selection to add several songs at once. New members append in visible order, duplicates are skipped, and a song can belong to several playlists.
- Move one selected song up or down when search is clear. Shuffle still applies to playback when enabled.
- Remove selected memberships or delete a playlist without deleting library songs or music files. Playlist deletion asks for confirmation.
- Save through the existing background worker, Retry save action and normal-close flush.

### Favorites

- Mark or unmark one or several selected library songs with Favorite/Unfavorite; marked rows show a star.
- Browse and search a built-in Favorites view in main-library insertion order, using the same Track objects.
- Keep favorite membership independent of manual playlists. Unfavoriting keeps library rows, other memberships and music files.
- Save choices across launches through the existing background worker, Retry save action and normal-close flush, without autoplay.
- Distinguish built-in Favorites from a manual playlist named Favorites through the dropdown's Playlist: name labels.

### Sidecar LRC lyrics

- Load a same-basename `.lrc` file beside the playing MP3 on every playback request, including Previous/Next, automatic advancement and repeat.
- Browse a separate Lyrics tab while the now-playing information and playback controls stay available below it.
- Follow the audio clock with an accented current row and automatic scrolling on line changes. Backward seeks and timed blank lines update the highlight too.
- Click a timed row, or select it and press Enter, to seek when audio duration is available. Selecting a row alone leaves playback unchanged.
- Use Reload lyrics to read a file added or edited during playback without restarting the song.
- Read UTF-8 text with an optional BOM, repeated timestamps, simultaneous lines and signed millisecond offsets. File reads and parsing run in the background; obsolete results cannot replace the selected song's lyrics.

## Technology

| Component | Current choice |
| --- | --- |
| Language and runtime | Java 25; development environment uses Eclipse Temurin 25.0.3 |
| UI and audio | JavaFX 25.0.2: `javafx-controls` and `javafx-media` |
| MP3 tags and embedded artwork | Jaudiotagger 3.0.1 |
| Saved library, favorites, playlists, metadata and playback preferences | Xerial SQLite JDBC 3.53.4.0 |
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
- Internet access for the initial Maven dependency downloads. Normal playback, metadata, artwork and sidecar lyrics use local files.

## Run in IntelliJ IDEA

1. Open the project folder containing `pom.xml`.
2. Select JDK 25 as the project SDK and the Maven Runner JRE.
3. Open the Maven tool window and reload/sync the Maven project. Wait for dependencies to download.
4. Expand **Plugins → javafx** and run **javafx:run**.
5. Wait for the saved library data and settings to load; import, favorite, playlist and preference controls are gated during restoration. Use **Open MP3** to start a single song, or **Add MP3s** / **Add Folder** to populate All songs, then double-click a row or press Enter. Restoring saved data does not start audio.

Maven's run process remains active while the application window is open. Closing the window stops audio and background readers; the database worker finishes accepted saves before the run exits.

If standalone Maven is available on your terminal's `PATH`, run this from the project root:

```powershell
mvn javafx:run
```

IntelliJ's bundled Maven does not automatically make `mvn` available in PowerShell. A Maven Wrapper has not been added to this project yet.

## Saved library

The database is `%LOCALAPPDATA%\MiSPlay\library.db` on Windows. If `LOCALAPPDATA` is unavailable, MiSPlay uses `.misplay/library.db` under Java's `user.home` directory. The directory and database are created automatically when needed.

SQLite saves normalized absolute paths, insertion order, title, artist and album, plus each cached result's file size, modification time and cache-format version. It also stores favorite membership, playlist IDs/names/membership/order and volume, repeat mode and the shuffle switch. Audio files stay in their original locations. The playback queue, selected library view/tab/song, playback position, shuffled order, artwork cache and loaded lyric lines remain session-only. Lyrics are read from their sidecar files and are not stored in SQLite.

Schema 5 adds favorite membership while retaining paths, IDs, library order, valid caches, preferences and manual playlists from schema 4. Schemas 0–3 are supported too; missing caches fill in the background and absent preferences use defaults. Favorites starts empty on upgrade, independently of any manual playlist with that name. Keep a copy of `library.db` before upgrading; earlier application code cannot open schema 5. Later launches validate file attributes and reuse unchanged tags.

Imports appear immediately and new paths/cache changes are saved in background batches. Favorite and playlist edits use the same worker; preference changes use it after a short UI save delay. If a save fails, current tracks/favorites/playlists/settings remain usable and **Retry save** retries pending changes, retaining newer metadata and favorite/playlist/preference snapshots over older failed writes. Normal shutdown captures the latest controls and drains accepted writes; a forced stop or unresolved storage error can leave changes unsaved.

If saved library data or settings cannot be opened, MiSPlay shows a status message and allows temporary imports, playback, favorites, playlists and settings without saving them for that launch. It does not reset an unreadable database or a schema from a newer version. Fix the storage problem and restart to try loading again.

Missing, moved or temporarily unavailable files remain listed and saved. Failed checks/reads clear stale text metadata and use filename/Unknown fallbacks. Restoring the file and selecting it for playback or reimporting its path retries metadata; automatic path repair is future work.

## Using playlists

1. Click **New playlist**, enter a name and click OK. Names are trimmed, allow 1–100 characters and must differ from other playlist names without regard to case. **All songs** is reserved.
2. Choose **All songs** from **View**, select songs with Ctrl/Shift, then click **Add to playlist...** and choose the destination. File/folder imports populate All songs; adding playlist membership is a separate action.
3. Choose **Playlist: name** from View. Select one song and use **Move up** or **Move down** with an empty search field to change its order.
4. Double-click a song or press Enter to capture the current visible results for playback. Turn Shuffle OFF when checking manual playback order.
5. Use **Rename**, **Remove from playlist** or **Delete playlist** as needed. Shared memberships and the main library remain available.

Editing a playlist leaves an already captured playback queue intact, including songs subsequently removed from that playlist. Explicitly start playback again to capture the edited results. Each launch begins in All songs with playback stopped; named playlists and their order are restored independently of the active view/queue.

## Using Favorites

1. Select songs in All songs or a manual playlist, using Ctrl/Shift for multiple selections, then click **Favorite**. Their rows gain a **★** marker.
2. Choose **Favorites** from View to browse or search marked songs. Its order follows All songs; manual move controls are disabled.
3. Select songs and click **Unfavorite** to remove only their favorite membership. With a mixed selection, both actions are available and skip songs already in the requested state.
4. Double-click or press Enter to capture the currently visible Favorites results for playback. **Add to playlist...** also works from this view.

Built-in Favorites cannot be renamed or deleted. A manual **Playlist: Favorites** retains its independent members and order. Removing favorites leaves files, manual memberships and a captured playback queue intact. Saved stars/membership return after restart while the initial view remains All songs and playback stays stopped.

## Using lyrics

1. Put the MP3 and its UTF-8 lyric file in the same folder with the same basename: `My song.mp3` and `My song.lrc`. On Windows, make sure extensions are visible so the lyric file is not accidentally named `My song.lrc.txt`.
2. Start the MP3 from the library or Open MP3, then select the **Lyrics** tab. Browsing/selecting another library row alone does not change the playing lyrics.
3. The accented row follows playback. Click a row to seek to its time, or move the independent selection with the keyboard and press Enter. Seeking remains available while paused; unknown/zero duration, audio errors and lines beyond the song's duration prevent lyric seeks.
4. Add or edit the sidecar, then click **Reload lyrics**. Playback and the captured queue continue; the loaded lines are replaced in the background. Replaying the song also rechecks its sidecar.

A small original example (also supplied as `examples/lyrics-demo.lrc` in the feature handoff):

```text
[ti:MiSPlay lyrics check]
[00:00.00]Ready for the first line
[00:05.00]The highlight follows the song
[00:10.00]
[00:15.00][00:25.00]This line appears twice
[00:20.00]Click a line to seek
```

These are demonstration words, not lyrics or timings for a particular song. Rename the example to match any working test MP3 with sufficient duration.

Supported timestamps have 1–6 minute digits, two second digits from 00–59, and an optional dot followed by 1–3 fractional digits: `[m:ss]`, `[mm:ss.xx]` and `[mm:ss.xxx]` are examples. Timestamps sort by time. Entries sharing an adjusted timestamp display together in file order; timed empty entries display **♪**. Headers and untimed/invalid-leading-timestamp lines are ignored. Recognized enhanced `<mm:ss.xx>` word markers are removed for readable line text; highlighting uses the leading line time.

The last valid signed 32-bit `[offset:N]` applies globally in milliseconds: positive values show lyrics sooner, negative values later. For example, `[offset:+500]` makes `[00:02.00]` active at 1.5 seconds. Times before zero clamp to zero. Files are limited to 1 MiB, physical lines to 8,192 characters, and expanded timed entries to 10,000; exceeding a limit produces a recovery message without partial lyrics. Save legacy-encoded files as UTF-8 before reloading.

No matching sidecar, an untimed file and an unreadable/invalid file have separate status messages. Lyric problems do not stop playback. Sidecars are re-read on playback/reload, with no continuous file watcher, lyric database cache, online lookup or embedded-tag fallback in this checkpoint.

## Playback preferences

A new profile starts at 50% volume, Repeat OFF and Shuffle OFF. Later launches restore saved choices before the controls become available. Invalid saved values fall back per field; valid remaining settings are retained.

Volume affects audio immediately. Saving waits for about 250 ms after the last volume/repeat/shuffle control change, while normal close captures changes made before that delay finishes. Restoring settings does not rewrite startup defaults.

Only the shuffle switch is saved. A fresh queue can produce a new shuffled order, and the previous queue, song and playback position are not resumed automatically.

## Project layout

| Path | Responsibility |
| --- | --- |
| `pom.xml` | Java version, dependencies and Maven plugins |
| `src/main/java/com/misplay/MiSPlayApp.java` | Window/tabs, controls, Favorites/playlist views, dialogs, bindings, choosers, lyric playback integration, delayed preference saves and shutdown |
| `src/main/java/com/misplay/service/AudioService.java` | Audio loading, playback, errors and player disposal |
| `src/main/java/com/misplay/service/LibraryService.java` | Observable library/playlists, immutable favorite state, metadata validation/cache, workers, editing rules, preferences and restore/save coordination |
| `src/main/java/com/misplay/persistence/LibraryRepository.java` | SQLite schema/migration, complete startup snapshots and transactional path/cache/preference/playlist/favorite writes |
| `src/main/java/com/misplay/model/PlaybackPreferences.java` | Immutable volume, repeat-mode and shuffle choices with safe defaults |
| `src/main/java/com/misplay/model/Playlist.java` | Immutable playlist ID/name and unique ordered library paths |
| `src/main/java/com/misplay/service/MetadataService.java` | Reading title, artist and album strings with fallbacks |
| `src/main/java/com/misplay/service/FolderScanTask.java` | Background folder traversal, counts and cancellation checks |
| `src/main/java/com/misplay/service/ArtworkService.java` | Selected-song artwork loading, thumbnail caching and display properties |
| `src/main/java/com/misplay/model/LyricLine.java` | Immutable adjusted timestamp and lyric text, including simultaneous lines |
| `src/main/java/com/misplay/service/LrcParser.java` | Bounded line-timed text parsing, offsets, sorting and simultaneous grouping |
| `src/main/java/com/misplay/service/LyricsService.java` | Background sidecar reads, status, immutable line views, latest-request guards and clock lookup |
| `src/main/java/com/misplay/view/LyricsPane.java` | Virtualized wrapped lyric rows, clock highlighting, automatic scrolling, reload and click/Enter seeking |
| `src/main/java/com/misplay/model/` | Track metadata/status, folder-scan results and playback queue/repeat models |
| `docs/DEVELOPMENT.md` | Architecture, manual checks and development conventions |
| `docs/GIT_SETUP.md` | Initial commit and remote repository setup |
| `CHANGELOG.md` | Human-readable record of completed changes |
| `.gitignore` | Generated files, local IDE configuration and personal audio excluded from Git |
| `.gitattributes` | Consistent text line endings |

## Verification

Run the [manual checks](docs/DEVELOPMENT.md#manual-checks) relevant to each change. Working checkpoints have been reported by the developer; the complete per-check results have not been recorded. Automated project tests and a memory/CPU profiling baseline have not been introduced yet.

The development guide distinguishes the manual checklist from development-time artwork, metadata, persistence and UI checks. Favorites passed 32 repository/migration, 20 service and 36 application/layout checks: 88 in total. The earlier playlist checkpoint's 150 checks are a separate recorded result and are not claimed as rerun for Favorites. These used a Java 17/JavaFX 21.0.1 harness with some substitute classes, so they do not establish a full Java 25/JavaFX 25.0.2 Windows build or audio regression result. A successful Maven validation alone does not compile the application or verify playback.

The LRC checkpoint passed 27 parser/model, 24 sidecar-service and 38 application/layout checks (89 focused checks). The existing 32 repository, 20 library-service and 36 Favorites/application checks also passed against this checkpoint: **177 checks in total**. Coverage included rapid/same-path reloads, stale results, UTF-8/limits, clock boundaries/backward seeks, adjusted click/Enter seeks, audio-error guards, wrapped-row following after resize, queue navigation, shutdown and restart without autoplay. The same harness/substitute limits apply; real Windows playback and the full target build remain pending. See manual checks M201–M224.

## Next milestone

- Verify sidecar LRC playback and seeking locally on Windows, then add embedded-lyrics support in a later checkpoint.

## Planned features

- Playlist import/export improvements.
- Additional metadata such as track number and improved handling of multiple artists.
- Embedded lyric display and LRC export when usable timestamps are present.
- System tray controls and an optional mini-player/overlay.
- A bundled Windows runtime and executable/installer through `jpackage`.
- Manual or startup update checks.

The future Python LRC generator is a separate project. AI models are not part of normal music playback.

## Known limitations

- MP3 is the current import/playback format; other audio formats are not part of this checkpoint.
- Lyrics currently require a matching local UTF-8 `.lrc` sidecar. There is no embedded/plain-text lyric fallback, manual lyric-file chooser, online lookup, word-by-word highlighting or automatic file watching. The stated file/line/cue bounds are input limits, not a total-process memory guarantee.
- Library paths/order, favorite membership, playlist names/membership/order, valid text metadata and playback preferences persist; the active queue, selected library view/tab/song, playback position, shuffled order, artwork cache and loaded lyrics are session-only. A new profile uses 50% / Repeat OFF / Shuffle OFF; saved choices override those defaults.
- Playlists contain each path once and support manual single-song moves. Playlist import/export, drag ordering and library removal are not implemented. Saves replace a complete playlist snapshot per batch; large-playlist performance has not been profiled.
- Favorites follows main-library insertion order and has no separate sorting or manual reordering. Accepted save batches replace its complete membership snapshot; large-favorites performance has not been profiled.
- Moved/deleted paths remain in the library. Background metadata validation detects unavailable files, but paths are not repaired automatically.
- Metadata uses the first stored title, artist and album values. Yaezakura by HoyoMix displays a shortened artist/details line; the cause remains undiagnosed.
- Text metadata is checked on startup, playback requests and reimport. There is no continuous file watcher or content hash; edits preserving both size and modification time can evade detection. Previous values can remain visible while a background check is pending.
- Failed metadata reads retry on explicit playback/reimport, without an automatic retry loop. Artwork remains session-only and can retain an older cover until eviction or restart.
- Artwork uses the first stored embedded cover. Linked URLs and external cover files are not loaded, and images that cannot be decoded into readable pixels use the unavailable placeholder.
- Artwork payloads larger than 8 MiB are rejected after tag parsing. The thumbnail cache limit does not cap temporary parser/decoder allocations or total process memory.

## Performance

Low memory use, smooth scrolling and low idle CPU remain goals. The prototype has not been profiled, and no process-memory target is claimed as achieved. Measure the application separately from the IDE and Maven, and record the JDK, workload and measurement method.

## Development

See the [development guide](docs/DEVELOPMENT.md) for implementation principles and commit checkpoints, and the [changelog](CHANGELOG.md) for completed work.

JavaFX dependencies and launcher configuration follow the [OpenJFX Maven guide](https://openjfx.io/openjfx-docs/maven).
