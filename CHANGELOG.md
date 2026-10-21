# Changelog

Completed changes are recorded here. Planned work belongs in the README or development notes. Entries under **Unreleased** have not been published as a tagged release.

## Unreleased

### Added

- A Maven project targeting Java 25 with JavaFX controls/media, Git ignore rules, text line-ending configuration and project documentation.
- Local MP3 selection and playback with Play/Pause controls, filename/title display and immediate/asynchronous audio error reporting.
- `AudioService` ownership of the active media player, including disposal when a track is replaced or the application closes.
- Adjustable application volume retained across track changes during the current session.
- Elapsed time and duration display, an unavailable-duration placeholder, and mouse/keyboard seeking while playing, paused or after completion.
- An observable library with batch MP3 import and duplicate prevention based on normalized absolute paths.
- A resizable, virtualized song list with reusable fixed-height cells and playback through double-click or Enter.
- Playback queues captured from visible library results and preserved across searches and imports; Open MP3 creates a single-song queue.
- Manual Previous/Next navigation and automatic queue advancement, preserving the current volume.
- Repeat Off, Repeat All and Repeat One, including queue wrapping and manual skipping while repeating one song.
- Session-only shuffle with consistent Previous/Next navigation, Repeat All support and restoration of the captured order when disabled.
- Jaudiotagger 3.0.1 integration for title, first stored artist value, album and embedded artwork reading.
- An observable `Track` model with metadata and PENDING, LOADING, LOADED or FAILED status, plus an immutable `TrackMetadata` record.
- Automatic background metadata loading for imported tracks using one reader, priority for the selected song and continued loading after unreadable files.
- Filename and Unknown artist/album fallbacks, plus metadata caching across queue changes and duplicate imports.
- Library rows displaying cached titles and artists, with binding cleanup when cells are reused.
- Case-insensitive substring search across filename, title, artist and album, whitespace trimming, a no-results message and automatic filtering when metadata changes.
- Folder importing through Add Folder, including subfolders, case-insensitive MP3 extensions, background file counts and cancellation.
- Folder-import summaries reporting newly added tracks and skipped unreadable entries; cancelled scans add no partial results.
- Embedded now-playing album artwork loaded on a background worker, with an eight-entry least recently used cache of resized pixel-only thumbnails.
- Missing/unreadable-cover placeholders and selection checks preventing older artwork results from replacing the current song's cover.
- SQLite library-path persistence through Xerial SQLite JDBC 3.53.4.0, with a runtime `slf4j-nop` 1.7.36 binding; originally introduced in schema 1.
- `LibraryRepository` with ordered path loading, parameterized transactional batch inserts and unique normalized paths.
- Background startup restoration of library membership and insertion order without automatic playback; import controls wait for restoration to finish.
- A per-user `MiSPlay/library.db` under `LOCALAPPDATA`, with a `.misplay/library.db` fallback under Java's `user.home` directory.
- Background save batches, loading/saving status, a Retry save action and preservation of pending paths after write failures.
- Temporary session use when initial database loading fails, without resetting unreadable or newer-schema databases; missing file paths remain saved.
- Schema 2 migration preserving library paths, IDs and order while adding cached title/artist/album, file size, full modification-time text and metadata-cache version 1.
- Background cache validation on startup, playback requests and reimport, skipping tag parsing for unchanged files and refreshing changed files.
- Before/after file checks around fresh tag reads, with one retry for concurrent edits and no unbounded retry loop.
- Cached successful missing-tag fallbacks, stale-cache clearing after failed checks/reads and explicit same-launch retry for repaired files.
- Transactional path/cache batches using UPDATE for cached fields, plus newest-result preservation across failed writes and normal-close flushing.
- Shutdown handling for the audio player, label bindings, background metadata reads, folder scans and artwork work, plus draining accepted database writes and a final idempotent path/cache save on normal close.

### Changed

- Metadata scheduling is owned by `LibraryService`. Changing songs prioritizes a waiting read without cancelling the active read; now-playing labels observe only the selected track.
- Library rows and search use cached metadata rather than filename-only display/matching.
- Library membership, order and valid text metadata now survive restart. Playback queues/settings and artwork caches remain session-only; restored tracks validate cached tags before reusing them.
- Reimporting or requesting playback of a loaded/failed Track now schedules a deduplicated metadata check while preserving Track identity. Unchanged files reuse tags; edited/repaired files receive fresh reads.

### Verification

- Maven validation and JavaFX window launch were confirmed on Windows 11.
- The developer reported working playback/library metadata, folder importing, embedded artwork and SQLite library-path checkpoints. Detailed per-check Windows results have not been recorded.
- The current application was reported working after the metadata-cache handoff; individual cache/migration scenarios have not been recorded as Windows passes.
- Development-time headless artwork checks covered JPEG/PNG resizing, Unicode filenames, cache reuse/eviction, missing/damaged covers, invalid-file recovery, rapid selection changes and shutdown. These checks do not establish a full Windows playback regression result.
- SQLite checks covered real database creation/restart, path normalization, duplicates/order, Unicode paths, transaction rollback, missing paths, schema rejection and corrupt-file preservation. Headless coordination/UI checks covered restore/import gating, queued writes/retry, folder imports/cancellation, responsive controls during a database lock, normal-close saves, process exit and no automatic playback. These used JDK 17/JavaFX 21.0.1 with substitutes for some unchanged services; they do not establish a full Java 25/JavaFX 25.0.2 Windows build or audio regression result.
- The metadata-cache checkpoint passed 30 focused checks covering migration/rollback, invalid cache fields/version, parser-call avoidance after restart, size/time invalidation including submillisecond changes, live selection/reimport refresh, missing/repaired files, bounded reads during concurrent edits, save retry, newest-result shutdown behavior, cached fallbacks and an 80-track batch. The existing 12 repository, 21 library and 7 UI checks also passed in that harness; the same environment/substitute-class limits apply.
- No committed automated project test suite or memory/CPU profiling baseline has been recorded.
