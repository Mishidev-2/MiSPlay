# Development guide

## Scope and approach

MiSPlay is a personal Windows 11 desktop project developed in small, working increments. Each increment should have a clear behavior to verify. Keep the architecture understandable and add dependencies when a feature needs them.

The current checkpoint includes MP3 playback, captured queues with repeat/shuffle, saved library paths/order and text metadata, saved playback preferences, background cache validation, folder importing and embedded album artwork. SQLite schema 3 retains cached tags and stores volume, repeat mode and the shuffle switch. Manual playlists are the next proposed checkpoint. Favorites, lyrics, tray/overlay behavior, updates and Windows packaging remain future work.

## Current architecture

| Class | Owns | Does not own |
| --- | --- | --- |
| `MiSPlayApp` | Controls, bindings, choosers, playback actions, delayed preference saves, startup guards and shutdown coordination | Audio decoding, tag parsing or SQL execution |
| `AudioService` | The current `MediaPlayer`, playback/seek/volume requests, callbacks, audio errors and disposal | Library imports, UI controls or metadata/artwork caching |
| `LibraryService` | Observable tracks, metadata validation/cache, workers, current/active/pending preferences, restore/save scheduling and storage status | Audio playback, artwork caching or SQL execution |
| `LibraryRepository` | Database schema/migration, consistent library/settings snapshots and transactional path/cache/preference writes | JavaFX controls, worker scheduling, tag parsing or audio bytes |
| `Track` | A file, observable metadata/status and normalized search matching | File reading, worker scheduling or controls |
| `TrackMetadata` | Immutable title, artist and album strings | Parser objects, artwork or loading state |
| `MetadataStatus` | PENDING, LOADING, LOADED and FAILED states | Worker execution or persistence |
| `MetadataService` | Reading tag strings and applying missing-field fallbacks | Controls, scheduling or artwork caching |
| `FolderScanTask` | Recursive folder traversal, discovered paths, counts and cancellation checks | Adding tracks to the library or changing playback |
| `FolderScanResult` | An unmodifiable copy of discovered files and a skipped-entry count | Scan execution or library changes |
| `ArtworkService` | Selected-song artwork properties, its worker, latest waiting request and bounded thumbnail cache | Audio playback, library metadata reads or remote-image fetching |
| `PlaybackQueue` | Captured original/active orders, position, navigation, repeat and shuffle rules | Controls, audio resources or file reading |
| `PlaybackPreferences` | Immutable volume, repeat mode and shuffle choices, validation and defaults | Queue membership/order, playback position, controls or SQL |
| `RepeatMode` | OFF, ALL and ONE choices | Queue navigation or playback execution |

`LibraryRepository` also declares three nested transfer records: `CachedMetadata` contains tag strings and file attributes, `SavedTrack` contains a file and optional cache, and `LibraryState` contains copied track entries plus preferences loaded together. A null cache represents a cache miss when loading or a request to clear cached fields when saving.

### Startup and saved library

`MiSPlayApp` shows the window, then calls `LibraryService.restoreLibrary()` once. Loading runs on a dedicated database worker. Open MP3, Add MP3s, Add Folder, volume, repeat and shuffle controls stay disabled until restoration finishes; the list placeholder and storage row show loading status.

`LibraryRepository.defaultDatabasePath()` uses `%LOCALAPPDATA%\MiSPlay\library.db`, falling back to `.misplay/library.db` under Java's `user.home` when `LOCALAPPDATA` is missing or blank. Its constructor normalizes the path without touching the disk. Opening a connection creates the parent directory and database when needed. No separate SQLite server or `module-info.java` is required; Maven supplies `sqlite-jdbc` 3.53.4.0 and runtime `slf4j-nop` 1.7.36. The launcher's native-access option includes `ALL-UNNAMED` for the JDBC dependency.

Schema version 3 is recorded with `PRAGMA user_version`. Startup upgrades earlier schemas transactionally: schemas 0/1 receive cached-tag columns, and schema 2 retains those columns/data while gaining a preferences table. Saved paths, IDs and insertion order are not replaced. The track table remains:

```sql
CREATE TABLE IF NOT EXISTS library_tracks (
    id INTEGER PRIMARY KEY,
    path TEXT NOT NULL UNIQUE,
    title TEXT,
    artist TEXT,
    album TEXT,
    file_size INTEGER,
    modified_time TEXT,
    metadata_version INTEGER
);
```

Metadata-cache format version 1 is separate from database schema version 3. Bump the cache-format constant when tag interpretation or fallback rules change, so older results become misses. Missing fields, negative sizes and unsupported cache-format versions are treated as uncached metadata rather than rejecting the saved paths.

`loadState()` reads paths/caches with `ORDER BY id` and the preferences row in one read transaction. It validates absolute paths and returns normalized files without checking existence or starting playback. On the JavaFX thread, `LibraryService` assigns preferences, registers Tracks and schedules background cache checks without reinserting paths. Preferences are assigned before `restoringLibrary` becomes false. The app applies them to audio/queue/controls under a guard suppressing save requests. The original insertion order becomes the library order; filesystem traversal order is not guaranteed for a newly imported folder.

An initial restore/migration failure disables saving for that launch, displays a status message and enables temporary imports/playback/settings after loading finishes. An unreadable/corrupt database or schema version above 3 is not silently reset or replaced. Fix the storage problem and restart to retry restoration. Missing paths remain saved even if metadata or audio loading fails. Keep a database backup before upgrading; earlier application code cannot open schema 3.

### Saving imported paths

After restoration, `addFiles` registers new normalized paths on the JavaFX thread, schedules metadata checks and queues only newly added paths for insertion. Known paths retain their Track identity and receive a deduplicated recheck without duplicate inserts. Successful folder imports use the same path.

One database task runs at a time. An insertion-ordered pending set coalesces new paths while a batch is active. A pending metadata map retains the latest result or clear request for each path. Tag results can coalesce until 32 pending changes or the end of the metadata queue; imports, save completion and normal close can also flush smaller batches.

The repository opens/closes a connection per operation and uses a three-second SQLite busy timeout. `saveChanges` inserts paths with prepared parameters and `ON CONFLICT(path) DO NOTHING`, updates cached columns and optionally upserts preferences in one transaction. Null preferences leave existing settings unchanged. Cache/settings updates never reorder paths. SQL failures or an update for an unknown path roll back the whole batch. Library membership remains append-only; there is no removal or full-library snapshot replacement.

