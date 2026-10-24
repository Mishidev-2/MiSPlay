# MiSPlay

A personal Windows 11 desktop music player built with Java 25 and JavaFX. The project is developed in small increments, with an emphasis on understandable Java OOP, responsive controls and measured resource use.

**Status:** Phase 1B navigation checkpoint (pending Windows verification), MP3 prototype with explicit embedded-cover refresh, editable session queue, song/queue context menus and searchable album/artist browsing within All songs, Favorites and playlists, Added order and Title/Artist/Album sorting, independent saved playlist orders, expanded cached metadata, sidecar/embedded lyrics and export, saved library/preferences, folder importing and artwork. The pre-UI 24-source checkpoint compiled with Java 25/JavaFX 25.0.2 on Linux; the cover-refresh checkpoint passed 7 focused service and 2 new application checks plus 332 earlier queue/browsing/sorting regressions (341 total). Schema 7 and metadata-cache format 2 are unchanged. Windows Maven, real audio/synchronization and native-dialog checks remain local work. The visual overhaul is in progress (navigation and sidebar so far); the full Figma screens and Windows packaging remain future work.

## Phase 1B UI navigation checkpoint (Windows verification pending)

- The app now has prototype-inspired **Library / Albums / Artists / Playlists** navigation and a top-level search field. The header also exposes **Create Playlist**, alongside the existing import actions.
- **Library** displays the current virtualized song browser and provides a Play all button. **Albums / Artists** route to existing browsable groups (not yet the final Figma artwork grid and detail layouts). Library search and sort state remain bound to the existing service and browser.
- **Playlists** opens a collection view showing saved playlists and their song counts. Double-click a playlist or press Enter to open its existing editor; use **← All playlists** to return. An empty collection has a Create Playlist action. The original View dropdown remains available in the editor during this incremental migration.
- The Now Playing sidebar, Lyrics/Queue switch, shuffle/queue fix, metadata/artwork, playback and database are unchanged. Queue state remains session-only; no ratings, mini-player or compact-player implementation has been added.
- **Build and UI verification:** JavaFX 25/JDK 25/Maven are not available in the authoring environment. Run IntelliJ Maven `package` and the Phase 1B M390–M402 checks in `docs/DEVELOPMENT.md` before committing.

## Current features

### Playback

- Open a local MP3 and start a single-song playback queue.
- Pause and resume playback, or replace the current song by opening another file.
- Adjust application volume from 0% to 100%; the setting carries across track changes and is restored on later launches, including mute at 0%.
- Display elapsed time and duration, with a placeholder when duration is unavailable.
- Seek by dragging, clicking the slider track or using keyboard controls, including while paused or after completion.
- Navigate with Previous and Next through a queue captured from the visible All songs, Favorites or manual-playlist results.
- Advance automatically, with Repeat Off, Repeat All and Repeat One modes.
- Shuffle with consistent Previous/Next navigation and restoration of the captured order when shuffle is disabled. Repeated on/off cycles preserve the current song; if two or more songs remain after it, each enable changes their order. With zero or one upcoming songs there is nothing to randomize.
- Save volume, repeat mode and the shuffle switch, then restore them at startup while playback waits for a song selection.
- Combine rapid control changes into a save after about 250 ms and capture the latest values on normal close.
- Preserve the active queue during sorting, searches, imports, view changes, favorite edits and playlist edits/deletion.
- Stage songs through the song context menu with **Play next** or **Add to queue**; inspect the session queue via the Queue switch in the right sidebar. Repeated songs are separate entries. The current occurrence has a persistent green/arrow marker even after shuffle toggles or switching to Lyrics and back.
- Start an entry with double-click/Enter, remove a nonplaying entry, move upcoming entries, or clear upcoming entries without interrupting the current song.
- Display playback errors and dispose of the previous audio player when switching files or closing the application.

### Library and metadata

- Add multiple MP3s without changing playback.
- Import folders and subfolders using a cancellable background scan, with file counts and an import summary.
- Match MP3 extensions without regard to case during folder scanning, skip unreadable child entries and prevent duplicate normalized paths.
- Save imported paths and their library order in SQLite, then restore them in the background after restart without starting playback.
- Show library loading/saving status and offer Retry save when a write fails.
- Browse a virtualized song list with reusable fixed-height rows; start a song by double-clicking or pressing Enter.
- Read title, all usable artist values, album artists, album and optional track/disc numbers for imported MP3s in the background, without requiring playback.
- Read estimated duration from the MP3 audio header and cache it alongside text metadata. Playback/seek duration remains owned by MediaPlayer.
- Display two-line fixed-height library rows and expanded now-playing details. Long text has complete tooltips and rows fit the viewport.
- Prioritize the selected song's pending metadata read after the current read finishes.
- Display filename and Unknown artist/album fallbacks when tags are missing.
- Search filenames, cached titles, every artist, album artists and albums, with results updating as metadata arrives.
- Sort All songs, Favorites and playlists by Added order, Title, Artist or Album, with both A–Z and Z–A text directions. Each View/Browse combination remembers its song sort during the session. Songs defaults to Added order, Albums to Album A–Z and Artists to Title A–Z; later launches start in All songs / Songs / Added order.
- Reuse successful metadata across launches when file size and modification time still match, including successful missing-tag fallback values.
- Check metadata on startup, playback requests and reimport; refresh edited files and allow repaired files to retry without restarting.

### Metadata values

