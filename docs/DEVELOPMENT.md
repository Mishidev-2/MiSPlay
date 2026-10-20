# Development guide

## Scope and approach

MiSPlay is a personal Windows desktop project developed in small, working increments. Each increment should have a clear behavior to verify. Keep the architecture understandable and add dependencies when a feature needs them.

The current checkpoint is local MP3 playback. Library scanning, metadata services, persistence, lyrics and tray behavior are future increments.

## Current architecture

| Class | Owns | Does not own |
| --- | --- | --- |
| `MiSPlayApp` | JavaFX controls, the file chooser, displayed messages and the `AudioService` instance | Media decoding and audio resource management |
| `AudioService` | The current `MediaPlayer`, playback requests, error handling and disposal | Windows, buttons, labels or library rendering |
| `LibraryService` | Owns the session-only song collection, exposes a read-only observable view and prevents duplicate file paths during import. |
| `PlaybackQueue` | Stores original and active queue orders, tracks the current position, and applies navigation, repeat and shuffle rules. |
| `RepeatMode` | Defines the available repeat choices: OFF, ALL and ONE. |
| `MetadataService` | Reads title, artist and album tags and supplies fallbacks for missing fields. |
| `TrackMetadata` | Immutable record containing the title, artist and album strings used for display. |
| `LibraryService` | Maintains an observable Track list, observes metadata changes and provides normalized-path lookup for deduplication and caching. |
| `Track` | Connects a file to observable metadata, records loading state and matches normalized searches against filename and metadata fields. |
### Opening a track

1. The user chooses an MP3 in the file chooser.
2. `MiSPlayApp` updates the filename, clears the old error and calls `loadAndPlay(file)`.
3. `AudioService` disposes of the previous player, creates `Media` from the file URI and creates a replacement `MediaPlayer`.
4. Error callbacks and end-of-media behavior are attached to the replacement player. The player receives the volume stored in `AudioService`, which defaults to 0.5 (50%) when the application starts.
5. Playback is requested. JavaFX completes media preparation asynchronously.

Play and Pause buttons delegate to service methods. They do not maintain a separate `isPlaying` flag. At the natural end of a track, the current callback stops the player so the file can be played again from the beginning.

### Error reporting

The UI passes a `Consumer<String>` to `AudioService`. The service calls this callback with an error message; the UI displays it and disables the playback buttons. Selecting another file enables another playback attempt.

Synchronous construction failures are caught by `loadAndPlay`. Later media/player errors are handled by callbacks. `handleError` checks which player reported the error so an obsolete player cannot clear its replacement.

### Resource ownership

- At most one active player is held by `AudioService`.
- Replacing a track disposes of the old player first.
- Disposal clears the service's reference before releasing the old player.
- `Application.stop()` delegates to the service's disposal method during normal shutdown.
- Use file URIs for `Media`; do not construct media locations by manually concatenating Windows path separators.

### Volume control

`AudioService` stores the current volume as a number from 0.0 to 1.0. `setVolume()` keeps the value within that range and updates the active player when one exists.

`MiSPlayApp` displays a slider from 0 to 100. Its value-change listener divides the percentage by 100 before passing it to `AudioService.setVolume()`.

Every replacement player receives the stored volume. Volume can also be adjusted before opening a track.

The setting lasts for the current application session. Each application launch starts at 50%.

### Playback queue

`PlaybackQueue` is a plain Java class containing the captured original order, the active playback order, a current index, a repeat mode and a shuffle flag.

Activating a row passes its Track to `startQueue`. That action converts the current filtered entries into a `List<File>` and passes it, together with the selected file, to `PlaybackQueue.start`.

The queue captures that file order using `List.copyOf`. Later metadata changes, searches and imports leave the active queue intact. Activating another row creates a new queue. Open MP3 creates a single-song queue.

The shared `playFile` action starts a file and updates the playback display, visible selection and navigation buttons. Manual Previous and Next start the selected track even when playback was paused.

Repeat defaults to OFF:

| Mode | Natural completion | Manual Previous and Next |
| --- | --- | --- |
| OFF | Advance to the next track; remain silent after the final track. | Stop at the queue boundaries. |
| ALL | Advance and wrap from the final track to the first. | Wrap around a queue containing multiple tracks. |
| ONE | Replay the current track. | Move normally, stopping at the queue boundaries. |

Previous and Next are disabled for empty and single-song queues in every mode. A single song can still repeat automatically in ALL or ONE.

Changing the repeat mode updates the controls without starting, pausing or restarting playback.

Starting another queue preserves the chosen repeat mode. The choice is currently session-only and resets to OFF when the application restarts.

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