The storage row shows restoration/saving activity. A failed write restores paths, metadata changes and settings to pending state, sets the save-failed property and pauses automatic writes. New imports/results/settings can still join pending work. Newer waiting metadata/preferences win over older failed snapshots. Retry save clears the failure state and attempts pending changes again. Import, search and playback continue while saves run.

Paths, insertion order, valid title/artist/album caches and playback preferences persist. Loading status, artwork and active queue/song/position/order are rebuilt or reset each launch. Normal close attempts to finish accepted writes; forced termination or an unresolved storage error can leave paths/cache/settings updates unsaved.

### Opening a track

1. Open MP3 shows a file chooser. Cancelling leaves the library and playback unchanged.
2. After restoration has finished, the selected file is imported through `LibraryService.addFiles`, preserving an existing Track for a known path. A new path queues metadata and a database save when persistence is available. `PlaybackQueue.start` creates a single-song queue.
3. The shared `playFile` action binds the now-playing metadata labels, calls `ArtworkService.showArtwork`, clears the previous audio error and updates playback controls and the visible list selection.
4. `AudioService.loadAndPlay` disposes of the previous player, creates `Media` from the file URI and creates a replacement `MediaPlayer` with its callbacks attached. The player receives the current restored or edited volume; 0.5 (50%) is the fallback when no valid saved volume exists.
5. Playback is requested. JavaFX completes media preparation asynchronously; metadata and artwork can finish independently.

Row activation, manual navigation and automatic advancement also use `playFile`. When the current song is visible in the filtered list, it is selected and scrolled into view.

Play and Pause delegate to service methods rather than maintaining a separate UI playback flag. Natural completion delegates to the queue rules described below.

### Error reporting

The UI passes a `Consumer<String>` to `AudioService`. The service calls this callback with an error message; the UI displays it and disables the playback buttons. Selecting another file enables another playback attempt.

Synchronous construction failures are caught by `loadAndPlay`. Later media/player errors are handled by callbacks. `handleError` checks which player reported the error so an obsolete player cannot clear its replacement.

### Resource ownership

- `AudioService` holds at most one active player and disposes of the previous player before replacing it.
- Disposal clears the service's player reference before releasing audio resources.
- `LibraryService` owns the current launch's Track objects, path lookup, metadata/scanning/database workers and active/pending save snapshots, including preferences.
- `LibraryRepository` owns blocking SQL operations; each operation closes its statements and connection and runs on the database worker.
- `ArtworkService` owns the artwork worker and thumbnail cache; Track metadata remains strings only.
- `MiSPlayApp` owns the preference save delay and restoration/shutdown guards. Its stop method captures the latest settings before closing the library service.
- `Application.stop()` also disposes of now-playing bindings, closes artwork/library services and disposes of the audio player.
- Use file URIs for `Media`; do not construct media locations by manually concatenating Windows path separators.

### Volume control

`AudioService` stores the current volume as a number from 0.0 to 1.0. `setVolume()` keeps the value within that range and updates the active player when one exists.

`MiSPlayApp` displays a slider from 0 to 100. Its value-change listener divides the percentage by 100 before passing it to `AudioService.setVolume()`.

Every replacement player receives the stored volume. Volume can also be adjusted before opening a track.

Startup restores a valid saved volume before controls become available. Without a valid saved choice, volume uses 50%. A saved 0% remains muted rather than being mistaken for a missing value.

### Playback preferences

`PlaybackPreferences` is an immutable record containing volume from 0.0 to 1.0, a non-null `RepeatMode` and a shuffle flag. Its defaults are 0.5, OFF and false. Invalid caller volume (including NaN/infinity) and a null repeat mode are rejected.

Schema 3 adds this singleton table:

```sql
CREATE TABLE playback_preferences (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    volume REAL NOT NULL,
    repeat_mode TEXT NOT NULL,
    shuffle_enabled INTEGER NOT NULL
);
```

An absent row uses defaults without inserting one. Invalid stored volume/mode/shuffle values fall back per field, retaining other valid choices. Loading and applying defaults never automatically rewrite the row. Actual edits save a complete validated snapshot with a parameterized `ON CONFLICT(id) DO UPDATE`.

`LibraryService.getPlaybackPreferences()` exposes the current immutable choices. Its setter is guarded while restoring, ignores unchanged snapshots and retains session-only choices if persistence is disabled. Active and pending snapshots are separate so newer waiting choices survive an older write failure.

Volume/repeat/shuffle handlers update runtime audio/queue state immediately, then restart a 250 ms `PauseTransition`. When it finishes, `MiSPlayApp` captures the latest choices for background saving. Applying restored controls suppresses save requests. Timing follows JavaFX pulse scheduling; no SQL runs in the animation callback.

Normal close stops the delay and captures any last change before setting the shutdown guard and closing services. Closing during startup skips this capture, so temporary defaults cannot overwrite saved choices. The shutdown guard also prevents a late restoration-state change from applying controls after stop.

Only volume, repeat mode and the shuffle switch are saved. Queue membership, selected song, playback position and the particular shuffled order remain session-only. A fresh queue can generate a new order with the saved switch ON. Preference restoration leaves the queue empty and playback stopped.

### Playback queue

`PlaybackQueue` is a plain Java class containing the captured original order, active playback order, current position, repeat mode and shuffle flag. Its entries reference files rather than loaded audio.

Activating a row passes its Track to `startQueue`. That action converts the current filtered entries into a `List<File>` and passes it, together with the selected file, to `PlaybackQueue.start`.

The queue captures its file order using `List.copyOf`. Later metadata changes, searches and imports leave it intact. Activating another row creates a new queue. Open MP3 creates a single-song queue.

The shared `playFile` action updates metadata/artwork, audio playback, visible selection and navigation buttons. Previous/Next use the active queue without rebuilding it and start the selected song even if playback was paused. Volume carries over.

Without a valid saved choice, repeat defaults to OFF. Otherwise startup restores the saved mode. Its behavior is:

| Mode | Natural completion | Manual Previous and Next |
| --- | --- | --- |
| OFF | Advance to the next track; remain silent at zero after the final track. | Stop at the queue boundaries. |
| ALL | Advance and wrap from the final track to the first. | Wrap around a queue containing multiple tracks. |
| ONE | Replay the current track. | Move normally, stopping at the queue boundaries. |

Previous and Next are disabled for empty and single-song queues in every mode. A single song can still repeat automatically in ALL or ONE.

Changing repeat mode updates the controls without starting, pausing or restarting playback. Starting another queue preserves the choice; later launches restore the saved mode.

