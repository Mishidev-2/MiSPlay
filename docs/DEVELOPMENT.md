# Development guide

## Scope and approach

MiSPlay is a personal Windows 11 desktop project developed in small, working increments. Each increment should have a clear behavior to verify. Keep the architecture understandable and add dependencies when a feature needs them.

The current checkpoint includes MP3 playback, captured queues with repeat/shuffle, saved Favorites and manual playlists, library paths/order and text metadata, saved playback preferences, background cache validation, folder importing and embedded album artwork. SQLite schema 5 adds favorite membership while retaining cached tags, playlists and volume/repeat/shuffle choices. Sidecar and embedded lyrics are implemented, including synchronized line highlighting, click/Enter seeking for usable timestamps and scrollable untimed embedded text. The developer reports sidecar files working. The current embedded update passed harness checks; its complete target build, real-audio Windows synchronization and the developer’s specific Musicolet files remain pending locally. Lyric export, tray/overlay behavior, updates and Windows packaging remain future work. Lyrics are read-only and do not change schema 5.

## Current architecture

| Class | Owns | Does not own |
| --- | --- | --- |
| `MiSPlayApp` | Window/tabs, library controls/views, dialogs, bindings, choosers, playback/lyric integration, delayed preference saves, startup guards and shutdown coordination | Audio decoding, LRC/tag parsing or SQL execution |
| `AudioService` | The current `MediaPlayer`, playback/seek/volume requests, callbacks, audio errors and disposal | Library imports, UI controls or metadata/artwork caching |
| `LibraryService` | Observable tracks/playlists, immutable favorite state, editing rules, metadata validation/cache, workers, active/pending preference/playlist/favorite snapshots, restore/save scheduling and storage status | Audio playback, artwork caching or SQL execution |
| `LibraryRepository` | Database schema/migration, complete startup snapshots and transactional path/cache/preference/playlist/favorite writes | JavaFX controls, worker scheduling, tag parsing or audio bytes |
| `Track` | A file, observable metadata/status and normalized search matching | File reading, worker scheduling or controls |
| `TrackMetadata` | Immutable title, artist and album strings | Parser objects, artwork or loading state |
| `MetadataStatus` | PENDING, LOADING, LOADED and FAILED states | Worker execution or persistence |
| `MetadataService` | Reading tag strings and applying missing-field fallbacks | Controls, scheduling or artwork caching |
| `FolderScanTask` | Recursive folder traversal, discovered paths, counts and cancellation checks | Adding tracks to the library or changing playback |
| `FolderScanResult` | An unmodifiable copy of discovered files and a skipped-entry count | Scan execution or library changes |
| `ArtworkService` | Selected-song artwork properties, its worker, latest waiting request and bounded thumbnail cache | Audio playback, library metadata reads or remote-image fetching |
| `LyricLine` | Immutable adjusted timestamp and text for one line or simultaneous group | File reading, controls or audio playback |
| `LrcParser` | Pure line-timed text parsing, limits, global offsets, sorting and simultaneous grouping | File reading, JavaFX state or audio playback |
| `LyricsService` | Immutable timed/plain line views, source priority/fallback, worker, latest waiting request, status and binary clock lookup | Controls, audio execution, tag parsing, online fetching or SQLite |
| `EmbeddedLyricsReader` | Bounded blocking ID3 tag reads, embedded text/timing selection and immutable extraction results | Controls, worker scheduling, audio decode, file writes or SQLite |
| `LyricsPane` | Separate virtualized wrapped timed/plain lists, source/status hints, clock highlighting/follow delay, reload and guarded timed seeks | File parsing, audio decoding or database state |
| `PlaybackQueue` | Captured original/active orders, position, navigation, repeat and shuffle rules | Controls, audio resources or file reading |
| `PlaybackPreferences` | Immutable volume, repeat mode and shuffle choices, validation and defaults | Queue membership/order, playback position, controls or SQL |
| `Playlist` | Immutable ID/name and normalized unique file paths in manual order | Track objects, observable editing state, playback execution or SQL |
| `RepeatMode` | OFF, ALL and ONE choices | Queue navigation or playback execution |

`LibraryRepository` also declares three nested transfer records: `CachedMetadata` contains tag strings and file attributes, `SavedTrack` contains a file and optional cache, and `LibraryState` contains copied track entries, preferences, copied playlists and an immutable favorite-file set loaded together. A null cache represents a cache miss when loading or a request to clear cached fields when saving. Favorites introduced no new model class. LRC adds `LyricLine`, `LrcParser`, `LyricsService` and `LyricsPane`, bringing that checkpoint to 19 top-level classes. Embedded reading adds `EmbeddedLyricsReader`, bringing the current table to **20 top-level classes**. `LyricsService` has a private nested `LoadResult` record for timed lines, plain lines and status. `EmbeddedLyricsReader.Result` holds copied timed/plain lists and a diagnostic note; its lists cannot both be nonempty.

### Startup and saved library

`MiSPlayApp` shows the window, then calls `LibraryService.restoreLibrary()` once. Loading runs on a dedicated database worker. Import, favorite, playlist/view and preference controls are gated until restoration finishes; the list placeholder and storage row show loading status.

`LibraryRepository.defaultDatabasePath()` uses `%LOCALAPPDATA%\MiSPlay\library.db`, falling back to `.misplay/library.db` under Java's `user.home` when `LOCALAPPDATA` is missing or blank. Its constructor normalizes the path without touching the disk. Opening a connection creates the parent directory and database when needed. No separate SQLite server or `module-info.java` is required; Maven supplies `sqlite-jdbc` 3.53.4.0 and runtime `slf4j-nop` 1.7.36. The launcher's native-access option includes `ALL-UNNAMED` for the JDBC dependency.

Schema version 5 is recorded with `PRAGMA user_version`. Startup upgrades schemas 0–4 transactionally: schemas 0/1 receive cached-tag columns, earlier schemas gain preferences and playlist/membership tables when needed, and all gain a favorite-membership table. Saved paths, IDs, library order, valid caches, manual playlists and existing playback settings are not replaced. The track table remains:

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

Metadata-cache format version 1 is separate from database schema version 5. Bump the cache-format constant when tag interpretation or fallback rules change, so older results become misses. Missing fields, negative sizes and unsupported cache-format versions are treated as uncached metadata rather than rejecting the saved paths.

`loadState()` reads paths/caches with `ORDER BY id`, the preferences row, playlists/memberships by saved positions and favorite membership in one read transaction. It validates absolute paths and returns normalized files without checking existence or starting playback. On the JavaFX thread, `LibraryService` assigns preferences, registers Tracks, restores playlist/favorite snapshots and schedules background cache checks without reinserting paths. All restored state is assigned before `restoringLibrary` becomes false. The app applies preferences to audio/queue/controls under a guard suppressing save requests. The original insertion order becomes the library order; filesystem traversal order is not guaranteed for a newly imported folder. The initial tab is Library, its view is All songs and its playback queue is empty; restoring stars schedules no favorite write.

An initial restore/migration failure disables saving for that launch, displays a status message and enables temporary imports/playback/favorites/playlists/settings after loading finishes. An unreadable/corrupt database or schema version above 5 is not silently reset or replaced. Invalid playlist/favorite references, including orphan membership, also fail restoration instead of discarding data. Fix the storage problem and restart to retry restoration. Missing media paths retain library/playlist/favorite membership even if metadata or audio loading fails. Keep a database backup before upgrading; earlier application code cannot open schema 5.

### Saving imported paths

After restoration, `addFiles` registers new normalized paths on the JavaFX thread, schedules metadata checks and queues only newly added paths for insertion. Known paths retain their Track identity and receive a deduplicated recheck without duplicate inserts. Successful folder imports use the same path.

