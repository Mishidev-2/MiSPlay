# Development guide

## Scope and approach

MiSPlay is a personal Windows 11 desktop project developed in small, working increments. Each increment should have a clear behavior to verify. Keep the architecture understandable and add dependencies when a feature needs them.

The current checkpoint includes MP3 playback, captured queues with repeat/shuffle, a session-only library, background metadata loading, folder importing and embedded album artwork. Next is SQLite persistence, starting with remembering imported tracks after restart. Lyrics, tray/overlay behavior, updates and Windows packaging remain future work.

## Current architecture

| Class | Owns | Does not own |
| --- | --- | --- |
| `MiSPlayApp` | Controls, bindings, file/folder choosers, playback actions, service instances and shutdown coordination | Audio decoding, tag parsing or database storage |
| `AudioService` | The current `MediaPlayer`, playback/seek/volume requests, callbacks, audio errors and disposal | Library imports, UI controls or metadata/artwork caching |
| `LibraryService` | Observable tracks, normalized-path lookup, metadata scheduling and folder-scan coordination | Audio playback, artwork caching or persistent storage |
| `Track` | A file, observable metadata/status and normalized search matching | File reading, worker scheduling or controls |
| `TrackMetadata` | Immutable title, artist and album strings | Parser objects, artwork or loading state |
| `MetadataStatus` | PENDING, LOADING, LOADED and FAILED states | Worker execution or persistence |
| `MetadataService` | Reading tag strings and applying missing-field fallbacks | Controls, scheduling or artwork caching |
| `FolderScanTask` | Recursive folder traversal, discovered paths, counts and cancellation checks | Adding tracks to the library or changing playback |
| `FolderScanResult` | An unmodifiable copy of discovered files and a skipped-entry count | Scan execution or library changes |
| `ArtworkService` | Selected-song artwork properties, its worker, latest waiting request and bounded thumbnail cache | Audio playback, library metadata reads or remote-image fetching |
| `PlaybackQueue` | Captured original/active orders, position, navigation, repeat and shuffle rules | Controls, audio resources or file reading |
| `RepeatMode` | OFF, ALL and ONE choices | Queue navigation or playback execution |

### Opening a track

1. Open MP3 shows a file chooser. Cancelling leaves the library and playback unchanged.
2. The selected file is imported through `LibraryService.addFiles`, preserving an existing Track for a known path. `PlaybackQueue.start` creates a single-song queue.
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
- `LibraryService` owns session Track objects, the path lookup, pending metadata and folder scanning.
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

`LibraryService.getTracks()` exposes an unmodifiable `ObservableList<Track>`. A normalized absolute-path map preserves one Track object per imported path.

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

`LibraryService` automatically schedules metadata reads when new tracks are imported. Library rows and search results update as reads finish, without requiring playback.

The shared `playFile` action calls `loadMetadata`, covering Open MP3, row activation, manual navigation and automatic queue advancement.

`loadMetadata` releases the previous label bindings and binds the now-playing labels to the selected Track's metadata and loading status. Cached metadata appears immediately. Pending tracks display their filename fallback and `Waiting for tags...` or `Reading tags...` as appropriate.

Selecting a track asks `LibraryService` to prioritize its pending read after the current read finishes. Completed reads update their own Track objects; labels observe only the selected Track, so another result cannot replace the current song's details.

Read failures retain the fallback values, show `Tags unavailable — showing filename.` for the selected track and log the exception independently of audio errors.

Known issue: Yaezakura displays a shortened artist/details line even though Windows Properties lists artist and album information. The cause remains undiagnosed and investigation is deferred.

### Session metadata cache

`LibraryService` maintains a map from normalized absolute paths to Track objects. Reimporting a known path preserves its model and cached values without scheduling another metadata read.

A new Track contains filename and Unknown artist/album fallbacks. Its observable status starts as PENDING and progresses through LOADING to either LOADED or FAILED. `isMetadataLoaded()` returns true only for LOADED.

A successful read calls `Track.setMetadata` on the JavaFX Application Thread. This marks metadata as loaded, stores the result and notifies listeners. Successful reads with missing tags also cache their fallback values.

Switching songs does not cancel metadata reads. A completed result remains useful on its corresponding Track even if that song is no longer playing.

A failed read marks the Track FAILED, logs the error and allows the next waiting read to proceed. Failed reads are not automatically retried during the current launch; duplicate imports do not reschedule them.

This cache contains metadata strings for all imported tracks and lasts for the current library session. It does not persist across launches or detect file edits. Its lifetime and size policy differ from the eight-entry artwork cache.

