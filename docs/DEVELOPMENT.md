# Development guide

## Scope and approach

MiSPlay is a personal Windows 11 desktop project developed in small, working increments. Each increment should have a clear behavior to verify. Keep the architecture understandable and add dependencies when a feature needs them.

The current checkpoint includes MP3 playback, captured queues with repeat/shuffle, saved library paths/order and text metadata, background cache validation, folder importing and embedded album artwork. SQLite schema 2 caches title, artist and album with file size/modification time. Saved playback preferences are the next proposed checkpoint. Lyrics, tray/overlay behavior, updates and Windows packaging remain future work.

## Current architecture

| Class | Owns | Does not own |
| --- | --- | --- |
| `MiSPlayApp` | Controls, bindings, file/folder choosers, playback actions, service instances and shutdown coordination | Audio decoding, tag parsing or database storage |
| `AudioService` | The current `MediaPlayer`, playback/seek/volume requests, callbacks, audio errors and disposal | Library imports, UI controls or metadata/artwork caching |
| `LibraryService` | Observable tracks, normalized-path lookup, metadata validation/cache, metadata/folder workers, restore/save scheduling and storage status | Audio playback, artwork caching or SQL execution |
| `LibraryRepository` | Database location/schema/migration, ordered paths/cached tags and transactional path/cache writes | JavaFX models/controls, worker scheduling, tag parsing or audio bytes |
| `Track` | A file, observable metadata/status and normalized search matching | File reading, worker scheduling or controls |
| `TrackMetadata` | Immutable title, artist and album strings | Parser objects, artwork or loading state |
| `MetadataStatus` | PENDING, LOADING, LOADED and FAILED states | Worker execution or persistence |
| `MetadataService` | Reading tag strings and applying missing-field fallbacks | Controls, scheduling or artwork caching |
| `FolderScanTask` | Recursive folder traversal, discovered paths, counts and cancellation checks | Adding tracks to the library or changing playback |
| `FolderScanResult` | An unmodifiable copy of discovered files and a skipped-entry count | Scan execution or library changes |
| `ArtworkService` | Selected-song artwork properties, its worker, latest waiting request and bounded thumbnail cache | Audio playback, library metadata reads or remote-image fetching |
| `PlaybackQueue` | Captured original/active orders, position, navigation, repeat and shuffle rules | Controls, audio resources or file reading |
| `RepeatMode` | OFF, ALL and ONE choices | Queue navigation or playback execution |

`LibraryRepository` also declares two nested transfer records: `CachedMetadata` contains a `TrackMetadata`, file size and modification-time string; `SavedTrack` contains a file and optional cache. A null cache represents a cache miss when loading or a request to clear cached fields when saving.

### Startup and saved library

`MiSPlayApp` shows the window, then calls `LibraryService.restoreLibrary()` once. Loading runs on a dedicated database worker. Open MP3, Add MP3s and Add Folder stay disabled until restoration finishes; the list placeholder and storage row show loading status.

`LibraryRepository.defaultDatabasePath()` uses `%LOCALAPPDATA%\MiSPlay\library.db`, falling back to `.misplay/library.db` under Java's `user.home` when `LOCALAPPDATA` is missing or blank. Its constructor normalizes the path without touching the disk. Opening a connection creates the parent directory and database when needed. No separate SQLite server or `module-info.java` is required; Maven supplies `sqlite-jdbc` 3.53.4.0 and runtime `slf4j-nop` 1.7.36. The launcher's native-access option includes `ALL-UNNAMED` for the JDBC dependency.

Schema version 2 is recorded with `PRAGMA user_version`. Startup upgrades schema 1 transactionally by adding six nullable columns without replacing saved paths or IDs. A new database is initialized through the same path. The resulting table is:

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

Metadata-cache format version 1 is separate from database schema version 2. Bump the cache-format constant when tag interpretation or fallback rules change, so older results become misses. Missing fields, negative sizes and unsupported cache-format versions are treated as uncached metadata rather than rejecting the saved paths.

Loading reads paths/caches with `ORDER BY id`, validates that each path is absolute and returns normalized files. The repository does not check file existence or start playback. On the JavaFX thread, `LibraryService` registers restored files as new Track models and schedules background cache checks without inserting those paths again. The original insertion order becomes the library order; filesystem traversal order is not guaranteed for a newly imported folder.

An initial restore/migration failure disables saving for that launch, displays a status message and still enables temporary imports/playback. An unreadable/corrupt database or a schema version above 2 is not silently reset or replaced. The user must fix the storage problem and restart to retry restoration. Missing paths remain saved even if metadata or audio loading fails. Keep a database backup before upgrading; schema-1 application code cannot open schema 2.

### Saving imported paths