`TrackMetadata` keeps immutable artist and album-artist lists. Separate `ARTISTS` / `ALBUM_ARTISTS` fields take priority when they contain usable values; otherwise the reader keeps every standard `ARTIST` / `ALBUM_ARTIST` value. Blank values are ignored, exact duplicates are removed in order and missing artists use Unknown artist. Display names join with `; `. Commas, slashes, ampersands, semicolons and “feat.” are not guessed as delimiters: they can be part of a real artist name. A single combined credit remains a single value unless the tag supplies separate values.

Missing/blank title and album still use filename and Unknown album. Unknown track/disc numbers and duration are nullable, not zero or invented values; the current details line omits them. Track/disc totals are not stored in this checkpoint. Header duration is an estimate used before playback; the live seek slider continues to use MediaPlayer’s duration. Stored album artists provide album grouping/credits and are searchable. The Artists browser uses each stored track artist; album-artist-only values remain album credits.

The earlier reader’s `getFirst(ARTIST)` discarded later stored artist values. This checkpoint uses all usable values and adds tooltips for visually shortened details, then refreshes old caches. These address general causes of shortened credits. Yaezakura’s actual tags have not been supplied, so verify that specific file locally before considering its reported issue resolved.

### Album artwork

- Display the playing song's first stored embedded cover beside its metadata.
- Load artwork on a background worker and keep an eight-entry cache of thumbnails fitted within 192 by 192 pixels.
- Show placeholders for missing or unreadable covers and prevent an older result from replacing the selected song's artwork.
- Use **Reload cover** beside the playback options after editing the playing MP3's embedded image. Explicit Open MP3 / Add MP3s reimports invalidate those covers as well; a stale in-flight read cannot reinsert its old result.

### Manual playlists

- Create, rename and delete playlists with saved names, membership, addition sequence and independent manual order, including empty playlists.
- Switch between All songs and named playlists through the View dropdown; search within the selected view using the same library Track objects.
- Use Ctrl/Shift selection to add several songs at once. New members append in visible order, duplicates are skipped, and a song can belong to several playlists.
- Choose **Songs** and **Playlist order** to move one selected song up or down when search is clear. Returning to **Added order** restores its separate addition sequence. Shuffle still applies to playback when enabled.
- Remove selected memberships or delete a playlist without deleting library songs or music files. Playlist deletion asks for confirmation.
- Save through the existing background worker, Retry save action and normal-close flush.

### Favorites

- Mark or unmark one or several selected library songs with Favorite/Unfavorite; marked rows show a star.
- Browse, search and sort a built-in Favorites view using the same Track objects. Its Added order follows main-library import order, independently of when a song was favorited.
- Keep favorite membership independent of manual playlists. Unfavoriting keeps library rows, other memberships and music files.
- Save choices across launches through the existing background worker, Retry save action and normal-close flush, without autoplay.
- Distinguish built-in Favorites from a manual playlist named Favorites through the dropdown's Playlist: name labels.

### Lyrics

- Load a same-basename `.lrc` file beside the playing MP3 on every playback request, including Previous/Next, automatic advancement and repeat.
- Use the right-sidebar Lyrics/Queue toggle below the artwork and metadata; playback controls stay available in the bottom transport area.
- Follow the audio clock with an accented current row and automatic scrolling on line changes. Backward seeks and timed blank lines update the highlight too.
- Click a timed row, or select it and press Enter, to seek when audio duration is available. Selecting a row alone leaves playback unchanged.
- Use Reload lyrics to read a file added or edited during playback without restarting the song.
- Read UTF-8 text with an optional BOM, repeated timestamps, simultaneous lines and signed millisecond offsets. File reads and parsing run in the background; obsolete results cannot replace the selected song's lyrics.

- Fall back to lyrics stored inside the MP3 when the sidecar is missing, untimed, unreadable or over its limits.
- Read embedded ordinary text and LRC-formatted lyric text, plus supported binary synchronized ID3 lyrics with millisecond timestamps.
- Display plain embedded lyrics in their own virtualized, wrapped list with a no-timestamps hint. Plain-row selection, clicks and Enter leave playback unchanged.
- Recheck both sources on playback/reload, with source/status messages, Unicode support and stale-request guards across format changes.
- Read lyrics without editing the MP3 or adding lyric data to SQLite.
- Use **Export lyrics** to save the loaded result as UTF-8: synchronized rows become `.lrc`, while ordinary embedded words become `.txt` with paragraph breaks.
- Choose a destination through Save As and confirm replacement of an existing lyric file. Saving runs in the background; accepted writes finish on normal close.

## Technology

| Component | Current choice |
| --- | --- |
| Language and runtime | Java 25; development environment uses Eclipse Temurin 25.0.3 |
| UI and audio | JavaFX 25.0.2: `javafx-controls` and `javafx-media` |
| MP3 tags, embedded artwork and embedded lyrics | Jaudiotagger 3.0.1 |
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
- Internet access for the initial Maven dependency downloads. Normal playback, metadata, artwork and lyrics use local files.

## Run in IntelliJ IDEA

1. Open the project folder containing `pom.xml`.
2. Select JDK 25 as the project SDK and the Maven Runner JRE.
3. Open the Maven tool window and reload/sync the Maven project. Wait for dependencies to download.
4. Expand **Plugins → javafx** and run **javafx:run**.
5. Wait for the saved library data and settings to load; import, favorite, playlist and preference controls are gated during restoration. Use **Open MP3** to start a single song, or **Add MP3s** / **Add Folder** to populate All songs, then double-click a row or press Enter. Restoring saved data does not start audio.

Maven's run process remains active while the application window is open. Closing the window stops audio and background readers; database and lyric-export workers finish accepted saves before the run exits.

If standalone Maven is available on your terminal's `PATH`, run this from the project root:

```powershell
mvn javafx:run
```

IntelliJ's bundled Maven does not automatically make `mvn` available in PowerShell. A Maven Wrapper has not been added to this project yet.

## Saved library

The database is `%LOCALAPPDATA%\MiSPlay\library.db` on Windows. If `LOCALAPPDATA` is unavailable, MiSPlay uses `.misplay/library.db` under Java's `user.home` directory. The directory and database are created automatically when needed.

SQLite saves normalized absolute paths, insertion order, title, album, ordered artist/album-artist lists, optional track/disc numbers and header duration, plus each cached result's file size, modification time and cache-format version. It also stores favorite membership, playlist IDs/names/membership/manual order/addition sequence and volume, repeat mode and the shuffle switch. Audio files stay in their original locations. The playback queue, selected library view/tab/song, browse mode, group selection/search/direction, each View/Browse song-sort choice, playback position, shuffled order, artwork cache and loaded lyric lines remain session-only. Lyrics are read from local sidecars or the selected MP3’s tags and are not stored in SQLite.

Schema **7** upgrades schemas 0–6 transactionally, preserving paths, IDs, library order, preferences, playlists and Favorites. It adds an independent playlist `added_position` alongside the saved manual `position`. Older versions never stored addition history: an existing playlist’s current saved order becomes its starting Added order. Earlier addition order cannot be reconstructed after past manual moves. New additions retain their sequence independently; removing and re-adding a song puts it at the end.

Metadata-cache format stays at **2**. Existing valid expanded caches remain reusable after size/time validation; only older cache formats require fresh reads under the existing metadata rules. Music files are not rewritten. Close MiSPlay and keep a copy of `library.db` before upgrading; earlier schema-6 code cannot open schema 7.

Imports appear immediately and new paths/cache changes are saved in background batches. Favorite and playlist edits use the same worker; preference changes use it after a short UI save delay. If a save fails, current tracks/favorites/playlists/settings remain usable and **Retry save** retries pending changes, retaining newer metadata and favorite/playlist/preference snapshots over older failed writes. Normal shutdown captures the latest controls and drains accepted writes; a forced stop or unresolved storage error can leave changes unsaved.

If saved library data or settings cannot be opened, MiSPlay shows a status message and allows temporary imports, playback, favorites, playlists and settings without saving them for that launch. It does not reset an unreadable database or a schema from a newer version. Fix the storage problem and restart to try loading again.

Missing, moved or temporarily unavailable files remain listed and saved. Failed checks/reads clear stale text metadata and use filename/Unknown fallbacks. Restoring the file and selecting it for playback or reimporting its path retries metadata; automatic path repair is future work.

## Using playlists

1. Click **New playlist**, enter a name and click OK. Names are trimmed, allow 1–100 characters and must differ from other playlist names without regard to case. **All songs** is reserved.
2. Choose **All songs** from **View**, select songs with Ctrl/Shift, then click **Add to playlist...** and choose the destination. File/folder imports populate All songs; adding playlist membership is a separate action.
3. Choose **Playlist: name** from View and **Songs** from Browse. It initially shows **Added order**. For manual moves, choose **Playlist order** in Sort songs, select one song and use **Move up** or **Move down** with an empty song-search field. Album/artist groups keep moves disabled because other playlist members are hidden.
4. Double-click a song or press Enter to capture the current visible results for playback. Turn Shuffle OFF when checking manual playback order.
5. Use **Rename**, **Remove from playlist** or **Delete playlist** as needed. Shared memberships and the main library remain available.

Editing a playlist leaves an already captured playback queue intact, including songs subsequently removed from that playlist. Explicitly start playback again to capture the edited results. Each launch begins in All songs / Added order with playback stopped; named playlists and both saved orders are restored independently of the active view/queue. New members append once to both orders; removing and re-adding a member appends it again.

## Using Favorites

1. Select songs in All songs or a manual playlist, using Ctrl/Shift for multiple selections, then click **Favorite**. Their rows gain a **★** marker.
2. Choose **Favorites** from View to browse, search or sort marked songs. Added order follows main-library import order; manual move controls are disabled.
3. Select songs and click **Unfavorite** to remove only their favorite membership. With a mixed selection, both actions are available and skip songs already in the requested state.
4. Double-click or press Enter to capture the currently visible Favorites results for playback. **Add to playlist...** also works from this view.

Built-in Favorites cannot be renamed or deleted. A manual **Playlist: Favorites** retains its independent members and order. Removing favorites leaves files, manual memberships and a captured playback queue intact. Saved stars/membership return after restart while the initial view remains All songs and playback stays stopped.

## Sorting library views

Choose a View and Browse mode, then use **Sort songs** above search. Text comparisons ignore case using a locale-neutral character comparison; they do not translate names, apply language-specific alphabet rules or sort embedded numbers naturally. Missing tags use the existing filename/Unknown fallbacks.

| Sort | Result |
| --- | --- |
| Added order | Main-library import order in All songs/Favorites; that playlist’s own addition sequence in a manual playlist. |
| Title A–Z / Z–A | Title in the chosen direction; equal titles retain addition order. |
| Artist A–Z / Z–A | Complete displayed artist credit in the chosen direction, then title A–Z and addition order. |
| Album A–Z / Z–A | Album name in the chosen direction, then album artist A–Z, disc number, track number, title A–Z and addition order. Unknown disc/track numbers follow known values. |
| Playlist order | Manual playlists only: the saved order edited with Move up/down. |