One database task runs at a time. An insertion-ordered pending set coalesces new paths while a batch is active. A pending metadata map retains the latest result or clear request for each path. Tag results can coalesce until 32 pending changes or the end of the metadata queue; imports, save completion and normal close can also flush smaller batches.

The repository opens/closes a connection per operation, uses a three-second SQLite busy timeout and enables foreign keys. `saveChanges` inserts paths with prepared parameters and `ON CONFLICT(path) DO NOTHING`, updates cached columns, optionally upserts preferences and optionally replaces playlist/favorite snapshots in one transaction. A null optional component leaves it unchanged; an empty playlist list or favorite set clears that component. Paths are inserted before membership references are saved. Cache/settings/playlist/favorite updates never reorder library paths. SQL failures, cache updates for unknown paths or membership references to unknown paths roll back the whole batch. Library membership remains append-only; there is no removal or full-library snapshot replacement.

The storage row shows restoration/saving activity. A failed write restores paths, metadata changes and settings/playlist/favorite snapshots to pending state, sets the save-failed property and pauses automatic writes. New imports/results/settings/playlist/favorite edits can still join pending work. Newer waiting metadata and snapshots win over older failed snapshots, including an empty playlist list or favorite set. Retry save clears the failure state and attempts pending changes again. Import, search, favorite/playlist editing and playback continue while saves run.

Paths, library/playlist order, favorite membership, playlist IDs/names/membership, valid title/artist/album caches and playback preferences persist. Loading status, artwork, selected view and active queue/song/position/order are rebuilt or reset each launch. Normal close attempts to finish accepted writes; forced termination or an unresolved storage error can leave paths/cache/settings/playlist/favorite updates unsaved.

### Opening a track

1. Open MP3 shows a file chooser. Cancelling leaves the library and playback unchanged.
2. After restoration has finished, the selected file is imported through `LibraryService.addFiles`, preserving an existing Track for a known path. A new path queues metadata and a database save when persistence is available. `PlaybackQueue.start` creates a single-song queue.
3. The shared `playFile` action binds now-playing metadata, calls `ArtworkService.showArtwork`, resets the lyric clock/highlight through `LyricsPane.beginTrack` and calls `LyricsService.showLyrics` to select a sidecar or embedded fallback on its worker. It clears the previous audio error and updates playback controls and the visible list selection. Previous timed/plain lyrics disappear immediately while the new read runs.
4. `AudioService.loadAndPlay` disposes of the previous player, creates `Media` from the file URI and creates a replacement `MediaPlayer` with its callbacks attached. The player receives the current restored or edited volume; 0.5 (50%) is the fallback when no valid saved volume exists.
5. Playback is requested. JavaFX completes media preparation asynchronously; metadata, artwork and lyrics can finish independently.

Row activation, manual navigation and automatic advancement also use `playFile`. When the current song is visible in the filtered list, it is selected and scrolled into view.

Play and Pause delegate to service methods rather than maintaining a separate UI playback flag. Natural completion delegates to the queue rules described below.

### Error reporting

The UI passes a `Consumer<String>` to `AudioService`. The service calls this callback with an error message; the UI displays it, disables the playback buttons and calls `LyricsPane.playbackFailed` to clear the active lyric highlight and prevent seeks against an obsolete duration. Loaded text can still be read/reloaded. Selecting another file enables another playback attempt.

Synchronous construction failures are caught by `loadAndPlay`. Later media/player errors are handled by callbacks. `handleError` checks which player reported the error so an obsolete player cannot clear its replacement.

### Resource ownership

- `AudioService` holds at most one active player and disposes of the previous player before replacing it.
- Disposal clears the service's player reference before releasing audio resources.
- `LibraryService` owns the current launch's Track objects, path lookup, immutable playlist/favorite snapshots, metadata/scanning/database workers and active/pending save snapshots, including preferences, playlists and favorites.
- `LibraryRepository` owns blocking SQL operations; each operation closes its statements and connection and runs on the database worker.
- `ArtworkService` owns the artwork worker and thumbnail cache; Track metadata remains strings only.
- `LyricsService` owns its daemon file worker and loaded line view; `LyricsPane` owns its clock/list listeners and one-shot follow delay.
- `MiSPlayApp` owns the preference save delay and restoration/shutdown guards. Its stop method captures the latest settings before closing the library service.
- `Application.stop()` also disposes of now-playing bindings, closes artwork/lyrics/library resources and disposes of the audio player.
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

Activating a row passes its Track to `startQueue`. That action converts the current visible filtered/sorted entries into a `List<File>` and passes it, together with the selected file, to `PlaybackQueue.start`. All songs and Favorites use library insertion order; a manual playlist uses its independent order before shuffle rules apply.

The queue captures its file order using `List.copyOf`. Later metadata changes, searches, imports, view changes, favorite edits and playlist edits/deletion leave it intact. A song removed from Favorites or a playlist can still play from that previously captured queue. Activating another row creates a new queue. Open MP3 creates a single-song queue.

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

Searches, imports, view changes, favorite edits and playlist edits do not change the active queue. Starting another queue preserves the shuffle switch; later launches restore that switch without restoring the earlier queue/order. Turn Shuffle OFF when checking library or manual-playlist playback order.

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

A `ListView<Track>` displays filtered/sorted entries through reusable cells with a fixed height of 32 pixels. Multiple selection supports Ctrl/Shift membership actions; playback activation uses the chosen row. Each populated cell binds its text to metadata and displays title — artist. Pending tracks use filename and Unknown artist fallbacks until their background read finishes; small files may update too quickly to observe the fallback.

When a cell is reused, `updateItem` unbinds its text, disposes of its previous binding and clears empty-cell content before binding to another Track. Cell rendering uses in-memory values and does not read tags or decode artwork.

Double-clicking a populated row or pressing Enter on a selected row invokes `startQueue` with its Track. Open MP3 imports and starts a single-song queue. Add MP3s and successful folder scans import entries without changing playback or the active queue.

### Library search

The search-field listener strips surrounding whitespace and converts the query to lowercase using `Locale.ROOT`.

`Track.matchesSearch` checks the filename and cached title, artist and album using the same lowercase convention. Matching uses substring searches. Empty and whitespace-only queries include all tracks in the selected view. A playlist predicate also requires membership in that playlist.

The library extractor reports metadata changes to the `FilteredList`, allowing tracks to enter or leave the current results without editing the query. For example, Unknown artist matches can disappear when an artist loads, while an artist or album search can gain matching entries.

Filtering uses in-memory values. Newly imported tracks receive metadata in the background without requiring playback; embedded fields become searchable only after the corresponding read completes.

Changes to search results do not modify the captured playback queue or interrupt playback.

### Manual playlists

`Playlist` is an immutable record containing a stable string ID, trimmed name and copied normalized absolute file paths in manual order. Its constructor rejects blank IDs, blank/overlong names and duplicate normalized members. The app permits 1–100 name characters; service create/rename checks also reject case-insensitive duplicate names and reserve All songs. New saved IDs come from `UUID.randomUUID()`.

`LibraryService.getPlaylists()` exposes an unmodifiable observable list of immutable snapshots. `createPlaylist`, `renamePlaylist`, `deletePlaylist`, `addToPlaylist`, `removeFromPlaylist` and `movePlaylistTrack` own edits on the JavaFX thread and are gated during restoration. Added/removed Track arguments must be the service's canonical library objects. Adds use a `LinkedHashSet` to append once in supplied order; the UI supplies visible selection order. A single move swaps adjacent entries, with boundary moves and unchanged renames/duplicate adds producing no save.

The View dropdown includes synthetic All songs/Favorites choices plus saved manual snapshots displayed as Playlist: name. Playlist-list changes rebuild its choices while preserving the selected ID, including through renames and membership edits. Deleting the selected playlist falls back to All songs. The existing library `FilteredList` limits membership/search and a `SortedList` applies saved playlist positions; built-in views retain source insertion order. Rows therefore keep the same Track identities and live metadata updates. Selection is retained where still visible when the view is reapplied.