### Shuffle playback

Without a valid saved choice, shuffle defaults to OFF. Its switch is restored at startup and controlled through a JavaFX `ToggleButton`.

`originalTracks` stores the file order captured when the queue starts. `tracks` stores the active playback order. Both lists contain file references rather than loaded audio.

Enabling shuffle during an existing queue preserves entries up to and including the current position. Only entries after that position are shuffled.

Starting a new queue while shuffle is enabled places the selected song first and shuffles all remaining entries.

Shuffling uses a temporary `ArrayList` and `Collections.shuffle`. The resulting order is stored using `List.copyOf`.

Previous and Next move through the same active order. Repeat All loops through that order again. Repeat One replays the current song, while manual navigation continues to use the active order.

Disabling shuffle restores `originalTracks` and finds the current song's position within it. Subsequent navigation follows that original captured order.

Toggling shuffle updates queue state and controls without reloading audio, preserving playback position and paused state.

Searches and imports do not change the active queue. Starting another queue preserves the shuffle switch; later launches restore that switch without restoring the earlier queue/order.

### Seeking

The seek slider represents playback position in seconds. Its maximum follows the track duration. It is disabled when duration is unavailable, non-finite or zero.

Automatic slider updates use the `updatingSeekSlider` flag to avoid triggering another seek. Position updates leave the handle alone while the user is dragging it.

Dragging seeks when the handle is released. Track clicks and keyboard adjustments also request seeks. Keyboard adjustments use a five-second increment.

`AudioService.seek()` rejects invalid values and clamps requested positions between zero and the track duration.

### Track completion

When the active player finishes while playing, `AudioService` requests a stop and invokes its stored `onTrackFinished` callback.

`MiSPlayApp` handles this callback by calling `PlaybackQueue.nextAfterCompletion()`. If a file is returned, the shared `playFile` action starts it and updates the playback display, visible selection and navigation buttons. Volume carries over.

If no file is returned, playback remains silent at zero. The stopped-state handler pauses the still-active player so seeking and replay remain available.

Manual Next uses `PlaybackQueue.next()` instead. Keeping these actions separate allows Repeat One to replay a completed song while still allowing the user to skip it manually.

The completion callback remains registered when individual players are replaced. Player identity checks prevent delayed callbacks from affecting a replacement player.

### Song list

`LibraryService.getTracks()` exposes an unmodifiable `ObservableList<Track>`. A normalized absolute-path map preserves one Track object per restored or imported path during the launch. Restarting creates new models from the saved paths in insertion order.

The source list uses an extractor observing each track's `metadataProperty()`, so metadata changes produce list-update events.

A `ListView<Track>` displays filtered entries through reusable cells with a fixed height of 32 pixels. Each populated cell binds its text to metadata and displays title — artist. Pending tracks use filename and Unknown artist fallbacks until their background read finishes; small files may update too quickly to observe the fallback.

When a cell is reused, `updateItem` unbinds its text, disposes of its previous binding and clears empty-cell content before binding to another Track. Cell rendering uses in-memory values and does not read tags or decode artwork.

Double-clicking a populated row or pressing Enter on a selected row invokes `startQueue` with its Track. Open MP3 imports and starts a single-song queue. Add MP3s and successful folder scans import entries without changing playback or the active queue.

### Library search

The search-field listener strips surrounding whitespace and converts the query to lowercase using `Locale.ROOT`.

`Track.matchesSearch` checks the filename and cached title, artist and album using the same lowercase convention. Matching uses substring searches. Empty and whitespace-only queries include all tracks.

The library extractor reports metadata changes to the `FilteredList`, allowing tracks to enter or leave the current results without editing the query. For example, Unknown artist matches can disappear when an artist loads, while an artist or album search can gain matching entries.

Filtering uses in-memory values. Newly imported tracks receive metadata in the background without requiring playback; embedded fields become searchable only after the corresponding read completes.

Changes to search results do not modify the captured playback queue or interrupt playback.

### Now-playing metadata

`MetadataService` uses Jaudiotagger 3.0.1 to read an MP3's tags. It retrieves the first stored value for TITLE, ARTIST and ALBUM.

Missing or blank titles fall back to the filename. Missing artists and albums use Unknown artist and Unknown album.

The service returns a `TrackMetadata` record containing strings and does not retain parser objects. `ArtworkService` reads embedded artwork separately and caches resized image pixels.

`LibraryService` automatically schedules metadata validation when tracks are restored or imported. Unchanged cached files skip parsing; misses and changed files use `MetadataService`. Library rows and search results update as results arrive, without requiring playback.

The shared `playFile` action calls `loadMetadata`, covering Open MP3, row activation, manual navigation and automatic queue advancement.

`loadMetadata` releases the previous label bindings and binds the now-playing labels to the selected Track's metadata and loading status. Cached metadata appears immediately. Pending tracks display their filename fallback and `Waiting for tags...` or `Reading tags...` as appropriate.

Requesting playback asks `LibraryService` to prioritize a metadata check after the current check/read finishes, including for loaded or failed Tracks. This catches edits or repaired files. Completed results update their own Track objects; labels observe only the selected Track, so another result cannot replace the current song's details. Already displayed values can remain visible while revalidation is pending.

Check/read failures replace stale text with filename/Unknown fallbacks, mark the Track FAILED, queue a stored-cache clear and show `Tags unavailable — showing filename.` for the selected track. Exceptions are logged independently of audio errors.

Known issue: Yaezakura displays a shortened artist/details line even though Windows Properties lists artist and album information. The cause remains undiagnosed and investigation is deferred.

### Persistent metadata cache

`LibraryService` maintains normalized-path maps for Track identity and cached metadata, plus a waiting deque/set that deduplicate requests. Reimporting a known path preserves its model and queues a check. Playback requests move a waiting check to the front; requesting the currently active Track does not enqueue another concurrent read.

A new Track contains filename and Unknown artist/album fallbacks. Its status starts PENDING and progresses through LOADING to LOADED or FAILED. Revalidation can move an existing model through LOADING again. `isMetadataLoaded()` returns true only for LOADED.

The metadata worker reads `BasicFileAttributes`, rejects non-regular files and compares size plus `lastModifiedTime().toString()` with the cache. Full timestamp text avoids deliberately truncating available precision to milliseconds. A match returns cached strings without calling the tag parser. Restored cache values are applied to Track properties only after this background validation.