`LibraryService.getTracks()` exposes an unmodifiable `ObservableList<Track>`. A normalized-path map preserves one Track object per imported file.

The source list uses an extractor that observes each track's `metadataProperty()`. Metadata changes therefore produce list-update events.

A `ListView<Track>` displays filtered entries using reusable cells with a fixed height of 32 pixels.

Each populated cell binds its text to the track's metadata and displays title — artist. Before metadata loads, this uses filename and Unknown artist fallbacks.

When a cell is reused, `updateItem` unbinds its text, disposes its previous binding and clears empty-cell content before connecting to another track. Rendering uses in-memory values.

Double-clicking a populated row or pressing Enter on a selected row invokes `startQueue` with its Track.

Open MP3 imports the selected file and starts a single-song queue. Add MP3s imports entries without changing playback or the active queue.

### Library search

The search-field listener strips surrounding whitespace and converts the query to lowercase using `Locale.ROOT`.

`Track.matchesSearch` checks the filename and cached title, artist and album using the same lowercase convention. Matching uses substring searches. Empty and whitespace-only queries include all tracks.

The library's extractor reports metadata changes to the FilteredList. A track can therefore enter or leave the current results without the user editing the query again.

For example, a track matching Unknown artist can leave those results after its embedded artist loads. An artist search can gain a matching entry when its metadata becomes available.

Filtering uses in-memory values. Metadata currently loads during playback, so unplayed tracks cannot match embedded fields that have not been read yet.

Changes to search results do not modify the captured playback queue or interrupt playback.  

### Playback queue

`PlaybackQueue` is a plain Java model containing a list of files and a current index. Before a queue starts, the index is `-1`.

Starting playback from a song-list row or Enter captures the currently visible files using `List.copyOf()`. The chosen file determines the starting position within that snapshot.

Search changes and library imports preserve the active queue. Starting a row again captures a fresh snapshot. Open MP3 creates a queue containing only the selected file.

The `startQueue` action establishes the queue and delegates playback to `playFile`. Previous/Next move within the existing queue and call `playFile` without rebuilding it.

Navigation starts the adjacent song, including when the previous song was paused. Volume carries over through `AudioService`.

Previous is disabled at the first entry, Next at the last entry, and both before playback or for a single-song queue. Navigation does not wrap.

When visible, the playing file is selected and scrolled into view.

Natural track completion advances to the next entry in the captured queue. After the final entry, playback returns to zero and remains silent. The queue position stays at that final entry, allowing replay or Previous navigation.

### Now-playing metadata

`MetadataService` uses Jaudiotagger 3.0.1 to read an MP3's tags. It retrieves the first stored value for TITLE, ARTIST and ALBUM.

Missing or blank titles fall back to the filename. Missing artists and albums use Unknown artist and Unknown album.

The service returns a `TrackMetadata` record containing strings. Parser objects and embedded artwork are not retained by the application.

`LibraryService` automatically schedules metadata reads when new tracks are imported. Library rows and search results update as reads finish, without requiring playback.

The shared `playFile` action calls `loadMetadata`, covering Open MP3, row activation, manual navigation and automatic queue advancement.

`loadMetadata` releases the previous label bindings and binds the now-playing labels to the selected Track's metadata and loading status. Cached metadata appears immediately. Tracks awaiting metadata display their filename fallback and a waiting or reading message.

Selecting a track also asks `LibraryService` to prioritize its pending metadata read. The current read finishes first.

Completed reads update their own Track objects. The now-playing labels observe only the selected Track, so another track's result cannot replace the current song's details.

Read failures retain the filename fallback, show a metadata-unavailable message for the selected track and log the exception independently of audio errors.

Known issue: Yaezakura displays a shortened artist/details line even though Windows Properties lists artist and album information. The cause remains undiagnosed and investigation is deferred.

### Session metadata cache

`LibraryService` maintains a map from normalized file paths to Track objects. Reimporting a known path preserves its existing model and does not schedule another metadata read.

A new Track contains filename and Unknown artist/album fallbacks. Its observable metadata status starts as PENDING.

Metadata status progresses through PENDING, LOADING and either LOADED or FAILED. `isMetadataLoaded()` returns true only when the status is LOADED.

A successful read calls `Track.setMetadata` on the JavaFX Application Thread. This marks the metadata as loaded, stores the result and notifies property listeners. Successful reads with missing tags also cache their fallback values.

Switching songs does not cancel metadata reads. A completed read remains useful because its result is cached on the corresponding Track, even if that track is no longer playing.

A failed read marks the Track as FAILED and allows loading to continue with the next waiting track. Failed reads are not automatically retried during the current launch.

The cache contains metadata strings and follows the lifetime of the current library session. It does not persist across launches or detect file edits yet.