### Folder importing

Add Folder opens a `DirectoryChooser`. Selecting a folder asks `LibraryService.scanFolder` to start a `FolderScanTask` on a dedicated background executor.

The task searches the selected folder and subfolders for regular files with a case-insensitive `.mp3` extension. Symbolic links are not followed.

Scanning collects file paths without modifying the library. A successful task returns a `FolderScanResult` with an unmodifiable copy of its file list and a skipped-entry count. The JavaFX completion handler calls `LibraryService.addFiles`, which prevents duplicate paths and schedules metadata reads.

Unreadable child entries are logged and skipped. Failure to access the selected root fails the scan without importing its collected files.

The UI displays an indeterminate spinner, checked-file/discovered-MP3 counts and Cancel scan. Counts are reported periodically; the total is not known in advance, so this is not a percentage. Add Folder is disabled while a scan is active.

Cancelling an active scan discards its collected results. Cancelling the folder chooser changes nothing. A completed scan reports newly added tracks, total discovered MP3s and skipped unreadable entries when present.

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
| Metadata | `LibraryService` | One `Task<TrackMetadata>` reads a captured file through `MetadataService`. | Update that Track's metadata/status and start the next waiting read. |
| Folder scan | `LibraryService` | One `FolderScanTask` traverses folders and collects paths/counts. | Import a successful result or display cancellation/failure without adding partial results. |
| Artwork | `ArtworkService` | One `Task<Image>` reads, resizes and copies an embedded cover. | Update the bounded cache and display only a result for the selected path. |

These operations use separate single-thread executors. Background tasks return data without manipulating live controls or the observable library.

Metadata priority changes the waiting deque without interrupting its current read. Artwork selection replaces its single waiting request without interrupting its current read. Result handlers check that the owning service remains open and their task is still active.

### Application shutdown

`MiSPlayApp.stop()` disposes of the now-playing bindings, closes `ArtworkService`, closes `LibraryService` and disposes of `AudioService`.

Artwork closing clears its current-task reference, waiting path and cache, requests cancellation of the active task and shuts down its executor. Library closing clears waiting metadata, clears task references, requests cancellation of active metadata/scanning tasks, unbinds folder status and shuts down both executors.

Cancellation is a request; closed/task-identity checks prevent late completions from updating the UI. The worker threads are daemon threads.

## Verification status

The developer has reported working playback/library metadata, folder importing and embedded artwork checkpoints. Per-ID Windows results for the complete checklist below have not been recorded; the rows describe checks to run, not a blanket pass claim.

Development-time headless artwork checks used generated MP3 fixtures and covered JPEG/PNG resizing, Unicode filenames, cache reuse and eviction, missing/damaged covers, invalid-file recovery, rapid selection changes, obsolete-result handling and shutdown. A separate layout check used substitutes for the existing services. These checks do not establish a full build or audio regression result for the Windows project.

No committed automated project test suite or memory/CPU profiling baseline has been recorded. The Yaezakura metadata display issue remains unresolved.

## Manual checks

Use MP3 files outside the Git repository. A small set of known working files is sufficient. Record actual results; these rows are a checklist, not a claim that every case has passed.