Album Z–A reverses album names while keeping disc/track order ascending within each album/album-artist group. Sorts read cached metadata; newly loaded or refreshed values can move a row. Search narrows the current sorted view. Visible selections stay selected when possible, and bulk Add to playlist follows their displayed order.

Song-sort choices are remembered separately per View/Browse combination until the app closes. Songs defaults to Added order, Albums to Album A–Z and Artists to Title A–Z. They do not save a new playlist order or change the current playback queue. Double-click or press Enter to capture the displayed sorted/filtered results for a new queue; Shuffle still applies when enabled. There is no date sorting or persistent sort preference in this checkpoint.

## Browsing albums and artists

1. Choose the membership scope in **View**: All songs, Favorites or Playlist: name. Then choose **Albums** or **Artists** in **Browse**. The first available group opens without starting playback.
2. Select a group on the left to see its songs on the right. Groups and song rows are virtualized; headings/rows have complete tooltips when text is shortened. Counts describe the whole selected group within View, before song search.
3. Use **Find albums...** / **Find artists...** on the left to narrow group names or album credits. The adjacent **A–Z / Z–A** button orders that index. **Sort songs** controls the right-hand songs separately.
4. The shared song-search field above both lists still matches filename, title, artists, album artists and album within the selected group. It stays in effect when View/Browse changes; clear it to see all group songs. A song search leaves the group index/counts intact.
5. Double-click a song or select it and press Enter to capture that displayed group/song-search order for playback. Merely selecting a group does not play it. Enter in the group list moves focus to songs; a subsequent song activation starts playback.
6. Favorite/Unfavorite and Add to playlist/Remove from playlist act on selected songs. Choose Browse: Songs for manual playlist moves. Changing groups, index direction, modes, scope or searches leaves an already captured queue intact.

Albums group by album name plus the ordered album-artist credit, ignoring case. When album artists are absent, the full ordered track-artist credit is the fallback. Consistent album-artist tags join compilations with different performers; missing or inconsistent credits can split a release, while identical album/credit pairs share one group. This does not guess release IDs, folders or punctuation delimiters.

Artists groups each separately stored track-artist value. A collaboration with distinct Alice and Bob values appears under both; a single combined `Alice feat. Bob` value stays one artist. Album-artist-only names do not add performer groups. Display spelling comes from the first scoped library member; case/Unicode comparisons do not transliterate names or remove accents. Unknown metadata uses the existing Unknown album/artist fallbacks and regroups when real metadata arrives.

Index query, direction and selected group are remembered per View/Browse combination for the session. The current Browse mode continues across View switches. Metadata regrouping coalesces completions over about 100 ms; choosing a different scope updates membership immediately. No tag/file reads, artwork decoding or database writes are added by browsing. Restart rebuilds indexes from cached metadata, resets browsing controls and leaves playback stopped. This checkpoint retains schema **7** and metadata-cache format **2**.

## Refreshing an edited cover

After changing the embedded cover in an MP3, choose **Reload cover** while that song is current. The old image clears and the worker reads the file again. A removed image becomes the normal No embedded cover placeholder; adding an image after a previous no-cover result works too. Using **Open MP3** or **Add MP3s** on an edited path also invalidates its saved thumbnail in this session. A folder re-scan does not currently signal the artwork cache, so use Reload cover if the current image remains stale. This reads only artwork and does not restart or seek audio, edit tags, or change the library/queue/database.

## Using the queue and context menus

Right-click a song in the library to **Play**, **Play next**, **Add to queue**, **Add to playlist...**, **Favorite**, **Unfavorite** or **Remove from playlist**. Ctrl/Shift selection applies bulk actions to the selected songs in their currently displayed order. Right-clicking an unselected row selects that row first. Play captures the whole displayed and filtered song view as a new queue; it replaces prior queue edits. Play next inserts selected songs immediately after the current entry, preserving their selection order. Add to queue appends them, including repetitions. With no active song, Add to queue stages songs without autoplay; Play next starts the first selected song.

Select **Queue** in the right sidebar to see the actual play order. Double-click a row or select it and press Enter to play that exact occurrence. The Queue sidebar's buttons and right-click menu remove one nonplaying entry, move an upcoming entry earlier/later, or clear upcoming entries. The currently playing occurrence cannot be removed or moved. Clearing upcoming keeps history and current playback; with an idle staged queue it clears all staged entries. Previous/Next and completion use the edited order, including Repeat and Shuffle. Turning Shuffle off restores the base order, including new entries; moving an upcoming entry commits the displayed order as the new base. Metadata changes update queue row text. Queue membership, edits and position remain session-only; saved Repeat/Shuffle preferences still restore.

The existing functional Queue and Lyrics panes now share the right sidebar, below artwork and current-track metadata. Switching views does not rebuild them or modify the queue.

## Using lyrics

1. For lyrics already saved inside an MP3, start that file and open **Lyrics**. A separate `.lrc` file is optional. If you edited lyrics in Musicolet on another device, copy the **updated MP3** to the Windows location MiSPlay actually plays; edits to a different copy do not update it automatically.
2. To use a sidecar, keep the MP3 and its UTF-8 `.lrc` in the same folder with the same basename: `My song.mp3` and `My song.lrc`. Show filename extensions on Windows to avoid accidentally naming it `My song.lrc.txt`.
3. Timed lyrics have an accented row following the playback clock. Click a row to seek, or select it with the keyboard and press Enter. Unknown/zero duration, audio errors and cues beyond duration prevent lyric seeks. Plain embedded lyrics show a no-timestamps hint and can be read by scrolling; plain-row clicks, Enter and selection do not seek or track the clock.
4. Edit the sidecar or the MP3’s lyric tags, then click **Reload lyrics**. Both formats are cleared while a fresh background read selects the current source. Playback and the captured queue continue. Replaying the song also rechecks its lyrics.