New/rename dialogs remain open for invalid names. Add to playlist opens a destination chooser and skips existing memberships. Remove from playlist edits only that playlist; Delete playlist asks for confirmation. These operations keep library rows and media files intact, including shared membership in another playlist. Move buttons require one selected song and a blank search, so a move cannot be obscured by hidden results. File/folder imports still populate All songs; they do not automatically join the selected playlist. The dropdown truncates long names while keeping header action labels readable.

Schema 4 adds:

```sql
CREATE TABLE IF NOT EXISTS playlists (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    position INTEGER NOT NULL UNIQUE CHECK (position >= 0)
);

CREATE TABLE IF NOT EXISTS playlist_entries (
    playlist_id TEXT NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
    track_id INTEGER NOT NULL REFERENCES library_tracks(id),
    position INTEGER NOT NULL CHECK (position >= 0),
    PRIMARY KEY (playlist_id, track_id),
    UNIQUE (playlist_id, position)
);
```

Playlist positions preserve creation order; only song positions have move controls. The repository reads memberships by position and maps track IDs back to normalized paths. Missing media on disk remains a member, while missing database references or invalid snapshots fail loading without silently deleting data.

Each accepted playlist save replaces the complete playlist/membership snapshot transactionally, leaving library IDs, order and caches intact. The four-argument `saveChanges` accepts an optional playlist list alongside paths/cache/preferences; existing overloads pass null to preserve playlists. An empty list is a real delete-all snapshot. Active and pending snapshots remain distinct so newer edits or delete-all survive an older failure and the final shutdown flush. Edits after a failed initial restore stay session-only.

No playlist import/export, drag ordering, duplicate entries within one playlist or library-removal feature is included. Full-snapshot performance for large playlists has not been profiled. The selected view and playback queue remain session-only; saved playlist order is restored without autoplay.

### Favorites

`LibraryService` stores favorite membership as an immutable `Set<File>` of normalized library paths inside a `ReadOnlyObjectWrapper`. `getFavoriteFiles()` returns the immutable snapshot, `favoriteFilesProperty()` exposes its read-only property and `isFavorite(Track)` supports UI queries. The Track and Playlist models remain unchanged.

`setFavorites(List<Track>, boolean)` runs on the JavaFX thread after restoration. It builds a candidate set, validates every argument against the canonical library Track map, skips duplicate/unchanged members and publishes once when at least one membership changes. A later invalid Track cannot partially publish the candidate. Changed bulk edits produce one property notification and enqueue an immutable save snapshot when persistence is enabled; session-only edits remain possible after a failed initial restore.

Favorite/Unfavorite buttons operate on a copied multi-selection. With a mixed selection, Favorite adds only unmarked songs and Unfavorite removes only marked ones. A redundant action or an empty selection disables the relevant button. Row text observes both Track metadata and the favorite property, prefixing marked titles with a star. Existing cell-binding cleanup releases both dependencies on reuse.

The View dropdown has built-in All songs and Favorites choices plus manual Playlist: name labels. A manual playlist named Favorites keeps its own ID, members and order; it is not converted into built-in favorites on upgrade. Rename/delete/manual-move controls are disabled for built-in views. Add to playlist from Favorites chooses only real manual destinations.

Favorites adds a membership predicate to the existing filtered library and uses source library order, with no manual comparator or extra Track objects. Membership changes reapply the view when Favorites is selected; metadata updates continue through the library extractor. Search and favorite edits do not rebuild the captured playback queue. Removing a favorite keeps its file, library row and manual memberships; deleting a manual playlist preserves favorite membership.

Schema 5 adds:

```sql
CREATE TABLE IF NOT EXISTS favorite_tracks (
    track_id INTEGER PRIMARY KEY REFERENCES library_tracks(id)
);
```

`loadState()` reads joined favorite paths and rejects missing database references while retaining unavailable media paths. The five-argument `saveChanges` accepts a favorite set alongside paths, metadata, preferences and playlists. Null preserves favorites; an empty set clears all favorite rows. Existing overloads pass null. Replacement deletes/reinserts only favorite rows in the same transaction, after any required library paths have been inserted; unknown paths and SQL failures roll everything back.

Separate `activeFavoriteFiles` and `pendingFavoriteFiles` snapshots preserve the newest membership after failure and during the normal-close flush. Empty sets are real pending snapshots rather than absent changes. No polling loop or extra worker is introduced. Complete-set copy/saving cost for large favorite collections has not been profiled.

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

### Sidecar and embedded lyrics

The shared playback action requests lyrics for the file actually being played, independently of library selection, search, Favorites or playlist membership. It runs for Open MP3, activated rows, Previous/Next, natural advancement and repeat. Merely browsing the library leaves playing lyrics alone. The Library and Lyrics tabs share artwork/metadata and transport controls below them. The selected tab and both loaded lyric formats remain session-only. This update changes LyricsService and LyricsPane and adds EmbeddedLyricsReader; MiSPlayApp, LyricLine, LrcParser, pom.xml and schema 5 are unchanged.

`LyricsService.showLyrics(File)` normalizes the audio path and requests a sidecar in the same directory, replacing only the final extension with `.lrc`: `song.mix.MP3` becomes `song.mix.lrc`. Matching follows the host filesystem’s case rules. A sidecar with valid timed entries wins immediately, so the embedded tag is not read in that case. If it is missing, untimed, unreadable, invalid UTF-8 or exceeds a bound, the worker tries the MP3’s embedded lyrics instead.

The embedded priority is: first usable LRC-formatted text variant, then a supported binary millisecond SYLT/SLT result, then the first usable ordinary text variant. Up to 32 text variants and 32 binary synchronized frames are considered, in the order returned by Jaudiotagger. Empty/invalid variants can be skipped in favor of later usable ones. There is no variant/language chooser. Unsupported MPEG-frame timing is not guessed or converted; ordinary text can still be used. Recovered fallback status names the actual source/mode. If neither source works, the status preserves sidecar guidance and describes missing/unavailable embedded lyrics.

The JavaFX-facing service retains read-only `linesProperty`, `messageProperty` and `loadingProperty`, and adds read-only `plainLinesProperty`. Timed lines and plain strings are published into separate unmodifiable observable views. A result has only one nonempty format. A new request clears both views and shows Loading lyrics before starting file work. A plain result contains no synthetic timestamp entries, so `indexAt` returns -1 for it. Lyric errors do not report an audio failure or stop playback. An actual audio failure separately disables timed seeking/highlighting while leaving loaded text readable.

There is one active `Task<LoadResult>` and one replaceable waiting path. Every request increments an identifier, including same-path reloads. A completion may publish only while that identifier remains current and the service is open. Rapid changes drop intermediate waiting requests; a late active result cannot overwrite the latest format or song. Same-path reload queued during a read still starts a fresh read afterward. Worker code returns copied/plain data; result handlers publish on the JavaFX thread. Closure cancels active work, discards waiting work and shuts down the daemon worker without waiting on the UI thread. Existing artwork/library services remain separate.

Every playback request and Reload lyrics rechecks source selection and current bytes. There is no lyric cache, file watcher, database table, sidecar generation or tag write. When a user edits on another device, the MP3 copy at the path being played must actually be updated. The specific Musicolet files have not been supplied/tested; the supported ID3 formats are the interoperability contract, rather than a claim that every Musicolet storage option has been verified.

#### Sidecar parsing

The sidecar reader accepts a regular local file, reads at most **1 MiB plus one detection byte**, closes the stream and decodes strictly as UTF-8. The pure parser removes an optional initial BOM. Exceeded bounds reject that source without showing a partial lyric result; embedded fallback can still succeed. Legacy encoding is not guessed for sidecars.