A miss reads tags, then checks size, modification time and file identity where available against the attributes captured before parsing. If they changed, it retries the read once using the newer attributes. Continued changes produce a failure instead of caching an unstable result. There is no file lock or full-file hash.

A successful validation/read calls `Track.setMetadata` on the JavaFX Application Thread. Fresh reads also queue persistence; pure cache hits do not rewrite the database. Successful reads with missing tags cache their fallback values. Switching songs does not cancel a read; its result remains useful on its own Track.

A failed check/read clears the in-memory result, queues nullable database fields, uses fallback text and lets the next waiting Track proceed. The path remains a member. Selecting the file for playback or reimporting it explicitly retries after repair; failures do not trigger an automatic retry loop. Database clears/updates still depend on successful saving.

Validation runs on startup, playback requests and reimport. It does not continuously watch files. An edit preserving size and modification time can evade detection. Artwork remains a separate session cache with no equivalent validation; its old cover can survive until eviction or restart. Metadata and artwork can still perform separate tag reads for the selected song.

### Folder importing

Add Folder opens a `DirectoryChooser`. Selecting a folder asks `LibraryService.scanFolder` to start a `FolderScanTask` on a dedicated background executor.

The task searches the selected folder and subfolders for regular files with a case-insensitive `.mp3` extension. Symbolic links are not followed.

Scanning collects file paths without modifying the library. A successful task returns a `FolderScanResult` with an unmodifiable copy of its file list and a skipped-entry count. The JavaFX completion handler calls `LibraryService.addFiles`, which prevents duplicate paths, schedules metadata reads and queues new paths for saving when persistence is available.

Unreadable child entries are logged and skipped. Failure to access the selected root fails the scan without importing its collected files.

The UI displays an indeterminate spinner, checked-file/discovered-MP3 counts and Cancel scan. Counts are reported periodically; the total is not known in advance, so this is not a percentage. Add Folder is disabled while a scan is active.

Cancelling an active scan discards its collected results and queues no database insert for them. Cancelling the folder chooser changes nothing. A completed scan reports newly added tracks, total discovered MP3s and skipped unreadable entries when present.

Importing does not start playback or modify the captured queue. Folder traversal is performed in the background, while committing the collected paths to the observable library happens on the JavaFX Application Thread.

### Album artwork

The shared `playFile` action calls `ArtworkService.showArtwork` for every playback path. The service reads the selected song's first stored embedded cover through Jaudiotagger; importing tracks does not decode artwork for the entire library.

The now-playing `ImageView` fits the cover within a 128 by 128 frame while preserving its aspect ratio. The view binds to `imageProperty()` and the placeholder binds to `messageProperty()`. Selecting an uncached song clears the previous image and shows `Loading cover...`. Missing/linked artwork shows `No embedded cover`. Reading or decoding failures show `Cover unavailable` and log an error independently of playback.

Only one cover read runs at a time. One waiting-path slot keeps the latest uncached selection; rapid skipping replaces that slot instead of accumulating tasks. The active read finishes first. Its result can populate the cache, but updates the displayed image only if its path is still selected. Selecting a cached image also clears any obsolete waiting request.

The session cache holds at most eight entries, including remembered missing covers, using least recently used eviction. Each cached cover fits within 192 by 192 pixels. A `WritableImage` copies only the resized pixels, preventing the cache from retaining the decoded image's input stream and original encoded bytes.

Encoded artwork larger than 8 MiB is rejected after tag parsing. Temporary tag/parser and decoder allocations are outside the thumbnail cache limit; this is not a total process-memory cap.

Linked image URLs and external cover files are not loaded. Images that fail decoding or do not expose readable pixels use the unavailable placeholder. Failed reads are not cached and can be retried by selecting the song again.

Successful and missing-cover results are session-only and do not detect file edits. A cached result can remain until eviction or restart. Metadata and artwork currently perform separate tag reads.

### Threading

JavaFX controls, observable library collections, Track properties, playback queue state and service scheduling state are updated on the JavaFX Application Thread. The public library/artwork methods are called from that thread.

| Work | Owner | Background operation | Result applied on JavaFX thread |
| --- | --- | --- | --- |
| Metadata | `LibraryService` | One `Task<MetadataReadResult>` validates attributes and reuses cached strings or calls `MetadataService`. | Update that Track/cache/status, queue fresh/cleared metadata for saving and start the next check. |
| Folder scan | `LibraryService` | One `FolderScanTask` traverses folders and collects paths/counts. | Import a successful result or display cancellation/failure without adding partial results. |
| Artwork | `ArtworkService` | One `Task<Image>` reads, resizes and copies an embedded cover. | Update the bounded cache and display only a result for the selected path. |
| Database | `LibraryService` schedules `LibraryRepository` | One worker serializes migration, library/settings loading and transactional path/cache/preference writes. | Assign preferences before unlocking controls, register Tracks/cache candidates and update save state. |
| Preference delay | `MiSPlayApp` | None; a 250 ms `PauseTransition` runs on the JavaFX thread. | Capture the latest choices and enqueue an immutable save snapshot. |

Metadata, folder scanning, artwork and database work use separate single-thread executors. The preference delay runs on the JavaFX thread and schedules SQL on the database worker. Background tasks return data without manipulating live controls or the observable library.

Metadata priority changes the waiting deque without interrupting its current read. Artwork selection replaces its single waiting request without interrupting its current read. Result handlers check that the owning service remains open and their task is still active.

### Application shutdown

`MiSPlayApp.stop()` stops the preference delay, captures the latest choices when restoration has finished, sets its shutdown guard, disposes of now-playing bindings, closes `ArtworkService`, closes `LibraryService` and disposes of `AudioService`.

Artwork closing clears its current-task reference, waiting path and cache, requests cancellation of the active task and shuts down its executor. Library closing clears waiting metadata, clears task references, requests cancellation of active metadata/scanning tasks, unbinds folder status and shuts down those two executors.

Metadata, scanner and artwork workers are daemon threads. Cancellation is a request; closed/task-identity checks prevent late completions from updating the UI, including a database restore completing after close.

The database worker is deliberately non-daemon. Closing does not cancel accepted database tasks. When persistence is enabled, a final save includes active/waiting paths, completed metadata changes and the newest pending preferences. `shutdown()` lets the worker drain before JVM exit. Repeated path inserts and preference upserts are harmless. Failed-save changes are included; another failure is logged and is not claimed as saved. Unfinished/cancelled metadata reads can be rebuilt from saved paths next launch. Shutdown does not wait on the JavaFX thread. A lock can delay exit until its background timeout; forced termination bypasses normal draining.