The first usable source wins:

| Priority | Source | Display |
| --- | --- | --- |
| 1 | Matching sidecar with valid timed LRC entries | Synchronized rows |
| 2 | Embedded lyric text containing valid LRC timestamps | Synchronized rows |
| 3 | Supported embedded SYLT/SLT synchronized lyrics with millisecond timestamps | Synchronized rows |
| 4 | Embedded ordinary lyric text | Scrollable plain rows |

Within embedded text, the first usable timed variant wins; otherwise the first usable plain variant is retained while supported binary timing is checked. Up to 32 text variants and 32 synchronized frames are considered. There is no language/variant chooser yet. A bad sidecar allows embedded fallback rather than blocking it; if no source works, the status includes missing/invalid-source guidance. Browsing/selecting another library row alone leaves the playing song’s lyrics intact.

Musicolet-edited MP3s can be read when the words are stored in supported ID3 lyric fields. This implementation has not yet been tested with the developer’s specific files, so confirm the actual edited copy on Windows. Supported tags are ID3v2.2/2.3/2.4: ULT/USLT text and supported SLT/SYLT binary timing. The reader handles tag-supported Unicode and legacy text encodings; the separate `.lrc` reader still requires UTF-8.

A small original example of timed lyric text (usable as a sidecar, or pasted into an embedded lyric field in a tag editor):

```text
[ti:MiSPlay lyrics check]
[00:00.00]Ready for the first line
[00:05.00]The highlight follows the song
[00:10.00]
[00:15.00][00:25.00]This line appears twice
[00:20.00]Click a line to seek
```

These are demonstration words, not lyrics or timings for a particular song. The embedded-lyrics handoff includes plain and timed demonstration text under `examples/`; MiSPlay does not edit tags itself.

Supported timestamps have 1–6 minute digits, two second digits from 00–59, and an optional dot followed by 1–3 fractional digits: `[m:ss]`, `[mm:ss.xx]` and `[mm:ss.xxx]` are examples. Timestamps sort by time. Entries sharing an adjusted timestamp display together in file order; timed empty entries display **♪**. Headers and untimed/invalid-leading-timestamp lines are ignored. Recognized enhanced `<mm:ss.xx>` word markers are removed for readable line text; highlighting uses the leading line time.

The last valid signed 32-bit `[offset:N]` applies globally in milliseconds: positive values show lyrics sooner, negative values later. For example, `[offset:+500]` makes `[00:02.00]` active at 1.5 seconds. Times before zero clamp to zero. Files are limited to 1 MiB, physical lines to 8,192 characters, and expanded timed entries to 10,000; exceeding a limit produces a recovery message without partial lyrics. Save legacy-encoded files as UTF-8 before reloading.

Binary synchronized lyrics use millisecond timestamps. When a SYLT frame marks line breaks, syllable fragments are combined into lines using each line’s first timestamp; without line breaks, each encoded entry remains a timed row. Recognized equal-time entries group together. MPEG-frame-based SYLT timing is not converted in this checkpoint; ordinary embedded text can still serve as a fallback. Plain text without usable timestamps is displayed rather than assigned guessed timing.

For embedded reading, the leading ID3 tag is limited to 8 MiB including its header; a large embedded cover can make the whole tag exceed this limit even if its lyrics are small. Embedded text is limited to 262,144 Java characters, 10,000 lines/entries and 8,192 characters per line. A binary synchronized payload, including its description, is limited to 1 MiB. These are input/accepted-result bounds, not a total-memory guarantee; tag parsing can allocate temporary objects. A valid sidecar remains usable independently of the embedded tag.

Lyric problems do not stop playback. Reads/reloads do not rewrite audio, create `.lrc` files or save lyrics in SQLite. Files are written only after an explicit export. There is no continuous file watcher, lyric cache, online lookup, plain external `.txt` fallback, tag editor or automatic timestamp generation.

### Exporting lyrics

1. Load the song’s lyrics, then click **Export lyrics** in the right-sidebar Lyrics view. The button is disabled before usable lyrics arrive, during a read and while an export is saving.
2. Save As suggests the playing MP3’s basename with `.lrc` for synchronized rows or `.txt` for plain text. A missing extension is appended; an incompatible extension is rejected. Choose a destination, or cancel to leave files unchanged.
3. Confirm replacement if the resolved lyric filename already exists. Windows may also ask through its native Save As dialog. Declining either confirmation leaves the old file intact.
4. Read the result message below the lyrics; hover it for the complete message if a long filename is shortened. After a write error, choose a writable location and export again.

The export captures the displayed result **before Save As opens**. If playback advances or lyrics reload while a dialog is open or the file is saving, the export keeps the original song’s text, basename suggestion and adjusted times. Playback controls and Reload lyrics remain available while the background write runs. Exporting does not seek, edit tags, alter the queue, update SQLite or add a library song. The original MP3 is protected from direct or existing hard-link replacement; directory/symbolic-link destinations are rejected.

LRC output uses `[mm:ss.xxx]` with three millisecond digits and the current adjusted times. Already-applied offsets are not emitted again. Simultaneous translated lines receive the same timestamp on separate physical lines; a timed blank stays blank after its timestamp rather than exporting the display’s **♪** marker. Plain output retains the loaded rows and paragraph breaks without adding guessed timestamps. Both formats use UTF-8 without a BOM and LF line endings, with a final newline.