`LrcParser.parse(String)` is pure Java. It accepts LF, CRLF or CR physical lines, surrounding whitespace and leading timestamps with **1–6 minute digits**, two second digits **00–59**, and an optional dot with **1–3 fraction digits**. Fraction digits represent tenths/hundredths/milliseconds. Consecutive timestamps repeat the remaining text at each time; every expanded entry counts toward the **10,000-entry** limit before grouping. A physical line longer than **8,192 characters** or an exceeded entry limit rejects the result rather than showing partial lyrics. Headers, untimed lines and invalid leading timestamps are ignored. Recognized enhanced `<time>` markers are removed from text, retaining line timing; word-level animation is not implemented.

The last valid signed 32-bit `[offset:N]` tag applies globally, even if found after timed lines. Adjusted time is `max(0, rawMilliseconds - offsetMilliseconds)`: +500 shows a 2-second line at 1.5 seconds; -500 shows it at 2.5 seconds. Out-of-range/malformed offset tags are ignored. Integer arithmetic retains millisecond precision. Cues sort by adjusted timestamp, and equal-time text is joined with newlines in original source order so translations can appear together. Empty text at its own timestamp remains an entry and displays as **♪**, allowing an instrumental break to replace the prior words.

#### Embedded tag reading

`EmbeddedLyricsReader.read(Path)` is blocking and has no JavaFX dependency. It reads the leading ten-byte ID3 header, validates its synchsafe size, and accepts at most **8 MiB including that header**. An oversized declared tag is rejected before allocating its body; a truncated header/payload or unsupported major version becomes a recoverable embedded-read error. The bounded bytes are passed to the appropriate Jaudiotagger **3.0.1** ID3v2.2, v2.3 or v2.4 constructor. Tag-frame parsing, including USLT/ULT string decoding, stays with the existing dependency. This reader neither parses audio samples nor calls a tag writer.

A large embedded cover contributes to the tag-size limit even when its lyrics are short. Tag/frame parsing can allocate temporary strings, artwork bytes or other objects, and compressed-frame expansion is delegated to the library. These accepted-input limits are not a total-memory or process-performance guarantee. A usable sidecar remains independent of the embedded-tag bound. Only leading MP3 ID3v2 tags are read here; other containers, trailing-only tags and legacy Lyrics3/APE lyric fields are outside this checkpoint.

ULT/USLT values may hold plain words or a complete LRC document. The reader accepts at most **262,144 Java UTF-16 code units** per text value, normalizes CRLF/CR and an initial BOM, and runs LrcParser first. A valid timed result uses the existing offset/repeated-time/grouping rules. Otherwise usable text becomes immutable plain rows, retaining internal paragraph breaks. Plain values are limited to **10,000 rows** and **8,192 characters per original line**. Ordinary words are never assigned inferred timestamps. Invalid/oversized variants can leave a diagnostic note while another source is considered.

Supported SLT/SYLT content types are lyrics and text transcription. Binary timestamps must use **milliseconds (format 2)**. The reader decodes ISO-8859-1, UTF-16 with a BOM/inherited byte order, UTF-16BE or UTF-8 according to the frame encoding; malformed binary text, incomplete entries and oversized payloads do not publish partial timing. Four-byte timestamps are interpreted as unsigned values, sorted and grouped at equal times. The encoded synchronized payload, including its description, is limited to **1 MiB**; decoded text is limited to **262,144 characters**, **10,000 entries/expanded lines** and **8,192 characters per original/assembled line before equal-time grouping**. A simultaneous multi-line group can therefore be longer than one original line while remaining under the overall bounds.

Jaudiotagger 3.0.1’s SYLT body uses a single-byte Latin-1 description delimiter even when the declared encoding is UTF-16. The reader reconstructs those lossless description bytes plus their consumed delimiter and remaining payload, then skips the actual encoding-sized description terminator. This avoids shifting UTF-16 lyric bytes and supports byte order inherited from the description BOM. Independent fixtures cover an empty description, a Unicode description and UTF-16LE entries without repeated BOMs. The workaround does not modify the tag or dependency.

When synchronized entries include newline separators, syllable fragments are concatenated into lines using each line’s first timestamp. An initial separator does not invent an intro cue. If a frame has no newline separators, each entry remains a timed row rather than guessing word or sentence boundaries. This is line-level display; there is no karaoke word animation. Frame-based timestamps (format 1), non-lyric content and unsupported/malformed variants are skipped, with plain-text fallback when available.

#### Display and seeking

`LyricLine` validates nonnegative adjusted time and non-null text. `LyricsService.indexAt(double)` binary-searches the timed view for the greatest timestamp at or before elapsed milliseconds, returning -1 before the first entry or for a negative/nonfinite clock. It also returns -1 while plain lyrics are loaded because their timed view is empty. Backward seeks need no monotonic index. A final timed row remains current until another timestamp, track change or audio failure; a timed blank can mark an outro.

`LyricsPane` binds a ListView of LyricLine to the timed view and a second ListView of String to the plain view. Both use reusable wrapped Label cells; StackPane switches their managed/visible state. Plain strings are literal text, not HTML. The hint states whether lines can seek or have no timestamps. A plain-view listener clears selection and scrolls new text to its start. It does not listen to the clock for plain-row selection or scrolling.

The existing timed clock listener resets stored time to zero before audio replacement, then follows currentTimeProperty. A result arriving later uses the current clock. Active index changes style/scroll the timed row without selecting it. A **60 ms one-shot follow delay** lets wrapped rows acquire their heights after jumps/reloads/resizes; width/height changes restart the delay. There is no periodic clock poll. Plain text remains manually scrollable.

Only a primary single click on a nonempty timed cell or Enter on a selected timed row requests seek. The guard requires an available audio attempt, a completed read, no loaded plain text, a line belonging to the current timed result and finite positive duration; cues beyond duration are ignored. Plain rows have no seek handlers. Pausing does not disable timed seeks; AudioService still owns actual paused/completed seek behavior. Closure stops the follow delay, detaches clock/timed/plain listeners, unbinds list/visibility/status properties, releases the plain list’s items and disables reload.

The handoff includes original plain/timed demonstration text for optional pasting into a tag editor. MiSPlay itself does not edit tags, export lyrics or generate timestamps. Embedded export remains later work; plain words cannot become synchronized solely by changing a file extension.

### Threading

JavaFX controls, observable library/playlist collections, Track properties, playback queue state and service scheduling state are updated on the JavaFX Application Thread. The public library/artwork/lyrics methods are called from that thread. Database tasks receive immutable playlist snapshots rather than live observable collections.

| Work | Owner | Background operation | Result applied on JavaFX thread |
| --- | --- | --- | --- |
| Metadata | `LibraryService` | One `Task<MetadataReadResult>` validates attributes and reuses cached strings or calls `MetadataService`. | Update that Track/cache/status, queue fresh/cleared metadata for saving and start the next check. |
| Folder scan | `LibraryService` | One `FolderScanTask` traverses folders and collects paths/counts. | Import a successful result or display cancellation/failure without adding partial results. |
| Artwork | `ArtworkService` | One `Task<Image>` reads, resizes and copies an embedded cover. | Update the bounded cache and display only a result for the selected path. |
| Lyrics | `LyricsService` with `EmbeddedLyricsReader` | One Task selects bounded UTF-8 sidecar or embedded ID3 lyrics, parses timing and returns timed or plain rows. | Publish complete immutable timed/plain views/status only for the current request, then start the latest waiting read. |
| Database | `LibraryService` schedules `LibraryRepository` | One worker serializes migration, complete startup loading and transactional path/cache/preference/playlist/favorite writes. | Assign restored preferences/playlists/favorites before unlocking controls, register Tracks/cache candidates and update save state. |
| Lyric clock/follow | `LyricsPane` | None; clock listeners and a 60 ms one-shot follow delay run on the JavaFX thread. | Binary-search timed entries, style/follow their virtualized rows and guard timed seek requests; plain rows stay manually scrollable. |
| Preference delay | `MiSPlayApp` | None; a 250 ms `PauseTransition` runs on the JavaFX thread. | Capture the latest choices and enqueue an immutable save snapshot. |