## Verification status

The developer has reported working playback/library metadata, folder importing, embedded artwork and SQLite library-path checkpoints. Per-ID Windows results for the complete checklist below have not been recorded; the rows describe checks to run, not a blanket pass claim.

The current application was reported working after the metadata-cache handoff. The developer also reported the playback-preferences checkpoint working after installation. Detailed per-scenario Windows results have not been recorded.

Development-time headless artwork checks used generated MP3 fixtures and covered JPEG/PNG resizing, Unicode filenames, cache reuse and eviction, missing/damaged covers, invalid-file recovery, rapid selection changes, obsolete-result handling and shutdown. A separate layout check used substitutes for the existing services. These checks do not establish a full build or audio regression result for the Windows project.

SQLite development checks used the real 3.53.4.0 driver. Twelve repository checks covered database creation/restart, Unicode/apostrophes, normalization/duplicates/order, missing paths, transactional rollback, a 1,000-path batch, newer-schema rejection, corrupt bytes and an invalid storage directory. Twenty-one library-coordination checks covered restoration, save batching/retry, responsiveness under a database lock, folder import/cancellation, failed-restore session use, close during restore and normal-close flushing. A child JavaFX process exited with saves queued, and the parent verified its saved paths. Seven UI checks covered import bindings, storage/retry controls, restored rows, no automatic playback and layout.

The metadata-cache checkpoint added 30 focused checks: migration/rollback and retained IDs/order, Unicode/quotes, invalid cache data/version, atomic path/cache writes, restart cache hits verified through parser-call counts, time-only and size-only invalidation, submillisecond timestamps, playback/reimport refresh, missing/repaired files, bounded reads during concurrent edits, database locks, newest-result retry/close behavior, fallback caching and an 80-track batch. Those 30 and the existing 40 repository/library/UI checks passed together.

The preferences checkpoint passed a separate set of 70 checks: 35 model/repository checks, 16 settings/cache-coordination checks and 19 application checks. They covered schema 0/1/2 upgrades, retained caches, every repeat/shuffle combination, zero/full/decimal volume, malformed values, singleton-row enforcement, atomic rollback, startup gating, coalesced snapshots, first explicit playback volume, retry, immediate-close capture and failed-startup session use.

Those persistence checks ran with JDK 17 and JavaFX 21.0.1. Production repository, LibraryService and application code were exercised with API substitutes for unchanged model/audio/metadata/folder components where full source was unavailable. They are separate development harnesses, not a committed test suite or proof of the complete Java 25/JavaFX 25.0.2 Windows application. Local Windows playback verification remains necessary for later changes.

For the preferences checks, all four changed/new classes and the existing ArtworkService compiled. The audio spy recorded requests and volume without decoding MP3s; the queue fixture did not verify the production shuffle algorithm. Those behaviors still depend on local checks with the complete project.

No committed automated project test suite or memory/CPU profiling baseline has been recorded. The Yaezakura metadata display issue remains unresolved.

## Manual checks

Use MP3 files outside the Git repository. A small set of known working files is sufficient. Record actual results; these rows are a checklist, not a claim that every case has passed.

Unless a check specifies otherwise, set Repeat OFF and Shuffle OFF after restoration; those choices now persist. Use a disposable fresh profile when a check requires unsaved defaults. Some pending-state/cancellation checks need a sufficiently large batch; if work finishes too quickly, record the check as not exercised rather than passed.

Wait for restoration before using import controls. Run storage fault/schema checks only against a disposable database in a test copy or a harness using `LibraryService(Path)`; keep the real saved library intact.