Writes are staged in the destination directory before moving into place, so encoding/size/validation failures leave an existing file intact and do not leave a partial new lyric file. Output is limited to 1 MiB, 10,000 physical entries and 8,192 characters per physical line; the LRC line limit includes its timestamp. LRC minutes must fit 1–6 digits. An otherwise readable source can exceed an export bound after expansion; export fails rather than truncating it. Replacement uses an atomic move when supported and a regular replacement move otherwise; this is not a guarantee against forced termination or filesystem failure. Normal window closure drains an accepted export without waiting on the UI thread.

To make an exported timed file the preferred source, save it beside the MP3 with the same basename and click **Reload lyrics**. Plain `.txt` exports are for reading or another editor; MiSPlay does not load them as external lyrics. Rename alone cannot create synchronization. Export preserves the loaded line-level result, not original tag descriptions, metadata headers, encoding, word-level markers or pre-offset timestamp spelling.

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
| `src/main/java/com/misplay/model/PlaybackQueue.java` | Session-only occurrence IDs, editable base/playback order, current position, navigation, repeat and shuffle |
| `src/main/java/com/misplay/model/PlaybackPreferences.java` | Immutable volume, repeat-mode and shuffle choices with safe defaults |
| `src/main/java/com/misplay/model/Playlist.java` | Immutable playlist ID/name and matching unique paths in manual and addition orders |
| `src/main/java/com/misplay/model/TrackSort.java` | View-sort choices and cached-metadata comparators with stable addition-order ties |
| `src/main/java/com/misplay/model/LibraryBrowse.java` | Pure cached-metadata album/artist grouping with structured case-folded keys and copied membership |
| `src/main/java/com/misplay/view/LibraryBrowser.java` | Browse controls, scoped virtualized group index/detail, session state and coalesced metadata regrouping |
| `src/main/java/com/misplay/service/MetadataService.java` | Read-only tag/header extraction, ordered artist values, optional numbers/duration and fallbacks |
| `src/main/java/com/misplay/service/FolderScanTask.java` | Background folder traversal, counts and cancellation checks |
| `src/main/java/com/misplay/service/ArtworkService.java` | Selected-song artwork loading, explicit cache invalidation, thumbnail caching and display properties |
| `src/main/java/com/misplay/model/LyricLine.java` | Immutable adjusted timestamp and lyric text, including simultaneous lines |
| `src/main/java/com/misplay/service/LrcParser.java` | Bounded line-timed text parsing, offsets, sorting and simultaneous grouping |
| `src/main/java/com/misplay/service/LyricsService.java` | Background source priority/fallback, status, immutable timed/plain views, latest-request guards and clock lookup |
| `src/main/java/com/misplay/service/EmbeddedLyricsReader.java` | Bounded read-only ID3 text and synchronized-lyric extraction through Jaudiotagger |
| `src/main/java/com/misplay/service/LyricsExportService.java` | Immutable export snapshots, filename validation and bounded background UTF-8 writing with staging/close draining |
| `src/main/java/com/misplay/view/LyricsPane.java` | Virtualized timed/plain views, clock following, reload, guarded seeks and export dialogs/status |
| `src/main/java/com/misplay/model/` | Track metadata/status, folder-scan results and playback queue/repeat models |
| `docs/DEVELOPMENT.md` | Architecture, manual checks and development conventions |
| `docs/GIT_SETUP.md` | Initial commit and remote repository setup |
| `CHANGELOG.md` | Human-readable record of completed changes |
| `.gitignore` | Generated files, local IDE configuration and personal audio excluded from Git |
| `.gitattributes` | Consistent text line endings |

## Verification