Metadata, folder scanning, artwork, lyrics and database work use separate single-thread executors. The preference delay runs on the JavaFX thread and schedules SQL on the database worker. Background tasks return data without manipulating live controls or the observable library.

Metadata priority changes the waiting deque without interrupting its current read. Artwork selection replaces its single waiting request without interrupting its current read. Lyrics also replaces its single waiting request, with a per-request identifier so an old same-path read cannot satisfy a reload. Result handlers check that the owning service remains open and their task is still active; lyrics additionally checks its request identifier.

### Application shutdown

`MiSPlayApp.stop()` stops the preference delay, captures the latest choices when restoration has finished, sets its shutdown guard, disposes of now-playing bindings, closes `ArtworkService`, closes `LyricsPane` and `LyricsService`, closes `LibraryService` and disposes of `AudioService`.

Artwork closing clears its current-task reference, waiting path and cache, requests cancellation of the active task and shuts down its executor. Library closing clears waiting metadata, clears task references, requests cancellation of active metadata/scanning tasks, unbinds folder status and shuts down those two executors.

Lyrics closing first stops the follow delay and detaches clock/timed/plain listeners and bindings, then cancels the active lyric task, drops waiting work and shuts down its worker. EmbeddedLyricsReader owns no executor or open stream after read returns.

Metadata, scanner, artwork and lyrics workers are daemon threads. Cancellation is a request; closed/task-identity checks prevent late completions from updating the UI, including a database restore completing after close.

The database worker is deliberately non-daemon. Closing does not cancel accepted database tasks. When persistence is enabled, a final save includes active/waiting paths, completed metadata changes and the newest pending preferences/playlists/favorites, falling back to active snapshots when no newer one exists. Empty membership snapshots are retained. `shutdown()` lets the worker drain before JVM exit. Repeating path inserts, preference upserts or the same membership snapshots leaves the same saved data. Failed-save changes are included; another failure is logged and is not claimed as saved. Unfinished/cancelled metadata reads can be rebuilt from saved paths next launch. Shutdown does not wait on the JavaFX thread. A lock can delay exit until its background timeout; forced termination bypasses normal draining.

## Verification status

The developer has reported working playback/library metadata, folder importing, embedded artwork and SQLite library-path checkpoints. Per-ID Windows results for the complete checklist below have not been recorded; the rows describe checks to run, not a blanket pass claim.

The current application was reported working after the metadata-cache and playback-preferences handoffs. The developer accepted manual playlists and Favorites and subsequently reported sidecar `.lrc` files working. Detailed per-scenario Windows results have not been recorded. The current embedded update still needs the developer’s local target-build and actual-file check.

Development-time headless artwork checks used generated MP3 fixtures and covered JPEG/PNG resizing, Unicode filenames, cache reuse and eviction, missing/damaged covers, invalid-file recovery, rapid selection changes, obsolete-result handling and shutdown. A separate layout check used substitutes for the existing services. These checks do not establish a full build or audio regression result for the Windows project.

SQLite development checks used the real 3.53.4.0 driver. Twelve repository checks covered database creation/restart, Unicode/apostrophes, normalization/duplicates/order, missing paths, transactional rollback, a 1,000-path batch, newer-schema rejection, corrupt bytes and an invalid storage directory. Twenty-one library-coordination checks covered restoration, save batching/retry, responsiveness under a database lock, folder import/cancellation, failed-restore session use, close during restore and normal-close flushing. A child JavaFX process exited with saves queued, and the parent verified its saved paths. Seven UI checks covered import bindings, storage/retry controls, restored rows, no automatic playback and layout.

The metadata-cache checkpoint added 30 focused checks: migration/rollback and retained IDs/order, Unicode/quotes, invalid cache data/version, atomic path/cache writes, restart cache hits verified through parser-call counts, time-only and size-only invalidation, submillisecond timestamps, playback/reimport refresh, missing/repaired files, bounded reads during concurrent edits, database locks, newest-result retry/close behavior, fallback caching and an 80-track batch. Those 30 and the existing 40 repository/library/UI checks passed together.

The preferences checkpoint passed a separate set of 70 checks: 35 model/repository checks, 16 settings/cache-coordination checks and 19 application checks. They covered schema 0/1/2 upgrades, retained caches, every repeat/shuffle combination, zero/full/decimal volume, malformed values, singleton-row enforcement, atomic rollback, startup gating, coalesced snapshots, first explicit playback volume, retry, immediate-close capture and failed-startup session use.

The playlist checkpoint passed 150 checks: 26 playlist model/repository, 23 playlist coordination, 29 playlist application and 2 layout checks, plus the existing 70 preferences/cache checks. Coverage included schemas 0–3 upgrades and retained library IDs/caches/settings; copied/normalized membership; Unicode/quoted names; duplicate/no-op edits; shared/empty playlists; atomic rollback; missing/repaired media; blocked-save responsiveness; newest-state retry including deletion of the final playlist; immediate close after import/create/add; and session-only edits after failed startup. Application checks exercised the actual dialogs/buttons, name correction, multi-selection, view/selection preservation, moves, scoped/live search, rename/delete cancellation, filtered queue capture/navigation wiring and restart without autoplay. Rendered layout checks covered a maximum-length name at the default and minimum window sizes; an identified header-label squeeze was fixed. The existing harness song-list selector was updated to distinguish the dropdown's internal list.

Favorites passed 88 focused checks: 32 repository/migration, 20 service and 36 application/layout. They covered schemas 0–4 upgrades and retained data, a legacy manual playlist named Favorites, immutable/validated bulk edits, no-op suppression, mixed selection, stars, main-library order, scoped/live search, queue capture/navigation wiring, existing playlist dialogs/controls, blocked/failed saves and latest-empty retry, immediate close, unavailable/repaired media, failed-startup protection and readable default/minimum layouts. The earlier 150-check playlist suite was not rerun and remains a separate recorded result.

Those persistence checks ran with JDK 17 and JavaFX 21.0.1. Production repository, LibraryService and application code were exercised with API substitutes for unchanged model/audio/metadata/folder components where full source was unavailable. They are separate development harnesses, not a committed test suite or proof of the complete Java 25/JavaFX 25.0.2 Windows application. Local Windows playback verification remains necessary for later changes.

For the preferences checks, all four changed/new classes and the existing ArtworkService compiled. The audio spy recorded requests and volume without decoding MP3s; the queue fixture did not verify the production shuffle algorithm. Those behaviors still depend on local checks with the complete project.

For the playlist checks, the four changed/new classes compiled with the existing PlaybackPreferences and ArtworkService sources. Track, TrackMetadata, RepeatMode, audio, queue, metadata-reader and folder-task APIs were substitutes. The queue/audio fixtures verified capture/navigation requests and volume state without testing the actual shuffle implementation or MP3 playback. The harness files are not shipped as production sources.

For Favorites, the three changed classes compiled with the unchanged Playlist, PlaybackPreferences and ArtworkService sources. Track, TrackMetadata, MetadataStatus, RepeatMode, audio, queue, metadata-reader and folder-task APIs were substitutes. Text fixtures exercised cache coordination; audio/queue spies verified requests without real MP3 decoding or the production shuffle algorithm. The same Java 17/JavaFX 21.0.1 harness limits apply, and the complete Java 25/JavaFX 25.0.2 Windows application still depends on local verification.