| ID | Action | Expected result |
| --- | --- | --- |
| M01 | Launch with `javafx:run` | MiSPlay opens; Play and Pause start disabled |
| M02 | Open a known working MP3 | Audio starts at the restored/edited volume, using 50% only without a valid saved value; cached or freshly read metadata appears. |
| M03 | Pause during playback | Audio pauses without closing the application |
| M04 | Play after pausing | Playback resumes from the paused position |
| M05 | Open a second MP3 while the first plays | The first track stops and only the second track is heard |
| M06 | Cancel the file chooser during playback | The current track and UI selection remain intact |
| M07 | Let a short track finish, then click Play | The track plays again from the beginning |
| M08 | Select a deliberately invalid `.mp3` test file | An error is displayed and playback buttons are disabled |
| M09 | Open a valid MP3 after M08 | The application recovers and plays the file |
| M10 | Open a file whose path contains spaces or non-ASCII characters | The file opens normally if its MP3 encoding is supported |
| M11 | Close the application while audio plays | Audio stops and the Maven run finishes |
| M12 | Set volume to 20% before opening the first MP3 | The file starts using the selected volume and the slider remains at 20% |
| M13 | Adjust volume during playback, including 0% | Volume changes immediately; 0% silences the audio |
| M14 | Set volume to 20%, then open another MP3 | The replacement track uses the stored volume and the slider remains at 20% |
| M15 | Pause, change volume, then resume | Playback resumes using the new volume |
| M16 | Set volume, close normally and reopen MiSPlay. | The saved volume returns, including 0%; without a valid saved value, the fallback is 50%. |
| M17 | Launch before opening a file | Time displays as `0:00 / --:--` |
| M18 | Open an MP3 and let it play | Duration appears when available and elapsed time advances |
| M19 | Pause, then resume | Elapsed time holds while paused and continues after resuming |
| M20 | Open another MP3 during playback | Elapsed time resets and the new file's duration appears |
| M21 | Let the final track in a queue finish | Position returns to zero, duration remains displayed and audio stays silent |
| M22 | Launch, then open an MP3 | Seek slider starts disabled, then enables and follows playback |
| M23 | Drag the seek handle and hold it before releasing | Handle stays under user control; releasing seeks to the selected position |
| M24 | Click the slider track and use Left/Right keys | Playback seeks; arrow keys adjust by approximately five seconds |
| M25 | Pause, seek, then press Play | Position display updates while silent; Play resumes from the selected position |
| M26 | Seek after a track finishes, then press Play | Position display updates while silent; Play starts from the selected position |
| M27 | Open another MP3 | Slider position and range update for the replacement track |
| M28 | Add several MP3s without starting playback. | Rows appear with cached or fallback values, then update as metadata loads; audio and the active queue stay unchanged. |
| M29 | Double-click a populated row, then select another and press Enter | Each action starts the chosen file |
| M30 | Import an existing file through Add MP3s and Open MP3 | Only one row exists for that path; Open MP3 still starts playback |
| M31 | Double-click an empty row | Playback remains unchanged |
| M32 | Cancel either file dialog during playback | Collection and playback remain unchanged |
| M33 | Resize and scroll while playing | List uses available space, rows display correctly and audio continues |
| M34 | Import tracks, close normally and reopen MiSPlay. | After restoration, the same saved paths appear in insertion order without starting audio. |
| M35 | Search for part of a filename using different capitalization | Matching files appear regardless of query capitalization |
| M36 | Clear the query or enter only whitespace | The complete collection appears |
| M37 | Search a populated library using a query with no matches | The no-results message appears |
| M38 | Filter out the currently playing file | Audio and playback-time updates continue |
| M39 | Double-click a filtered result or select it and press Enter | The displayed file starts playing |
| M40 | Import files while searching. | Filename matches can appear immediately; title/artist/album matches update as background reads finish without editing the query. |
| M41 | Re-import a file hidden by the filter, then clear the search | Only one entry exists for that path |
| M42 | Launch before starting playback | Previous and Next are disabled |
| M43 | Start the middle entry of a list containing at least three songs | Both navigation buttons enable |
| M44 | Navigate to the first and last queue entries | Correct adjacent files play; buttons disable at their respective boundaries |
| M45 | Start from filtered results, then change the search | Navigation follows the captured queue |
| M46 | Import more files, then start a row again | Imports preserve the old queue; starting a row captures the updated visible list |
| M47 | Pause, change volume and press Next | The next song starts using the selected volume |
| M48 | Use Open MP3 | A single-song queue starts with both navigation buttons disabled |
| M49 | Let a song finish while another queued entry remains | The next queued song starts automatically and the playback display updates |
| M50 | Let successive queued songs finish | Each advances correctly, confirming the callback survives player replacement |
| M51 | Change the search or import files during playback | Automatic advancement follows the captured queue |
| M52 | Adjust volume before an automatic transition | The next song uses the selected volume |
| M53 | Open a single MP3 and let it finish | Playback returns to zero and remains silent |
| M54 | Pause before the endpoint | Playback stays paused until resumed or manually changed |
| M55 | After restoration, set Repeat OFF, then click it repeatedly. | Clicks cycle through ALL, ONE and OFF; a new profile defaults to OFF. |
| M56 | With Repeat OFF, let the final queued song finish. | Playback returns to zero and remains silent. |
| M57 | With Repeat ALL and multiple songs, finish the final song; also try Next at the end and Previous at the beginning. | Automatic completion and manual navigation wrap around the queue. |
| M58 | With Repeat ONE, finish a song, then press Next. | Completion replays the same song; Next starts the next song, which then repeats. |
| M59 | Open a single MP3 and test Repeat ALL and ONE. | The song repeats automatically; Previous and Next remain disabled. |
| M60 | Pause playback, then change repeat mode. | Playback remains paused and the controls reflect the new mode. |
| M61 | Search or import files while a captured queue is playing with Repeat ALL. | Playback continues and loops through the original captured queue. |
| M62 | Change repeat mode, start another queue, then restart the app. | New queues retain the mode and volume; saved choices return after restart while the active queue starts empty. |
| M63 | Launch with an empty queue and toggle shuffle after restoration. | The switch starts at its saved value, or OFF for a new profile; toggling causes no error. |
| M64 | Toggle shuffle during playback and while paused. | Playback position is preserved; paused playback remains paused. |
| M65 | Enable shuffle and activate a middle song in a multi-song list. Advance through the queue. | The selected song plays first; every captured song appears once before the queue ends. |
| M66 | Navigate backward and forward through a shuffled queue. | Previous and Next retrace the same order. |
| M67 | Start an ordinary queue, advance once, then enable shuffle. | Earlier entries and the current song retain their positions; only upcoming entries are shuffled. |
| M68 | Disable shuffle during playback. | The current song continues; navigation follows the original captured order. |
| M69 | With shuffle enabled, test Repeat ALL and ONE. | ALL loops through the same shuffled order. ONE repeats the current song; manual Next skips when available. |
| M70 | Search and import files during shuffled playback, then activate another row. | The active queue stays unchanged until row activation captures a fresh queue from the visible results. |
| M71 | Use Open MP3 with shuffle enabled; test Repeat ALL and ONE. | The queue contains one song, navigation buttons remain disabled, and automatic repeat works. |
| M72 | Start another queue with shuffle enabled, then restart the app. | The ON switch is retained; the earlier queue/shuffled order is not restored. |
| M73 | Play a tagged MP3. | The now-playing area displays its title, first stored artist value and album. |
| M74 | Change songs through Open MP3, row activation, navigation and automatic advancement. | Metadata follows the current song. |
| M75 | Skip several songs quickly. | An older result does not overwrite the final song's details. |
| M76 | Play an untagged or partially tagged MP3. | Missing fields use the documented fallbacks. |
| M77 | Play songs with Unicode tags. | Japanese, Korean and Chinese text displays correctly. |
| M78 | Use pause, seeking, volume, repeat and shuffle after metadata loads. | Playback controls continue working. |
| M79 | Close the application. | Audio stops and the application exits. |
| M80 | Import A and B, wait for their metadata, play both, then return to unchanged A. | A background attribute check can run; cached tags are reused without another parser call. |
| M81 | Open an already cached file again using Open MP3. | Its existing model and metadata are preserved without creating a duplicate library entry. |
| M82 | Let an unchanged cached song finish with Repeat ONE enabled. | Audio restarts; attribute validation can run without reparsing its tags. |
| M83 | Quickly switch from an uncached song to a cached song. | The cached song's details remain displayed; an older result cannot overwrite them. |
| M84 | Start another queue containing previously cached tracks. | Cached metadata remains available across queue changes. |
| M85 | Exercise navigation, library search, volume, seeking, repeat and shuffle. | Existing playback and library behavior continues working. |
| M86 | Restart with an unchanged successfully cached file already saved, without reimporting or playing it. | Its path is restored; background validation reuses saved metadata without tag parsing. |
| M87 | Import new unplayed tagged tracks after restoration finishes. | Pending rows can show filename — Unknown artist, then update automatically without playback; small files may load too quickly to see the fallback. |
| M88 | Play a tagged song whose metadata is cached or still pending. | The row and now-playing labels use its metadata; unchanged cached values require an attribute check but no tag parse. |
| M89 | Search a cached track by filename, title, artist and album. | Each field matches; case differences, surrounding whitespace and clearing the query work. |
| M90 | Keep an artist query active and use Open MP3 on a new song by that artist. | Its row appears once metadata becomes available without editing the query. |
| M91 | During a pending batch, search Unknown artist and activate a waiting track that has an embedded artist. | Its row leaves those results after metadata loads; playback continues. |
| M92 | Double-click and press Enter on filtered rows. | The correct files play. |
| M93 | Start a multi-song queue, then change search results or import files. | Navigation and automatic advancement retain the captured file order. |
| M94 | Scroll and resize repeatedly. | Reused cells show the correct content and empty cells contain no leftover text. |
| M95 | Exercise shuffle, repeat, volume and seeking. | Existing playback behavior continues working. |
| M96 | Import a tagged batch using Add MP3s without playing it. | Titles and artists fill in automatically; no audio starts. |
| M97 | Search a known artist or album while a larger batch is still loading. | Matching entries appear as tags finish loading without retyping the query. |
| M98 | Play a still-pending song near the end of an import batch. | Its read moves ahead of other waiting reads after the current read finishes. |
| M99 | Skip quickly while metadata is loading. | Now-playing labels follow the actual selected song; other completions update only their own Tracks. |
| M100 | Add more songs during metadata loading and reimport known files. | New files are scheduled; known paths keep their model identity and receive deduplicated checks without duplicate rows or parsing unchanged tags. |
| M101 | Import an empty bad-tags.mp3 with valid files using Add MP3s, without playing the invalid file. | A metadata error is logged for that file, fallback values remain and valid files continue loading. |
| M102 | Close while metadata is still loading, then reopen. | No late UI updates occur; accepted imported paths are saved by normal shutdown when storage is writable and are restored next launch. |
| M103 | Import nested folders with Unicode names, mixed-case .mp3 extensions and unrelated files. | Regular MP3 files are found in all levels; unrelated files are ignored. |
| M104 | Reimport the same folder, then one of its subfolders. | Known paths are not duplicated; the summary reports zero new tracks when all were already imported. |
| M105 | Import a folder containing no MP3s. | The summary reports zero discovered/added tracks without an error. |
| M106 | Scan a folder while music plays; use seeking, search and scrolling. | Playback and controls remain usable, and the captured queue keeps its membership. |
| M107 | Cancel the folder chooser during playback. | Library and playback remain unchanged. |
| M108 | Cancel a scan that is still running, then start another scan. | The cancelled scan adds no partial results; a subsequent scan can complete normally. |
| M109 | Scan a folder with an unreadable child entry where permissions allow this check. | Accessible files import; skipped entries are logged and included in the summary. |
| M110 | Attempt a scan whose selected root becomes inaccessible before traversal. | The scan reports failure and adds none of its collected paths. |
| M111 | Close the app while a folder scan is running, then reopen. | The incomplete scan adds no paths; prior accepted imports finish saving when storage is writable and reappear after restart. |
| M112 | Play songs with landscape PNG and portrait JPEG embedded covers. | Each appears in the now-playing frame with the correct aspect ratio. |
| M113 | Switch from a covered song to one without an embedded cover. | The previous cover clears and No embedded cover appears. |
| M114 | Skip rapidly between uncached songs, then select a cached song. | The final selected song keeps its correct cover; an older completion cannot replace it. |
| M115 | Revisit a recently cached song, including one with no artwork. | Its cached cover or missing-cover placeholder appears immediately without another artwork task. |
| M116 | Play a valid MP3 with damaged or unsupported embedded image data. | Cover unavailable appears and an artwork error is logged independently of audio playback. |
| M117 | Play more than eight distinct tracks and inspect the artwork cache with a debugger. | At most eight entries remain, including missing-cover entries; least recently used entries are evicted. |
| M118 | Change songs through Open MP3, double-click, Enter, Previous/Next and automatic advancement. | Every playback path requests the matching cover and metadata. |
| M119 | Import a folder while playing a song with artwork, then navigate and search. | Artwork, background metadata, folder controls and the captured playback queue continue working. |
| M120 | Close while an artwork read or waiting request exists. | No late cover update occurs; artwork work does not hold the process open. The database worker finishes accepted saves before exit. |
| M121 | Launch with no database in a disposable test profile and inspect startup/storage status. | The per-user directory/database are created; import controls wait for restoration, then enable with no audio started. |
| M122 | Add tracks A, B and C in that order, close normally and relaunch. | All three paths restore once in A/B/C order, without audio or a restored playback queue. |
| M123 | Complete a nested folder import, close normally and relaunch. | All successfully accepted paths return; their saved insertion order is retained. A cancelled scan adds none. |
| M124 | Reimport saved files and their folder, close and relaunch. | Each normalized path still has one row; existing paths retain their original insertion order. |
| M125 | Import supported MP3s with spaces, Unicode and apostrophes in their paths, then relaunch. | The exact paths restore and files can be selected without SQL quoting errors. |
| M126 | Import another batch while a save is pending, then immediately close normally and relaunch. | With writable storage, both active and waiting accepted paths are saved once before process exit and restored next launch. |
| M127 | Import during playback and use search, scrolling and navigation while saving. | Controls remain responsive; database saves leave the captured playback queue and audio unchanged. |
| M128 | Use a disposable track: save its path, close, move it away, launch, then close and restore the original file before another launch. | The missing path remains listed/saved; metadata or playback can fail while absent and fresh metadata is read when its path becomes available on a later launch. |
| M129 | In a disposable harness, make a path insert fail, queue another import, remove the failure and press Retry save. | Failure status/Retry appear, pending paths are retained, playback/imports continue and the successful retry saves both batches in order. |
| M130 | Launch a disposable harness with an unreadable/corrupt or newer-schema database, then import a file. | Loading fails visibly; temporary imports/playback remain available, saving stays disabled and the existing database is not reset. |
| M131 | Close a disposable harness while initial restoration is still pending. | No late model/UI changes or errors occur; the accepted database task finishes and the process exits. |
| M132 | Change volume/repeat/shuffle and play covered tracks, then close normally and relaunch. | Saved paths/order, validated text and playback preferences return; queue/song/position stay empty and artwork loads only for playback requests. |
| M133 | Upgrade a disposable schema-1 database containing known paths/IDs. | Schema becomes 3; paths/IDs/order remain, cache columns fill from successful reads and preferences use defaults until edited. |
| M134 | Fill caches, close normally, relaunch unchanged files and inspect parser calls with a debugger/harness. | Background attribute validation restores text metadata/search without calling the tag parser for those files. |
| M135 | Close, change tags on a disposable saved MP3, then relaunch. | Changed size or modification time triggers a fresh read and updates the row/search before playback is required. |
| M136 | In a disposable fixture harness, test same-size/time-only edits and size-only edits with unchanged time. | Either changed attribute invalidates cached text; available submillisecond timestamp differences are retained. |
| M137 | Edit a loaded file, then reimport it or request playback. | One Track/row remains; a deduplicated background check refreshes changed tags and search while the captured queue stays intact. |
| M138 | Move a disposable cached file away, request playback/reimport, restore it and request again. | The path remains; failure uses fallback text and queues a cache clear, then explicit retry reads repaired metadata in the same launch. |
| M139 | In a disposable fixture, make tag parsing fail after a previously cached file changes. | Track becomes FAILED, stale strings clear, a stored-cache clear is queued and other files continue; no automatic failure loop occurs. |
| M140 | In a controlled harness, edit a file during parsing once, then on both attempts. | One changed read is retried; repeated changes stop after the second attempt without caching an unstable result. |
| M141 | Block/fail an older metadata save while a newer result for the same path is pending, then Retry save. | Controls remain responsive; the newer value wins and successful saving clears the failure state. |
| M142 | With active/waiting metadata updates, close normally using a writable disposable database. | Accepted writes drain and the final flush stores the newest completed result; unfinished reads can be rebuilt next launch. |
| M143 | In a disposable database, use an unsupported metadata-cache version or incomplete cache fields, then relaunch. | Paths still restore; invalid cached text becomes a miss and successful fresh tags replace it. |
| M144 | Import an untagged but readable MP3, let its fallbacks save, then relaunch unchanged. | Filename/Unknown fallbacks are reused after validation without another tag parse. |
| M145 | Upgrade a disposable schema-2 library with valid cached tags. | Schema becomes 3; paths, IDs, order and caches remain, and the new preferences table initially uses unsaved defaults. |
| M146 | Set volume to 23%, Repeat ALL and Shuffle ON; close normally and reopen. | All choices return before controls become available; playback stays stopped. |
| M147 | Save 0% and then 100% volume across separate restarts. | Both boundary values survive; 0% is not replaced by 50%, and the first explicit song uses restored volume. |
| M148 | Save each repeat mode and both shuffle-switch values across launches. | Each choice returns; no particular old shuffled order is promised. |
| M149 | Change volume/repeat/shuffle repeatedly while playing or paused, then let saving settle. | Runtime changes apply immediately, the final snapshot saves after the short delay and saving does not restart the song. |
| M150 | Change all three controls, then close before the save delay finishes. | Normal close captures and restores the final choices on the next launch. |
| M151 | In a disposable harness, delay initial loading and inspect controls/default writes. | Preference controls stay disabled; applying saved choices does not write startup defaults. |
| M152 | Fail an older preference write, submit newer choices, remove the fault and press Retry save. | The newest pending choices win; controls remain usable during the failure. |
| M153 | Put malformed preference fields in a disposable database, preserving some valid choices. | Invalid fields use defaults, valid fields survive and merely loading them does not rewrite the row. |
| M154 | Start with an unreadable/future-schema disposable database, then change controls and close. | Temporary session choices work after loading fails; the original database/settings are not overwritten. |
| M155 | In a disposable harness, fail a preference save batched with path/tag changes. | The whole batch rolls back; no partial path/cache/settings changes commit. |
| M156 | Restore saved preferences, then select a library song for playback. | No prior queue/song/position is resumed automatically; the fresh queue uses the restored modes and volume. |