Run the [manual checks](docs/DEVELOPMENT.md#manual-checks) relevant to each change. Working checkpoints have been reported by the developer; the complete per-check results have not been recorded. Automated project tests and a memory/CPU profiling baseline have not been introduced yet.

The cover-refresh checkpoint passed **7 focused service checks and 2 new App-control checks**, plus the 332 earlier queue/browsing/sorting regressions: **341 total**. Generated silent MP3 files with red/blue embedded PNG covers tested changed/removed/new images and closed-service guards. All 24 production classes compiled with Azul JDK 25.0.3 / JavaFX 25.0.2. The headless Linux checks do not prove Windows native audio output or the user's actual edited files. Run M361–M369 locally. No schema, dependency or metadata-cache change.

The queue checkpoint passed **27 queue-model and 13 actual application UI checks**, plus the **292 browsing/sorting regressions: 332 total**. All 24 production sources compiled directly with Azul OpenJDK 25.0.3 / JavaFX 25.0.2. Tests used real production classes and SQLite in Linux headless Monocle 21.0.2; invalid media exercised the audio-error path. Queue checks cover duplicate occurrence identity, staged playback, insertion, removal, moves, clear, shuffle/repeat navigation, song context actions, the Queue tab and restart without queue restoration. They do not prove native Windows playback/audio output, actual track completion or dialogs. Run M345–M360 and relevant earlier checks on Windows. No schema or metadata-cache migration was added.

The browsing checkpoint passed **30 grouping-model, 24 browser-component, 53 actual application UI and 12 layout checks: 119 focused checks**. The previous **173 sorting checks were rerun: 292 total**. All 24 production sources compiled directly with Azul OpenJDK 25.0.3 / JavaFX 25.0.2 and the existing dependencies; no production class was substituted. Linux headless Monocle 21.0.2 tests cover structured/Unicode keys, compilations and fallback credits, multi-artist membership, scoped index queries/direction/session restoration, live model regrouping, close guards, sorting/moves/bulk-add, actual captured-queue Enter/Next wiring, empty profiles and restart without autoplay. Layouts were inspected at 820×780 and 760×720 with 240 tracks, 161 groups, an 80-song album, long credits and an audio error. Cached fixtures/direct model updates exercise regrouping; invalid media exercise audio errors, not real MP3 decoding. Windows Maven/audio/native dialogs, the actual Musicolet/Yaezakura files and profiling remain local checks M321–M344 plus relevant earlier cases. Metadata/lyric/export suites below were not rerun for browsing.

The sorting checkpoint passed **35 model, 61 repository/migration, 24 service, 45 application UI and 8 layout checks: 173 total**. All 22 actual production classes compiled directly with Azul OpenJDK 25.0.3 / JavaFX 25.0.2 and the project dependencies. Linux headless Monocle 21.0.2 tests used real SQLite and production services/models, including the actual queue; no production substitute was used. Checks cover all sort modes, schemas 0–6, retained cache-format-2 data, independent playlist orders, migration/write rollback, newest-state retry/close, scoped search, live metadata resorting, selections, the actual bulk-add dialog and Enter/Next queue wiring. Rendered 820×780 and 760×720 layouts were inspected with 240 tracks and long credits. Invalid/missing media fixtures exercised audio-error handling, not real MP3 playback. Windows Maven/audio/native-dialog checks and profiling remain pending; run M297–M320 and relevant earlier checks locally. Earlier metadata/lyric/export test totals below are historical and were not rerun for sorting.

The development guide distinguishes the manual checklist from development-time artwork, metadata, persistence and UI checks. Favorites passed 32 repository/migration, 20 service and 36 application/layout checks: 88 in total. The earlier playlist checkpoint's 150 checks are a separate recorded result and are not claimed as rerun for Favorites. These used a Java 17/JavaFX 21.0.1 harness with some substitute classes, so they do not establish a full Java 25/JavaFX 25.0.2 Windows build or audio regression result. A successful Maven validation alone does not compile the application or verify playback.

The LRC checkpoint passed 27 parser/model, 24 sidecar-service and 38 application/layout checks (89 focused checks). The existing 32 repository, 20 library-service and 36 Favorites/application checks also passed against this checkpoint: **177 checks in total**. Coverage included rapid/same-path reloads, stale results, UTF-8/limits, clock boundaries/backward seeks, adjusted click/Enter seeks, audio-error guards, wrapped-row following after resize, queue navigation, shutdown and restart without autoplay. The same harness/substitute limits apply. The developer subsequently reported sidecar files working; detailed Windows results for M201–M224 remain unrecorded.

The embedded checkpoint passed **40 reader, 23 source-coordination and 26 application/layout checks** (89 focused), plus all 177 existing LRC/Favorites checks: **266 total**. Independent ID3 fixtures used generated silent MP3 audio and covered ID3v2.2/2.3/2.4, Unicode/encodings, binary timing, UTF-16 descriptions, source priority, malformed/bounded inputs, format switches, read-only behavior, reloads, stale/close guards and rendered plain/timed views. The complete Java 25/JavaFX 25.0.2 Windows build, actual audio synchronization and the developer’s Musicolet files still need local checks M225–M248. The harness did not decode MP3s for playback or exercise the production shuffle algorithm.

The export checkpoint passed **56 export/service-capture checks, 34 lyric-component UI/layout checks and 2 child-process exit checks: 92 total**. Coverage includes UTF-8/Unicode, millisecond/offset round trips, simultaneous/blank lines, plain paragraphs, extension and overwrite handling, limits, failure preservation/cleanup, source protection, snapshots across dialog/track changes, busy/retry states, timed seek/plain no-seek regression and normal-close draining. The three changed/new production classes compiled alongside the real unchanged lyric reader/parser/model using JDK 17/JavaFX 21.0.1 and an AudioService substitute. Rendered component layouts were inspected at 820×780 and 760×720. The earlier 266-check embedded/LRC/Favorites suite is historical and was **not rerun** for export. These results do not establish a complete target build, native Windows dialogs or actual MP3 decoding/synchronization. Run M249–M272 locally as well as pending embedded checks.

The expanded-metadata checkpoint passed **31 model/reader, 45 repository/migration, 17 service and 23 application/UI checks: 116 focused checks**, plus **56 export/service-capture and 2 export-exit regressions: 174 total**. Tests used all actual production classes, independent ID3v2.2/2.3/2.4 fixtures with generated silent MP3 frames, real SQLite and JavaFX. The complete 21-class source compiled with Azul OpenJDK 25.0.3 and JavaFX 25.0.2; headless UI tests used Monocle 21.0.2 on Linux. Layout images were inspected at 820×780 and 760×720, including long credits. Maven package was attempted but its dependency transport could not reach the environment’s proxy; direct Java 25 compilation succeeded. These checks do not verify native Windows dialogs, actual audio output/decoding, the developer’s specific MP3s or a memory/CPU target. The earlier 266-check suite and the export checkpoint’s 34 component-UI checks were not rerun. Complete M273–M296 locally, with pending earlier lyric/export checks.

## Next milestone

- Install this cover-refresh checkpoint over the working queue checkpoint and run Windows checks M361–M369 plus relevant earlier playback/browsing/lyrics cases.
- Record a real Windows memory/CPU baseline and the remaining Windows audio, Musicolet, Yaezakura and native-dialog checks.
- Implement the main visual redesign from the supplied prototype in a later checkpoint.

## Planned features

- Playlist import/export improvements.
- Album artwork grids and additional visual list/detail layouts based on the supplied prototype.
- The supplied prototype’s navigation and bottom player, a shared Queue/Lyrics sidebar with centred lyrics, black/light/grey base colours and a saved configurable accent colour. Ratings and date-based sort controls are excluded.
- System tray controls and an optional mini-player/overlay.
- A bundled Windows runtime and executable/installer through `jpackage`.
- Manual or startup update checks.

The future Python LRC generator is a separate project. AI models are not part of normal music playback.

## Known limitations

- MP3 is the current import/playback format; other audio formats are not part of this checkpoint.
- Embedded lyrics currently cover supported MP3 ID3 fields, not every format supported by Musicolet. MPEG-frame-based SYLT timing, variant/language selection, external plain-text files, manual lyric-file selection, online lookup, word-level animation, tag editing and live watching are not implemented. File/tag/text/line/cue bounds do not cap total process memory.
- Library import order, favorite membership, playlist names/membership/manual order/addition sequence, valid text metadata and playback preferences persist; browse mode/group/query/direction, each View/Browse song-sort choice, the active queue, selected library view/tab/song, playback position, shuffled order, artwork cache and loaded lyrics are session-only. A new profile uses 50% / Repeat OFF / Shuffle OFF; saved choices override those defaults.
- Playlists contain each path once and support manual single-song moves. Playlist import/export, drag ordering and library removal are not implemented. Saves replace a complete playlist snapshot per batch; large-playlist performance has not been profiled.
- Favorites Added order follows main-library import order, not favorite-marking time; text sorting is available but manual reordering is not. Accepted save batches replace its complete membership snapshot; large-favorites performance has not been profiled.
- The queue supports repeated file occurrences and entry-level actions, but it does not save queue history or support drag-and-drop. Its current view is the switchable right sidebar; queue remains session-only.
- Browse controls and song-sort choices reset after restart. Album grouping uses names and ordered credits, without release IDs/folder heuristics; Artists uses track artists rather than album-artist-only credits. Artwork grids, artist album subpages and group-wide context menus are future UI work.
- Upgraded playlists begin their addition sequence from the saved order available at migration; previously unrecorded addition history cannot be recovered. Language-specific collation and natural-number text sorting are not implemented.
- Moved/deleted paths remain in the library. Background metadata validation detects unavailable files, but paths are not repaired automatically.
- Title and album use the first usable values; artist lists use the documented structured/standard-field priority. Combined credits are not heuristically split. Header duration can differ from live playback duration. The actual Yaezakura file remains unverified; full artist reading and tooltips are implemented, but its specific issue is not claimed resolved.
- Text metadata is checked on startup, playback requests and reimport. There is no continuous file watcher or content hash; edits preserving both size and modification time can evade detection. Previous values can remain visible while a background check is pending.
- Failed metadata reads retry on explicit playback/reimport, without an automatic retry loop. Artwork remains session-only; manual Reload cover and direct file reimport invalidate its thumbnail. Folder scanning and external edits are not watched automatically.
- Artwork uses the first stored embedded cover. Linked URLs and external cover files are not loaded, and images that cannot be decoded into readable pixels use the unavailable placeholder.
- Artwork payloads larger than 8 MiB are rejected after tag parsing. The thumbnail cache limit does not cap temporary parser/decoder allocations or total process memory.

## Performance

Low memory use, smooth scrolling and low idle CPU remain goals. The prototype has not been profiled, and no process-memory target is claimed as achieved. Measure the application separately from the IDE and Maven, and record the JDK, workload and measurement method.

## Development

Each implementation handoff includes the updated README, changelog, development guide and exact commit message. Functional UI fixes continue with the features; the main visual redesign follows stable core behavior and performance checks, before final Windows packaging.

See the [development guide](docs/DEVELOPMENT.md) for implementation principles and commit checkpoints, and the [changelog](CHANGELOG.md) for completed work.

JavaFX dependencies and launcher configuration follow the [OpenJFX Maven guide](https://openjfx.io/openjfx-docs/maven).


## Windows performance baseline (Phase 0)

Before the visual overhaul, run the read-only `tools/Measure-MiSPlay.ps1` sampler on Windows 11 and record each workload using [docs/PERFORMANCE_BASELINE.md](docs/PERFORMANCE_BASELINE.md). The user completed five pre-UI baseline scenarios on 2026-10-24; see [recorded aggregates](docs/PERFORMANCE_RESULTS_2026-10-24.md). Raw CSVs remain in the local `baseline/` folder. The agreed UI layout keeps artwork and metadata at the top of the right sidebar and switches its lower area between Lyrics and Queue; ratings are out of scope, while compact/mini modes follow the main interface.

## Phase 1 UI shell checkpoint

The UI now uses `view/PlayerShell.java` and `src/main/resources/com/misplay/player.css`. The main workspace contains the existing `LibraryBrowser`; the fixed right sidebar shows current artwork/title/metadata and switches **Lyrics**/**Queue** without recreating either pane. The bottom transport retains play/pause/previous/next, seek, volume, shuffle, repeat, cover reload and error/status controls. `MiSPlayApp` continues to own application and service bindings, menu actions, and playback state; `PlayerShell` owns only layout/toggle visibility. The centered lyric presentation uses the existing synchronized/plain lyric data.

This is the **first shell increment**, not a pixel-complete Figma implementation: full Library/Albums/Artists/Playlists top-level navigation, artwork grids/detail pages, accent color picker/persistence, compact/mini player and final layout polish remain pending. Nothing about the audio service, queue ordering, database schema or import paths changes in this checkpoint. Install and verify it using [Phase 1 instructions](docs/DEVELOPMENT.md#phase-1a-windows-checkpoint--shell-and-sidebar).