For LRC, all five changed/new production classes compiled with the unchanged LibraryService, LibraryRepository, Playlist, PlaybackPreferences and ArtworkService. The checkpoint passed **27 parser/model, 24 lyrics-service and 38 application/layout checks**, plus the **32 repository, 20 library-service and 36 Favorites/application checks**: **177 total**. Focused cases covered fractional/long/repeated timestamps, simultaneous translations, blanks, global offsets/clamping, immutable results, Unicode/BOM, malformed input and bounds, same-path edits, missing/repaired sidecars, latest waiting requests, stale/close guards, clock boundaries/backward seeks, click/Enter adjusted seeks, unknown-duration/audio-error protection, library/queue independence, navigation, reload during playback, virtualized wrapped rows, default/minimum layout, following after resize, shutdown and restart without autoplay. Rendered snapshots exposed and helped fix a wrapped-row follow issue on resize; the final checks exclude hidden VirtualFlow reuse cells when judging visibility.

The LRC harness used the same JDK 17/JavaFX 21.0.1 environment and API substitutes for unavailable unchanged Track/metadata/status/repeat/audio/queue/folder code. Audio spy methods supplied clocks/durations and recorded seek requests; they did not decode MP3s or verify the production queue's repeat/shuffle implementation. Full Java 25/JavaFX 25.0.2 compilation and actual Windows audio/LRC synchronization, paused/completed seek behavior and repeat modes remain pending locally. The older 150-check playlist suite was not rerun in this checkpoint.

For embedded lyrics, the three changed/new production classes compiled without deprecation warnings with the unchanged MiSPlayApp, LrcParser, LyricLine, LibraryService, LibraryRepository, Playlist, PlaybackPreferences and ArtworkService. **40 reader, 23 source-coordination and 26 application/layout checks** passed, together with the **177 existing LRC/Favorites checks: 266 total**. New fixtures independently construct ID3 bytes and append generated silent MP3 audio, covering v2.2/2.3/2.4, multiple encodings/Unicode, plain paragraphs, embedded LRC offsets/grouping, multiple variants, binary SYLT/SLT timing, unsigned timestamps, newline syllable assembly, UTF-16 description/byte-order boundaries, malformed/truncated/oversized inputs, source priority and fallback, immutable results, same-path edits, rapid mixed-format requests, shutdown, read-only source bytes and no automatic export. One fixture was also opened by the actual MP3 tag/audio-header library as a compatibility sanity check.

The application checks exercised real production lyric readers/UI with controllable clock/audio and queue substitutes: timed/plain switches, no plain seek/highlight, source hints, sidecar addition/removal, reload, navigation, unknown-duration/audio-error guards, library/queue independence, restart without autoplay, virtualization and rendered default/minimum layouts. Text metadata/folder/Track components still used existing API substitutes where full source was unavailable. All checks used JDK 17/JavaFX 21.0.1, not the target Windows environment. They did not decode MP3s for playback, test the production shuffle algorithm, run the complete Java 25/JavaFX 25.0.2 application or inspect the developer’s Musicolet-edited files. The older 150-check playlist suite remains historical and was not rerun. Local checks M225–M248 cover the new handoff.

No committed automated project test suite or memory/CPU profiling baseline has been recorded. The Yaezakura metadata display issue remains unresolved.

## Manual checks

Use MP3 files outside the Git repository. A small set of known working files is sufficient. Record actual results; these rows are a checklist, not a claim that every case has passed.