Maven **validate** checks project configuration; it does not compile Java or verify audio. A successful application launch exercises compilation, while these checks verify playback behavior.

For a check failure, record the check ID, exact action, error text, JDK/JavaFX versions and whether the same file works elsewhere. Do not commit personal audio files as evidence.

## Commit checkpoints

A checkpoint is ready when its intended behavior works, relevant manual checks pass and the staged files describe one understandable change.

At each checkpoint, the development handoff should state:

1. What is ready to commit.
2. Which checks were run and which still need to be run locally.
3. The exact proposed commit message.
4. Which documentation to update, or that no documentation update is needed.

Suggested commit message format:

```text
type: short description of the resulting behavior
```

| Type | Use |
| --- | --- |
| `feat` | Add user-visible functionality |
| `fix` | Correct broken behavior |
| `docs` | Change documentation only |
| `refactor` | Improve structure while preserving behavior |
| `chore` | Repository or build maintenance |
| `test` | Add meaningful verification |


The playback-preferences checkpoint can use `feat: persist playback preferences` when its three changed Java files, new `PlaybackPreferences` record and documentation are committed together. If the feature code is already committed and only documentation remains, use `docs: document saved playback preferences`.

For later checkpoints, update `CHANGELOG.md` when functionality changes. Update the README for changed features, prerequisites or run instructions. Update this guide when responsibilities, workflows or verification change. Do not invent documentation changes just to fill a checklist.