After restoration, `addFiles` registers new normalized paths on the JavaFX thread, schedules metadata checks and queues only newly added paths for insertion. Known paths retain their Track identity and receive a deduplicated recheck without duplicate inserts. Successful folder imports use the same path.

One database task runs at a time. An insertion-ordered pending set coalesces new paths while a batch is active. A pending metadata map retains the latest result or clear request for each path. Tag results can coalesce until 32 pending changes or the end of the metadata queue; imports, save completion and normal close can also flush smaller batches.

The repository opens/closes a connection per operation and uses a three-second SQLite busy timeout. `saveChanges` inserts new paths with prepared parameters and `ON CONFLICT(path) DO NOTHING`, then updates cached columns by path in the same transaction. Cache updates never reorder existing paths. SQL failures or an update for an unknown path roll back the whole batch. Library membership remains append-only; there is no removal or full-library snapshot replacement.

The storage row shows restoration/saving activity. A failed write restores its paths and metadata changes to pending state, sets the save-failed property and pauses automatic writes. New imports/results can still join the pending work. When an older failed cache write and a newer waiting result share a path, the newer value wins. Retry save clears the failure state and attempts pending changes again. Import, search and playback continue while saves run.

Paths, insertion order and valid title/artist/album caches persist. Loading status, artwork, queue position/order and playback settings are rebuilt or reset each launch. Normal close attempts to finish accepted writes; forced termination or an unresolved database failure can leave paths/cache updates unsaved.

### Opening a track

1. Open MP3 shows a file chooser. Cancelling leaves the library and playback unchanged.
2. After restoration has finished, the selected file is imported through `LibraryService.addFiles`, preserving an existing Track for a known path. A new path queues metadata and a database save when persistence is available. `PlaybackQueue.start` creates a single-song queue.
3. The shared `playFile` action binds the now-playing metadata labels, calls `ArtworkService.showArtwork`, clears the previous audio error and updates playback controls and the visible list selection.
4. `AudioService.loadAndPlay` disposes of the previous player, creates `Media` from the file URI and creates a replacement `MediaPlayer` with its callbacks attached. The player receives the stored volume, which defaults to 0.5 (50%) at launch.
5. Playback is requested. JavaFX completes media preparation asynchronously; metadata and artwork can finish independently.

Row activation, manual navigation and automatic advancement also use `playFile`. When the current song is visible in the filtered list, it is selected and scrolled into view.

Play and Pause delegate to service methods rather than maintaining a separate UI playback flag. Natural completion delegates to the queue rules described below.

### Error reporting

The UI passes a `Consumer<String>` to `AudioService`. The service calls this callback with an error message; the UI displays it and disables the playback buttons. Selecting another file enables another playback attempt.

Synchronous construction failures are caught by `loadAndPlay`. Later media/player errors are handled by callbacks. `handleError` checks which player reported the error so an obsolete player cannot clear its replacement.

### Resource ownership

- `AudioService` holds at most one active player and disposes of the previous player before replacing it.
- Disposal clears the service's player reference before releasing audio resources.
- `LibraryService` owns the current launch's Track objects, path lookup, metadata/scanning workers, database worker and pending save batches.
- `LibraryRepository` owns blocking SQL operations; each operation closes its statements and connection and runs on the database worker.
- `ArtworkService` owns the artwork worker and thumbnail cache; Track metadata remains strings only.
- `Application.stop()` disposes of now-playing bindings, closes artwork/library services and disposes of the audio player.
- Use file URIs for `Media`; do not construct media locations by manually concatenating Windows path separators.

### Volume control

`AudioService` stores the current volume as a number from 0.0 to 1.0. `setVolume()` keeps the value within that range and updates the active player when one exists.

`MiSPlayApp` displays a slider from 0 to 100. Its value-change listener divides the percentage by 100 before passing it to `AudioService.setVolume()`.

Every replacement player receives the stored volume. Volume can also be adjusted before opening a track.

The setting lasts for the current application session. Each application launch starts at 50%.

### Playback queue

`PlaybackQueue` is a plain Java class containing the captured original order, active playback order, current position, repeat mode and shuffle flag. Its entries reference files rather than loaded audio.

Activating a row passes its Track to `startQueue`. That action converts the current filtered entries into a `List<File>` and passes it, together with the selected file, to `PlaybackQueue.start`.

The queue captures its file order using `List.copyOf`. Later metadata changes, searches and imports leave it intact. Activating another row creates a new queue. Open MP3 creates a single-song queue.

The shared `playFile` action updates metadata/artwork, audio playback, visible selection and navigation buttons. Previous/Next use the active queue without rebuilding it and start the selected song even if playback was paused. Volume carries over.

Repeat defaults to OFF:

| Mode | Natural completion | Manual Previous and Next |
| --- | --- | --- |
| OFF | Advance to the next track; remain silent at zero after the final track. | Stop at the queue boundaries. |
| ALL | Advance and wrap from the final track to the first. | Wrap around a queue containing multiple tracks. |
| ONE | Replay the current track. | Move normally, stopping at the queue boundaries. |

Previous and Next are disabled for empty and single-song queues in every mode. A single song can still repeat automatically in ALL or ONE.

Changing repeat mode updates the controls without starting, pausing or restarting playback. Starting another queue preserves the repeat choice, which resets to OFF after restarting the application.

### Shuffle playback

Shuffle defaults to OFF and is controlled through a JavaFX `ToggleButton`.

`originalTracks` stores the file order captured when the queue starts. `tracks` stores the active playback order. Both lists contain file references rather than loaded audio.

Enabling shuffle during an existing queue preserves entries up to and including the current position. Only entries after that position are shuffled.

Starting a new queue while shuffle is enabled places the selected song first and shuffles all remaining entries.

Shuffling uses a temporary `ArrayList` and `Collections.shuffle`. The resulting order is stored using `List.copyOf`.

Previous and Next move through the same active order. Repeat All loops through that order again. Repeat One replays the current song, while manual navigation continues to use the active order.

Disabling shuffle restores `originalTracks` and finds the current song's position within it. Subsequent navigation follows that original captured order.

Toggling shuffle updates queue state and controls without reloading audio, preserving playback position and paused state.

Searches and imports do not change the active queue. Starting another queue preserves the shuffle setting, which resets to OFF when the application restarts.

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
| Database | `LibraryService` schedules `LibraryRepository` | One worker serializes migration, path/cache loading and transactional inserts/updates. | Register restored Tracks/cache candidates, update storage status and schedule the next save. |

These operations use separate single-thread executors. Background tasks return data without manipulating live controls or the observable library.

Metadata priority changes the waiting deque without interrupting its current read. Artwork selection replaces its single waiting request without interrupting its current read. Result handlers check that the owning service remains open and their task is still active.

### Application shutdown

`MiSPlayApp.stop()` disposes of the now-playing bindings, closes `ArtworkService`, closes `LibraryService` and disposes of `AudioService`.

Artwork closing clears its current-task reference, waiting path and cache, requests cancellation of the active task and shuts down its executor. Library closing clears waiting metadata, clears task references, requests cancellation of active metadata/scanning tasks, unbinds folder status and shuts down those two executors.

Metadata, scanner and artwork workers are daemon threads. Cancellation is a request; closed/task-identity checks prevent late completions from updating the UI, including a database restore completing after close.

The database worker is deliberately non-daemon. Closing does not cancel accepted database tasks. When persistence is enabled, it queues a final save containing active/waiting paths and completed metadata changes, keeping the newest pending cache value per path, then calls `shutdown()` so the worker drains before JVM exit. Repeated path inserts are harmless because paths are unique. This final attempt also includes changes from a failed save; a further failure is logged and is not claimed as saved. Unfinished/cancelled metadata reads are not promised to be cached; saved paths can be read on the next launch. Shutdown does not wait on the JavaFX thread. A locked database can delay exit until its background timeout; a forced stop bypasses normal draining.

## Verification status

The developer has reported working playback/library metadata, folder importing, embedded artwork and SQLite library-path checkpoints. Per-ID Windows results for the complete checklist below have not been recorded; the rows describe checks to run, not a blanket pass claim.

The current application was reported working after the metadata-cache handoff. Individual migration/cache scenarios have not been recorded as Windows passes.

Development-time headless artwork checks used generated MP3 fixtures and covered JPEG/PNG resizing, Unicode filenames, cache reuse and eviction, missing/damaged covers, invalid-file recovery, rapid selection changes, obsolete-result handling and shutdown. A separate layout check used substitutes for the existing services. These checks do not establish a full build or audio regression result for the Windows project.

SQLite development checks used the real 3.53.4.0 driver. Twelve repository checks covered database creation/restart, Unicode/apostrophes, normalization/duplicates/order, missing paths, transactional rollback, a 1,000-path batch, newer-schema rejection, corrupt bytes and an invalid storage directory. Twenty-one library-coordination checks covered restoration, save batching/retry, responsiveness under a database lock, folder import/cancellation, failed-restore session use, close during restore and normal-close flushing. A child JavaFX process exited with saves queued, and the parent verified its saved paths. Seven UI checks covered import bindings, storage/retry controls, restored rows, no automatic playback and layout.

The metadata-cache checkpoint added 30 focused checks: migration/rollback and retained IDs/order, Unicode/quotes, invalid cache data/version, atomic path/cache writes, restart cache hits verified through parser-call counts, time-only and size-only invalidation, submillisecond timestamps, playback/reimport refresh, missing/repaired files, bounded reads during concurrent edits, database locks, newest-result retry/close behavior, fallback caching and an 80-track batch. Those 30 and the existing 40 repository/library/UI checks passed together.

