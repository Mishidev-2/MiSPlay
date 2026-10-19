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

### Verification

- Maven validation and JavaFX window launch were confirmed on Windows 11.
- Basic MP3 playback was confirmed by the developer.
- Detailed manual-check results and memory/CPU profiling have not yet been recorded.
