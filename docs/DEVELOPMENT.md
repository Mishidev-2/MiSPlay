# Development guide

## Scope and approach

MiSPlay is a personal Windows desktop project developed in small, working increments. Each increment should have a clear behavior to verify. Keep the architecture understandable and add dependencies when a feature needs them.

The current checkpoint is local MP3 playback. Library scanning, metadata services, persistence, lyrics and tray behavior are future increments.

## Current architecture

| Class | Owns | Does not own |
| --- | --- | --- |
| `MiSPlayApp` | JavaFX controls, the file chooser, displayed messages and the `AudioService` instance | Media decoding and audio resource management |
| `AudioService` | The current `MediaPlayer`, playback requests, error handling and disposal | Windows, buttons, labels or library rendering |

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

### Playback time

`AudioService` exposes read-only `currentTimeProperty()` and `durationProperty()` values. These follow the active player's playback position and the source media's duration.

`MiSPlayApp` binds its time label to those properties. Updates come from JavaFX property notifications without a polling timer.

The `formatTime()` helper displays minutes and seconds, adding hours for longer recordings. Unknown, indefinite or invalid values display as `--:--`.

When replacing or disposing of a player, the service disconnects both bindings and resets elapsed time to zero and duration to unknown before releasing the player.


### Threading

Current service calls originate from JavaFX event handlers. Keep UI updates on the JavaFX Application Thread. JavaFX handles asynchronous media preparation; a custom audio playback thread is not needed for this implementation.

When scanning or metadata reading is added, perform filesystem traversal and expensive tag/image work away from the UI thread. Apply the resulting UI collection changes on the JavaFX Application Thread. Choose the simplest background-task mechanism that meets that requirement.

## Manual checks

Use MP3 files outside the Git repository. A small set of known working files is sufficient. Record actual results; these rows are a checklist, not a claim that every case has passed.

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
| M21 | Let a track finish | Elapsed time returns to zero and the track duration remains displayed |
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

- Prevent automatic seek-slider updates from fighting user input.
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