## Upcoming implementation notes

- Add manual playlists with saved names, membership and track order next; add favorites in a later checkpoint.
- Keep database work and expensive file/image operations off the JavaFX Application Thread, applying observable-model changes on that thread.
- Preserve stable Track identity and captured playback-queue behavior while adding persistence.
- Extend refresh/invalidation to artwork and path repair; text metadata already validates size/time on startup, playback requests and reimport.
- Keep tag reading and artwork decoding out of virtualized cell updates.
- Record a Windows process-memory/CPU baseline before making optimization claims.

## Profiling

No memory/CPU baseline has been recorded for MiSPlay. Capture separate measurements for an empty window, playback, populated-library browsing, active folder/metadata work and artwork loading. Record the workload, observation interval, JDK build and tool used. Windows process memory includes more than the Java heap; do not compare unlike metrics.

## Reference documentation

- [JavaFX MediaPlayer](https://openjfx.io/javadoc/25/javafx.media/javafx/scene/media/MediaPlayer.html)
- [JavaFX Application lifecycle](https://openjfx.io/javadoc/25/javafx.graphics/javafx/application/Application.html)
- [JavaFX with Maven](https://openjfx.io/openjfx-docs/maven)
- [JavaFX PauseTransition](https://openjfx.io/javadoc/25/javafx.graphics/javafx/animation/PauseTransition.html)
- [SQLite UPSERT](https://www.sqlite.org/lang_upsert.html)
