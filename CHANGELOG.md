# Changelog

Completed changes are recorded here. Planned work belongs in the README or development notes. Entries under **Unreleased** have not been published as a tagged release.

## Unreleased

### Added

- A Maven project targeting Java 25 with JavaFX controls/media, Git ignore rules, text line-ending configuration and project documentation.
- Local MP3 selection and playback with Play/Pause controls, filename/title display and immediate/asynchronous audio error reporting.
- `AudioService` ownership of the active media player, including disposal when a track is replaced or the application closes.
- Adjustable application volume retained across track changes during the current session.
- Elapsed time and duration display, an unavailable-duration placeholder, and mouse/keyboard seeking while playing, paused or after completion.
- A session-only library with batch MP3 import and duplicate prevention based on normalized absolute paths.
- A resizable, virtualized song list with reusable fixed-height cells and playback through double-click or Enter.
- Playback queues captured from visible library results and preserved across searches and imports; Open MP3 creates a single-song queue.
- Manual Previous/Next navigation and automatic queue advancement, preserving the current volume.
- Repeat Off, Repeat All and Repeat One, including queue wrapping and manual skipping while repeating one song.
- Session-only shuffle with consistent Previous/Next navigation, Repeat All support and restoration of the captured order when disabled.
- Jaudiotagger 3.0.1 integration for title, first stored artist value, album and embedded artwork reading.
- An observable `Track` model with metadata and PENDING, LOADING, LOADED or FAILED status, plus an immutable `TrackMetadata` record.
- Automatic background metadata loading for imported tracks using one reader, priority for the selected song and continued loading after unreadable files.
- Filename and Unknown artist/album fallbacks, plus session metadata caching across queue changes and duplicate imports.
- Library rows displaying cached titles and artists, with binding cleanup when cells are reused.
- Case-insensitive substring search across filename, title, artist and album, whitespace trimming, a no-results message and automatic filtering when metadata changes.
- Folder importing through Add Folder, including subfolders, case-insensitive MP3 extensions, background file counts and cancellation.
- Folder-import summaries reporting newly added tracks and skipped unreadable entries; cancelled scans add no partial results.
- Embedded now-playing album artwork loaded on a background worker, with an eight-entry least recently used cache of resized pixel-only thumbnails.
- Missing/unreadable-cover placeholders and selection checks preventing older artwork results from replacing the current song's cover.
- Shutdown handling for the audio player, label bindings, background metadata reads, folder scans and artwork work.

### Changed

- Metadata scheduling is owned by `LibraryService`. Changing songs prioritizes a waiting read without cancelling the active read; now-playing labels observe only the selected track.
- Library rows and search use cached metadata rather than filename-only display/matching.

### Verification

- Maven validation and JavaFX window launch were confirmed on Windows 11.
- The developer reported working playback/library metadata, folder importing and embedded artwork checkpoints. Detailed per-check Windows results have not been recorded.
- Development-time headless artwork checks covered JPEG/PNG resizing, Unicode filenames, cache reuse/eviction, missing/damaged covers, invalid-file recovery, rapid selection changes and shutdown. These checks do not establish a full Windows playback regression result.
- No committed automated project test suite or memory/CPU profiling baseline has been recorded.