Those persistence checks ran with JDK 17 and JavaFX 21.0.1. Production repository, LibraryService and application code were exercised with API substitutes for unchanged model/audio/metadata/folder components where full source was unavailable. They are separate development harnesses, not a committed test suite or proof of the complete Java 25/JavaFX 25.0.2 Windows application. Local Windows playback verification remains necessary for later changes.

No committed automated project test suite or memory/CPU profiling baseline has been recorded. The Yaezakura metadata display issue remains unresolved.

## Manual checks

Use MP3 files outside the Git repository. A small set of known working files is sufficient. Record actual results; these rows are a checklist, not a claim that every case has passed.

Unless a check specifies otherwise, start it with Repeat OFF and Shuffle OFF. Some pending-state/cancellation checks need a sufficiently large batch; if work finishes before the action can be exercised, record the check as not exercised rather than passed.

Wait for restoration before using import controls. Run storage fault/schema checks only against a disposable database in a test copy or a harness using `LibraryService(Path)`; keep the real saved library intact.

| ID | Action | Expected result |
| --- | --- | --- |
| M01 | Launch with `javafx:run` | MiSPlay opens; Play and Pause start disabled |
| M02 | Open a known working MP3 | Audio starts at the current volume, initially 50%; cached metadata appears immediately or fallback text updates after its read. |
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
| M16 | Close and reopen MiSPlay | Volume starts at the default 50% |
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
| M55 | Launch the app and click Repeat repeatedly. | The initial mode is OFF; clicks cycle through ALL, ONE and OFF. |
| M56 | With Repeat OFF, let the final queued song finish. | Playback returns to zero and remains silent. |
| M57 | With Repeat ALL and multiple songs, finish the final song; also try Next at the end and Previous at the beginning. | Automatic completion and manual navigation wrap around the queue. |
| M58 | With Repeat ONE, finish a song, then press Next. | Completion replays the same song; Next starts the next song, which then repeats. |
| M59 | Open a single MP3 and test Repeat ALL and ONE. | The song repeats automatically; Previous and Next remain disabled. |
| M60 | Pause playback, then change repeat mode. | Playback remains paused and the controls reflect the new mode. |
| M61 | Search or import files while a captured queue is playing with Repeat ALL. | Playback continues and loops through the original captured queue. |
| M62 | Change repeat mode, start another queue, then restart the app. | New queues retain the mode and volume during the session; restarting resets repeat to OFF. |
| M63 | Launch the app and toggle shuffle with an empty queue. | Shuffle starts OFF; toggling causes no error. |
| M64 | Toggle shuffle during playback and while paused. | Playback position is preserved; paused playback remains paused. |
| M65 | Enable shuffle and activate a middle song in a multi-song list. Advance through the queue. | The selected song plays first; every captured song appears once before the queue ends. |
| M66 | Navigate backward and forward through a shuffled queue. | Previous and Next retrace the same order. |
| M67 | Start an ordinary queue, advance once, then enable shuffle. | Earlier entries and the current song retain their positions; only upcoming entries are shuffled. |
| M68 | Disable shuffle during playback. | The current song continues; navigation follows the original captured order. |
| M69 | With shuffle enabled, test Repeat ALL and ONE. | ALL loops through the same shuffled order. ONE repeats the current song; manual Next skips when available. |
| M70 | Search and import files during shuffled playback, then activate another row. | The active queue stays unchanged until row activation captures a fresh queue from the visible results. |
| M71 | Use Open MP3 with shuffle enabled; test Repeat ALL and ONE. | The queue contains one song, navigation buttons remain disabled, and automatic repeat works. |
| M72 | Start another queue with shuffle enabled, then restart the app. | Shuffle remains enabled between queues during the session and resets to OFF after restart. |
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
| M132 | Change volume/repeat/shuffle and play covered tracks, then close normally and relaunch. | Saved paths/order and valid text metadata return after validation; volume resets to 50%, repeat/shuffle to OFF and artwork loads only for playback requests. |
| M133 | Upgrade a disposable schema-1 database containing known paths/IDs. | Schema becomes 2; paths, IDs and order remain, cache columns start empty and successful tag results are saved. |
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


The metadata-cache checkpoint can use `feat: cache track metadata with file change detection` when its two changed Java files and documentation are committed together. If the feature code is already committed and only documentation remains, use `docs: document persistent metadata caching`.

For later checkpoints, update `CHANGELOG.md` when functionality changes. Update the README for changed features, prerequisites or run instructions. Update this guide when responsibilities, workflows or verification change. Do not invent documentation changes just to fill a checklist.

## Upcoming implementation notes

- Add saved playback preferences next, followed by playlist relationships in later checkpoints.
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