Unless a check specifies otherwise, choose All songs and set Repeat OFF and Shuffle OFF after restoration; those playback choices now persist. Use a disposable fresh profile when a check requires unsaved defaults. Some pending-state/cancellation checks need a sufficiently large batch; if work finishes too quickly, record the check as not exercised rather than passed.

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
| M133 | Upgrade a disposable schema-1 database containing known paths/IDs. | Schema becomes 5; paths/IDs/order remain, cache columns fill from successful reads, favorites/playlists start empty and preferences use defaults until edited. |
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
| M145 | Upgrade a disposable schema-2 library with valid cached tags. | Schema becomes 5; paths, IDs, order and caches remain, favorites/playlists start empty and the preferences table initially uses unsaved defaults. |
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
| M157 | Upgrade a disposable schema-3 library with cached tags and saved playback preferences. | Schema becomes 5 with empty favorite/playlist tables; paths, row IDs, insertion order, valid caches and playback settings remain. |
| M158 | Create an empty playlist, then cancel a separate New playlist dialog. | The created playlist is selected and saved even without members; cancelling creates nothing. |
| M159 | Try blank/overlong names, a differently cased existing name and All songs in create/rename dialogs, then correct the input. | Invalid input keeps the dialog open without an edit; a valid trimmed unique name is accepted. |
| M160 | In All songs, select separate songs with Ctrl and a range with Shift, then add them to a chosen playlist. | Members append once in visible selection order; the current library view and visible selection remain available. |
| M161 | Add the same songs again, along with one new member. | Existing members are skipped; only the new song appends and no duplicate row appears in that playlist. |
| M162 | Add a song to two playlists, then remove it from one. | Only that membership changes; the other playlist, library row and disk file remain. |
| M163 | With blank search, move one selected song up/down, including first/last boundaries. | The playlist's order changes one step and selection follows it; the relevant boundary button disables. All songs keeps its library order. |
| M164 | Search in a playlist or select several members. | Move buttons disable while search is nonblank or selection is not exactly one; selected memberships can still be removed. |
| M165 | Search a playlist by filename/tag, then refresh a changed member's metadata. | Results include only matching members and update through shared Track metadata without changing the captured queue. |
| M166 | With Shuffle OFF, activate a playlist row and use Previous/Next and natural advancement. | A new queue captures that playlist's visible manual order and follows normal repeat rules. |
| M167 | Start a playlist queue, then reorder/remove members, switch views, import music and delete that playlist. | The active queue retains its captured files/order and can still play removed memberships; editing does not reload current audio. |
| M168 | Search inside a playlist, then double-click or press Enter on a result. | The new queue contains only those currently visible matching members, with existing repeat/shuffle choices. |
| M169 | Rename the selected populated playlist. | Its ID, membership/order, current view and still-visible selection remain; the dropdown label changes. |
| M170 | Cancel Delete playlist, then confirm deletion. | Cancel retains it; confirmation removes the playlist and returns its view to All songs while music files and other memberships remain. |
| M171 | Delete the final playlist, close normally and reopen. | It stays deleted, no orphan membership remains, and Add to playlist is disabled until a playlist exists. |
| M172 | Save populated and empty playlists, close normally and reopen. | IDs/names, creation order and member order return; the view starts in All songs with no autoplay or restored queue, and preferences still apply. |
| M173 | Import files, create a playlist, add/reorder/rename and immediately close normally. | Accepted paths save before membership references; the final playlist snapshot survives the next launch when storage is writable. |
| M174 | In a disposable harness, block/fail an older playlist write, submit newer edits or delete all, remove the fault and Retry save. | Controls remain responsive; the newest waiting snapshot wins, including an empty list, and successful retry clears failure status. |
| M175 | In a disposable harness, fail initial loading, then create/edit temporary playlists and close. | Session editing works after loading fails, saving stays disabled and the unreadable/future database is not overwritten. |
| M176 | Move a disposable playlist member's file away, then restore and explicitly reimport/request it. | Its library/playlist slot remains while unavailable; metadata can recover in the same launch without recreating membership. |
| M177 | Use a 100-character playlist name at the default and minimum window sizes. | Only the dropdown name truncates as needed; header/action labels and main controls remain readable within the window. |
| M178 | In a disposable harness, fail a playlist write batched with paths, cache and preferences. | The complete batch rolls back, including playlist replacement; retry can save a newer consistent snapshot. |
| M179 | In a disposable harness, provide incompatible playlist tables, invalid orphan membership or a future schema version. | Loading reports failure without resetting data; failed migration rolls back its schema changes. |
| M180 | Delay startup in a disposable harness and close before restoration finishes. | Playlist controls remain gated while loading; close prevents late playlist/model updates and temporary defaults do not overwrite saved state. |
| M181 | Upgrade a disposable schema-4 library with manual playlists, caches and saved preferences. | Schema becomes 5 with empty built-in Favorites; existing IDs/order/cache/settings remain, including a manual playlist named Favorites. |
| M182 | Delay initial loading in a disposable harness and inspect favorite controls and writes. | Favorite/Unfavorite and view controls remain gated; restoring membership/stars starts no audio and writes no default favorite snapshot. |
| M183 | Select an unmarked song in All songs and click Favorite, then view a manual playlist containing it. | Both views show its star through the same Track; Favorites contains the song once. |
| M184 | Select marked and unmarked songs together, then apply each favorite action. | Both actions are available for mixed selection, only relevant memberships change, and redundant/empty-selection actions disable without duplicate writes. |
| M185 | Favorite several songs in a different order from the library, then open Favorites. | Rows follow main-library insertion order; manual reorder controls are disabled. |
| M186 | Search Favorites, then refresh a changed marked song's metadata. | Only marked matching songs appear; metadata refresh updates the same Track's starred row and search results. |
| M187 | Favorite/unfavorite songs from a manual playlist, then remove a manual membership or delete that playlist. | Favorite state stays independent; library rows, other memberships and actual music files remain. |
| M188 | With Shuffle OFF, activate Favorites and then a filtered Favorites result. | Each explicit activation captures that view's visible files/order; normal repeat/shuffle choices still apply. |
| M189 | Start a Favorites queue, then unfavorite queued songs, search and change views. | The original queue and current playback remain intact, including songs no longer marked. |
| M190 | Remove the final favorite, close normally and reopen. | The empty-state instruction appears and empty membership stays saved; library songs/files and playlists remain. |
| M191 | Select Favorites rows and use Add to playlist. | The chooser offers real manual playlists, appends selected songs once and retains favorite state/current view. |
| M192 | Compare built-in Favorites with a manual playlist named Favorites. | Labels distinguish Favorites from Playlist: Favorites; only the manual playlist has rename/delete/move controls and its own order. |
| M193 | Save favorites and preferences, close normally and reopen. | Membership/stars and preferences return; the initial view is All songs with no restored queue or automatic playback. |
| M194 | Import files, edit a playlist, favorite songs, change preferences and immediately close normally. | Paths save before their references and the final favorite/playlist/preference snapshots survive when storage is writable. |
| M195 | In a disposable harness, fail/block an older favorite save, make newer edits or remove all, then clear the fault and press Retry save. | Controls remain usable; the latest snapshot wins, including empty membership, and successful retry clears failure status. |
| M196 | In a disposable harness, fail startup loading, then import and favorite a session song. | Temporary choices work after loading unlocks; closing leaves the unreadable/future saved database untouched. |
| M197 | Make a disposable marked song unavailable, then restore it and explicitly reimport/request it. | Favorite membership remains while metadata fails and recovers; the repaired song needs no new favorite entry. |
| M198 | In a disposable harness, fail a favorite insert batched with path/cache/preferences/playlist changes. | The complete transaction rolls back, including snapshot deletion; invalid references do not cause partial state. |
| M199 | Inspect default/minimum window sizes with a maximum-length manual-playlist label and favorite controls visible. | Star rows and all main action labels remain readable; only the dropdown name truncates as needed. |
| M200 | Close a disposable harness during delayed favorite restoration. | No late favorite/model publication occurs and temporary empty defaults do not overwrite saved membership. |
| M201 | Start from the accepted Favorites checkpoint, install the five LRC Java files and run the complete project with Java 25/JavaFX 25.0.2. | The project compiles and opens on Windows; Library and Lyrics tabs share usable playback controls. Record the target-build result. |
| M202 | Open Lyrics before playing a song, then return to Library. | Lyrics is empty with initial guidance; Reload lyrics is disabled; no audio or database lyric write starts. |
| M203 | Place a UTF-8 same-basename .lrc beside a working MP3, start it from a library row and open Lyrics. | Matching lines appear and the accented row follows real playback without changing queue membership. |
| M204 | Use a Unicode/multiple-dot filename and an optional UTF-8 BOM. On Windows also try .LRC, then a mismatched basename or .lrc.txt. | The matching sidecar follows Windows case rules; Unicode displays correctly; mismatched names/extensions show missing-file guidance when no usable embedded lyrics exist. |
| M205 | Use an MP3 with no embedded lyrics, play without a sidecar, create the expected .lrc during playback and press Reload lyrics. | Missing guidance names the expected file; the new lines load while audio continues. |
| M206 | Edit, delete and restore a sidecar during playback, using Reload lyrics and later replaying the same song. | Each explicit request rechecks the file, clears obsolete text immediately and recovers without restart or stale caching. |
| M207 | Use whole seconds and 1/2/3 fraction digits, repeated out-of-order timestamp tags and untimed metadata lines. | Cues sort/expand at the specified millisecond times; headers and untimed text do not become lyric rows. |
| M208 | Put original and translated lines at the same time, followed later by an empty timed line. | Simultaneous text shares a wrapped active row in file order; the later blank displays ♪ instead of holding earlier words. |
| M209 | Put [offset:+500] after timed lines, then try -500 and a later valid replacement offset. | The last valid offset affects every cue; positive advances and negative delays by the stated milliseconds; seeking uses adjusted times. |
| M210 | Use a positive offset moving several cues before zero and a malformed/out-of-range offset. | Pre-zero cues group at zero; malformed offset tags are ignored without timing overflow. |
| M211 | Seek backward/forward with the main slider and restart the track, including before the first lyric time. | Highlight recomputes from the actual clock and clears before the first cue; it is not stuck at a later index. |
| M212 | Single-click a lyric row, then select another with keyboard arrows and press Enter. | The click and Enter seek to their row times. Moving selection alone neither seeks nor changes the current-line highlight. |
| M213 | Seek from lyrics while playing, paused and after natural completion of the final queue song. | Existing audio-service seek behavior works in all supported states; actual Windows behavior is recorded, not inferred from the spy. |
| M214 | Try lyric actions before duration becomes available, with zero/unknown duration, after an audio failure and on a cue beyond duration. | No lyric seek occurs in those cases; audio failure clears the active highlight. A new working song permits seeks again. |
| M215 | Use Previous/Next, natural advancement, Repeat One/All and a fresh Open MP3 queue with different/missing sidecars. | Every actual replacement/repeat reloads its own lyrics and resets the prior clock; missing sidecars never retain old words. |
| M216 | Change search, View, favorites or playlist membership while another song plays; switch tabs repeatedly. | The active queue/audio/lyrics stay tied to the playing file; merely selecting a library row does not load its sidecar. |
| M217 | Skip rapidly among sidecars while a read is delayed; also reload the same path while its older read is active in a disposable harness. | Only the latest request may publish; one latest waiting path is retained and same-path reload still reads afresh. |
| M218 | Using MP3s with no embedded lyrics, try empty/untimed-only, malformed-timestamp, malformed UTF-8 and unreadable sidecars, then repair and reload. | Distinct empty/recovery messages appear without stopping audio or showing partial stale lyrics; repaired UTF-8 input recovers. |
| M219 | With no embedded lyrics in disposable MP3 fixtures, exceed 1 MiB, 8,192 physical-line characters or 10,000 expanded timed entries, including repeated tags. | The whole lyric result is rejected with clear limit guidance. Exact supported boundaries remain usable. |
| M220 | Load many original fixture lines with long wrapped text, jump far ahead/back and resize between default and minimum dimensions. | Current-line highlighting remains visible after layout settles; a virtualized subset of cells renders and shared controls/reload fit. |
| M221 | Scroll lyrics manually without changing playback; click empty space or use a secondary click. | Manual browsing and nonactivation clicks do not seek. Following resumes on the next current-line change or resize. |
| M222 | Close while a lyric read, waiting request or follow delay is active, then reopen. | No late lyric publication/scroll occurs after close; the new launch has empty lyrics, the Library tab and no autoplay. |
| M223 | Inspect a disposable profile before/after lyric playback/reload, then verify Favorites/manual playlists/preferences after restart. | SQLite stays at schema 5; existing data survives. No lyric tables, persisted line snapshots or source-file edits appear. |
| M224 | Play a supported enhanced LRC with leading line times plus `<word-time>` markers; then remove it from an MP3 with usable embedded lyrics. | Recognized word markers are hidden and line-level timing remains; removal permits the new embedded fallback instead of retaining obsolete sidecar rows. |
| M225 | Install the three embedded Java files over the working LRC checkpoint and run with Java 25/JavaFX 25.0.2 on Windows. | The complete project compiles and opens; existing dependencies and schema 5 remain sufficient. Record the actual target-build result. |
| M226 | Copy a Musicolet-edited MP3 to the Windows path MiSPlay plays, confirm no usable matching sidecar exists, and play it. | Supported embedded lyric tags load in Lyrics; record the actual file/tag format and result rather than assuming every editor option is identical. |
| M227 | Use plain embedded words with paragraph breaks, Unicode and text resembling markup. | A wrapped plain list preserves the words/blank paragraphs literally and shows that timing is unavailable. |
| M228 | Select plain rows, click them, press Enter and let the playback clock advance while reading farther down. | Plain interactions do not seek, create an active timed highlight or force clock-based scrolling. |
| M229 | Embed valid LRC text with offsets, repeated timestamps, translations and timed blanks in a lyric field. | The synchronized view uses the existing parser behavior and adjusted times, including backward seeks. |
| M230 | Place a valid matching sidecar beside an MP3 with different embedded plain/timed lyrics, then reload. | The usable sidecar wins and its name appears in status; embedded data is not needed for that request. |
| M231 | Remove the sidecar, then try untimed-only, invalid UTF-8, unreadable and over-limit sidecars with usable embedded lyrics. | Each unusable sidecar permits embedded fallback without keeping old rows or stopping audio. |
| M232 | Edit tags on the exact MP3 being played and press Reload lyrics, switching plain to timed and timed to plain. | Fresh bytes/format appear without restarting audio; the old format clears, the hint changes and plain selection starts cleared. |
| M233 | Keep an older Windows copy while editing a different device’s copy, then copy the updated file into the played location and reload. | Only the actual played file’s bytes are read; after copying/reloading, the updated tags appear without a lyric cache. |
| M234 | Use disposable ID3v2.2, v2.3 and v2.4 files with supported encoded ULT/USLT text, including Japanese/Korean/Chinese and accents. | The supported tag versions/encodings render correctly; record which real edited files pass locally. |
| M235 | Use millisecond SYLT/SLT fixtures with multiple entries, equal/out-of-order times and Unicode text. | Supported binary timing becomes sorted synchronized rows; equal-time text groups without signed overflow. |
| M236 | Use SYLT syllable fragments separated by line markers, plus a frame with no line markers. | Marked fragments assemble into lines at their first times; unmarked entries stay individual timed rows without guessed word boundaries. |
| M237 | Use UTF-16 SYLT with an empty or Unicode description and UTF-16LE entries inheriting the description byte order. | Description boundaries do not shift lyric bytes and Unicode/timing remain intact. |
| M238 | Use MPEG-frame-based SYLT, non-lyric synchronized content or malformed/truncated binary entries, with and without ordinary embedded text. | Unsupported/bad timing is not guessed; usable text remains a plain fallback, otherwise guidance appears without partial timing. |
| M239 | Use several embedded text variants, including empty/invalid, plain and timed entries, plus usable SYLT. | First usable embedded LRC text wins; otherwise supported SYLT wins over the first plain variant. No language chooser is implied. |
| M240 | In disposable fixtures, exceed 32 text variants/32 SYLT frames or the embedded text/line/entry/payload bounds. | Only the supported candidate range is considered; oversized results are rejected without partial accepted lyrics, with alternate sources still usable. |
| M241 | Use a declared/truncated/unsupported ID3 header and a tag over 8 MiB, including a large-cover case; also try a valid sidecar. | Embedded errors are recoverable. A valid sidecar still displays independently; the tag-size limit includes artwork. |
| M242 | Compare the MP3 bytes and sidecar directory before/after lyric playback/reload and normal close. | Reading does not rewrite tags/audio or create .lrc files. Export and tag editing are not part of this update. |
| M243 | Try timed click/Enter seeks with known duration, paused/completed playback, unknown duration, audio errors and out-of-range cues. | Existing timed seek guards and actual Windows behavior hold; plain content never gains a seek action. |
| M244 | Navigate with Open MP3, Previous/Next, natural advancement and repeat among sidecar, embedded timed/plain and lyric-free songs. | Every playback request selects the current song’s source; lyric-free songs clear both prior formats and repeat rechecks bytes. |
| M245 | Delay reads in a disposable harness, skip quickly among formats and reload the same path while its older read is active. | Only the newest request publishes; one latest waiting path is retained, even across timed/plain format changes. |
| M246 | Read long wrapped plain and timed lyrics at default/minimum window sizes; switch tabs, reload and resize. | Both lists stay virtualized and readable, plain reload returns to the start, timed following settles on the current line, and shared controls fit. |
| M247 | Change search, favorites, playlists and library selection while embedded lyrics play, then restart normally. | Captured audio/queue/lyrics remain tied to playback; restart retains saved library data but begins with empty lyric views and no autoplay. SQLite stays at schema 5. |
| M248 | Close during an embedded read, queued request or timed follow delay, then launch again. | No late lyric publication/scroll occurs after close. Streams/tasks/listeners are released and no lyric state is restored automatically. |

