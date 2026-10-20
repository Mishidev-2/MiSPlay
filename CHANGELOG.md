# Changelog

Completed changes are recorded here. Planned work belongs in the README or development notes. Entries under **Unreleased** have not been published as a tagged release.

## Unreleased

### Added

- A fresh MiSPlay Maven project targeting Java 25 with JavaFX controls and media support.
- A JavaFX window with MP3 file selection and Play/Pause controls.
- An `AudioService` that owns the active media player and releases it when a track is replaced or the application closes.
- Filename display and error reporting for immediate and asynchronous playback failures.
- Initial README, architecture notes, manual-check instructions and Git setup documentation.
- Git ignore rules and text line-ending configuration.
- Adjustable application volume, retained across track changes during the current session.
- Elapsed playback time and track duration display, including an unavailable-duration placeholder.
- Playback seek slider with mouse and keyboard controls.
- Seeking while paused and after track completion, with immediate position display updates.
- Session-only song library with batch MP3 import and duplicate-path prevention.
- Resizable, virtualized song list with filename display and playback through double-click or Enter.
- Live filename search with case-insensitive matching, whitespace trimming and a no-results message.
- Manual playback queue navigation with Previous/Next controls and disabled buttons at queue boundaries.
- Queues captured from visible song-list results, preserved across searches and imports.
- Single-song queues when opening an MP3 directly.
- Automatic advancement through the captured playback queue, preserving volume between tracks.
- Repeat Off, Repeat All and Repeat One, including queue wrapping and manual skipping while repeating one song.
- Session-only shuffle playback, preserving the current song when toggled.
- Stable navigation through shuffled queues, including Repeat All support and restoration of the captured order when shuffle is disabled.
- Jaudiotagger integration for reading now-playing title, artist and album metadata.
- Background metadata loading with cancellation requests and protection against stale results.
- Fallback text for missing tags and metadata read failures.
- Track model containing a file, observable metadata and a metadata-loaded flag.
- Session metadata caching that survives queue changes and duplicate imports.
- Protection against stale metadata results when switching to an already cached track.
- Library rows displaying cached song titles and artists.
- Search across filenames, titles, artists and albums, including automatic filtering when metadata changes.
- Binding cleanup when virtualized cells are reused.

### Verification

- Maven validation and JavaFX window launch were confirmed on Windows 11.
- Basic MP3 playback was confirmed by the developer.
- Detailed manual-check results and memory/CPU profiling have not yet been recorded.