Unless a check specifies otherwise, start it with Repeat OFF and Shuffle OFF. Some pending-state/cancellation checks need a sufficiently large batch; if work finishes before the action can be exercised, record the check as not exercised rather than passed.

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
| M34 | Close and reopen MiSPlay | Song list starts empty |
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
| M80 | Import A and B, wait for their metadata, play both, then return to A. | Returning to A uses its cached metadata without another metadata task. |
| M81 | Open an already cached file again using Open MP3. | Its existing model and metadata are preserved without creating a duplicate library entry. |
| M82 | Let a cached song finish with Repeat ONE enabled. | Audio restarts without another metadata read. |
| M83 | Quickly switch from an uncached song to a cached song. | The cached song's details remain displayed; an older result cannot overwrite them. |
| M84 | Start another queue containing previously cached tracks. | Cached metadata remains available across queue changes. |
| M85 | Exercise navigation, library search, volume, seeking, repeat and shuffle. | Existing playback and library behavior continues working. |
| M86 | Restart the application and reimport a previously cached file without playing it. | Its metadata is read again in the new session and appears when the background read finishes. |
| M87 | Import unplayed tagged tracks in a fresh session. | Pending rows can show filename — Unknown artist, then update automatically without playback; small files may load too quickly to see the fallback. |
| M88 | Play a tagged song whose metadata is cached or still pending. | The row and now-playing labels use its metadata when available; cached values require no new read. |
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
| M100 | Add more songs during metadata loading and reimport known files. | New files are scheduled; known paths keep their model/status without duplicate entries or reads. |
| M101 | Import an empty bad-tags.mp3 with valid files using Add MP3s, without playing the invalid file. | A metadata error is logged for that file, fallback values remain and valid files continue loading. |
| M102 | Close while metadata is still loading. | The window/audio close and the Maven run finishes without late UI updates. |
| M103 | Import nested folders with Unicode names, mixed-case .mp3 extensions and unrelated files. | Regular MP3 files are found in all levels; unrelated files are ignored. |
| M104 | Reimport the same folder, then one of its subfolders. | Known paths are not duplicated; the summary reports zero new tracks when all were already imported. |
| M105 | Import a folder containing no MP3s. | The summary reports zero discovered/added tracks without an error. |
| M106 | Scan a folder while music plays; use seeking, search and scrolling. | Playback and controls remain usable, and the captured queue keeps its membership. |
| M107 | Cancel the folder chooser during playback. | Library and playback remain unchanged. |
| M108 | Cancel a scan that is still running, then start another scan. | The cancelled scan adds no partial results; a subsequent scan can complete normally. |
| M109 | Scan a folder with an unreadable child entry where permissions allow this check. | Accessible files import; skipped entries are logged and included in the summary. |
| M110 | Attempt a scan whose selected root becomes inaccessible before traversal. | The scan reports failure and adds none of its collected paths. |
| M111 | Close the app while a folder scan is running. | The window/audio close and the Maven run finishes cleanly. |
| M112 | Play songs with landscape PNG and portrait JPEG embedded covers. | Each appears in the now-playing frame with the correct aspect ratio. |
| M113 | Switch from a covered song to one without an embedded cover. | The previous cover clears and No embedded cover appears. |
| M114 | Skip rapidly between uncached songs, then select a cached song. | The final selected song keeps its correct cover; an older completion cannot replace it. |
| M115 | Revisit a recently cached song, including one with no artwork. | Its cached cover or missing-cover placeholder appears immediately without another artwork task. |
| M116 | Play a valid MP3 with damaged or unsupported embedded image data. | Cover unavailable appears and an artwork error is logged independently of audio playback. |
| M117 | Play more than eight distinct tracks and inspect the artwork cache with a debugger. | At most eight entries remain, including missing-cover entries; least recently used entries are evicted. |
| M118 | Change songs through Open MP3, double-click, Enter, Previous/Next and automatic advancement. | Every playback path requests the matching cover and metadata. |
| M119 | Import a folder while playing a song with artwork, then navigate and search. | Artwork, background metadata, folder controls and the captured playback queue continue working. |
| M120 | Close while an artwork read or waiting request exists. | The application closes without a lingering worker preventing exit or a late cover update. |

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


The artwork checkpoint can use `feat: display embedded album artwork` when its code and documentation are committed together. If only documentation changes remain after the feature was committed, use a documentation commit such as `docs: align project documentation with current features`.

For later checkpoints, update `CHANGELOG.md` when functionality changes. Update the README for changed features, prerequisites or run instructions. Update this guide when responsibilities, workflows or verification change. Do not invent documentation changes just to fill a checklist.

## Upcoming implementation notes

- Introduce SQLite in small steps: database setup and imported paths first, then cached metadata/file-change detection, followed by preferences and playlist relationships.
- Keep database work and expensive file/image operations off the JavaFX Application Thread, applying observable-model changes on that thread.
- Preserve stable Track identity and captured playback-queue behavior while adding persistence.
- Define refresh/invalidation for edited, moved, deleted and temporarily unavailable files, including both metadata and artwork caches.
- Keep tag reading and artwork decoding out of virtualized cell updates.
- Record a Windows process-memory/CPU baseline before making optimization claims.

## Profiling

No memory/CPU baseline has been recorded for MiSPlay. Capture separate measurements for an empty window, playback, populated-library browsing, active folder/metadata work and artwork loading. Record the workload, observation interval, JDK build and tool used. Windows process memory includes more than the Java heap; do not compare unlike metrics.

## Reference documentation

- [JavaFX MediaPlayer](https://openjfx.io/javadoc/25/javafx.media/javafx/scene/media/MediaPlayer.html)
- [JavaFX Application lifecycle](https://openjfx.io/javadoc/25/javafx.graphics/javafx/application/Application.html)
- [JavaFX with Maven](https://openjfx.io/openjfx-docs/maven)