Maven **validate** checks project configuration; it does not compile Java or verify audio. A successful application launch exercises compilation, while these checks verify playback behavior.

For a check failure, record the check ID, exact action, error text, JDK/JavaFX versions and whether the same file works elsewhere. Do not commit personal audio files as evidence.

## Commit checkpoints

A checkpoint is ready when its intended behavior works, relevant manual checks pass and the staged files describe one understandable change.

Every implementation handoff must include the updated **README.md**, **CHANGELOG.md** and **docs/DEVELOPMENT.md**, together with the exact commit message. This is the standing user request; do not defer the documentation or message to a follow-up. The handoff should state:

1. What is ready to commit.
2. Which checks were run and which still need to be run locally.
3. The exact proposed commit message.
4. Where to find all three updated documentation files supplied with the implementation.

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


The embedded checkpoint uses `feat: support embedded MP3 lyrics` for its three changed/new Java files and updated documentation. The handoff includes SETUP.md, original plain/timed sample text and COMMIT_MESSAGE.txt. Its prerequisite is the working LRC checkpoint, whose commit was `feat: add synchronized sidecar lyrics`. Run the complete Windows project and relevant manual checks before committing locally; the harness does not establish that target-build result.

For later checkpoints, update `CHANGELOG.md` when functionality changes. Update the README for changed features, prerequisites or run instructions. Update this guide when responsibilities, workflows or verification change. Do not invent documentation changes just to fill a checklist.

## Upcoming implementation notes

- Record local embedded/Musicolet checks M225–M248; explicit lyric export can follow as a separate checkpoint. Plain export must not invent timestamps.
- Keep functional layout/usability fixes with their features; perform the main visual redesign after core behavior is stable and performance has been measured, before final Windows packaging.
- Keep favorite/playlist edits independent from the active playback queue; profile full-snapshot saving before optimizing large collections.
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
- [ID3v2.4 lyric frame definitions](https://id3.org/id3v2.4.0-frames)
- [Jaudiotagger tag mapping](https://www.jthink.net/jaudiotagger/tagmapping.html)