### Threading

JavaFX controls, observable library collections, Track properties and playback queue state are updated on the JavaFX Application Thread.

`LibraryService` owns the metadata reader, a single background executor, a deque of waiting tracks and the active metadata task. Scheduling and changes to the waiting deque happen on the JavaFX Application Thread.

Only one metadata read runs at a time. Each `Task<TrackMetadata>` reads its captured file in `call()` and returns data without accessing UI controls.

Success and failure handlers run on the JavaFX Application Thread. They update the corresponding Track and schedule the next waiting read. Label bindings, library cells and search filtering respond to the model changes.

Prioritizing a track moves it to the front of the waiting deque. It does not interrupt the active read.

Result handlers check that the library is still open and that their task is still the active metadata task.

Application shutdown disposes the now-playing bindings and calls `LibraryService.close()`. Closing clears waiting metadata work, clears the active task reference, requests cancellation of the active task and shuts down the metadata executor. The application also disposes the audio player.

## Manual checks

Use MP3 files outside the Git repository. A small set of known working files is sufficient. Record actual results; these rows are a checklist, not a claim that every case has passed.

Unless a check specifies otherwise, start it with Repeat OFF and Shuffle OFF.

| ID | Action | Expected result |
| --- | --- | --- |
| M01 | Launch with `javafx:run` | MiSPlay opens; Play and Pause start disabled |
| M02 | Open a known working MP3 | Filename is displayed and audio starts at the current volume setting, which defaults to 50% |
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
| M28 | Add several MP3s | Filenames appear without changing playback |
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
| M40 | Import files while searching | Matching files appear immediately; other files appear after clearing the search |
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
| M80 | Play A, wait for metadata, play B, wait, then return to A. | A and B are read once each; returning to A reuses cached metadata. |
| M81 | Open an already cached file again using Open MP3. | Its existing model and metadata are preserved without creating a duplicate library entry. |
| M82 | Let a cached song finish with Repeat ONE enabled. | Audio restarts without another metadata read. |
| M83 | Quickly switch from an uncached song to a cached song. | The cached song's details remain displayed; an older result cannot overwrite them. |
| M84 | Start another queue containing previously cached tracks. | Cached metadata remains available across queue changes. |
| M85 | Exercise navigation, filename search, volume, seeking, repeat and shuffle. | Existing playback and library behavior continues working. |
| M86 | Restart the application, reimport a previously cached file and play it. | Metadata is read again in the new session. |
| M87 | Import unplayed tracks in a fresh session. | Rows initially display filename — Unknown artist. |
| M88 | Play a tagged song. | Its row updates to the embedded title and artist. |
| M89 | Search a cached track by filename, title, artist and album. | Each field matches; case differences, surrounding whitespace and clearing the query work. |
| M90 | Keep an artist query active and open an uncached song by that artist. | Its row appears after metadata loads without editing the query. |
| M91 | Search Unknown artist and activate an uncached song with an embedded artist. | Its row leaves the results after loading; playback continues. |
| M92 | Double-click and press Enter on filtered rows. | The correct files play. |
| M93 | Start a multi-song queue, then change search results or import files. | Navigation and automatic advancement retain the captured file order. |
| M94 | Scroll and resize repeatedly. | Reused cells show the correct content and empty cells contain no leftover text. |
| M95 | Exercise shuffle, repeat, volume and seeking. | Existing playback behavior continues working. |

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


The first commit suggestion is:

```text
feat: initialize MiSPlay with local MP3 playback
```

For later checkpoints, update `CHANGELOG.md` when functionality changes. Update the README for changed features, prerequisites or run instructions. Update this guide when responsibilities, workflows or verification change. Do not invent documentation changes just to fill a checklist.

## Upcoming implementation notes

- Attach callbacks to every replacement player. Store service-level callback intentions if consumers register before any player exists.
- Give tracks stable identity before implementing persistent library/playlist relationships.
- Keep scanning and artwork decoding out of virtualized cell updates.
- Bound artwork caches and define how missing artwork and modified files are handled.

## Profiling

No memory/CPU baseline has been recorded for MiSPlay. Capture separate measurements for an empty window, playback, later library browsing and background operation. Record the workload, observation interval, JDK build and tool used. Windows process memory includes more than the Java heap; do not compare unlike metrics.

## Reference documentation

- [JavaFX MediaPlayer](https://openjfx.io/javadoc/25/javafx.media/javafx/scene/media/MediaPlayer.html)
- [JavaFX Application lifecycle](https://openjfx.io/javadoc/25/javafx.graphics/javafx/application/Application.html)
- [JavaFX with Maven](https://openjfx.io/openjfx-docs/maven)
