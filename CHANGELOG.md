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
- Shuffle with consistent Previous/Next navigation, Repeat All support and restoration of the captured order when disabled; queue order lasts for the current session.
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
- Schema 3 migration adding a singleton playback-preferences table while retaining existing paths, IDs, order and valid metadata caches.
- An immutable `PlaybackPreferences` record for volume, repeat mode and the shuffle switch; invalid caller values are rejected and malformed saved values fall back per field.
- Startup library/settings snapshots loaded in one read transaction, with preference controls gated until restored choices are available and no automatic playback.
- Delayed preference saves after about 250 ms of control inactivity, startup guards preventing default overwrites, and immediate capture of the latest controls on normal close.
- Transactional preference/path/cache batches using the existing Retry save action and newest-snapshot preservation after failures.
- Named manual playlists with create/rename/delete dialogs, empty-playlist persistence, stable IDs and saved creation/song order.
- An immutable `Playlist` record containing a validated name and copied, normalized, unique library paths; service checks for case-insensitive name conflicts and library Track ownership.
- A View dropdown for All songs or a named playlist, scoped metadata search, Ctrl/Shift multi-selection and adding selected songs to a chosen playlist without duplicates.
- Remove from playlist and single-song Move up/Move down controls; reordering requires an empty search and one selection, and membership edits retain library rows and music files.
- Schema 4 migration adding playlist/membership tables with library-track references and foreign-key enforcement while preserving paths, IDs, caches and playback preferences from earlier schemas.
- Startup playlist restoration and transactional playlist snapshots through the existing background save/retry worker, including newer-state preservation and empty snapshots after deleting the final playlist.
- Favorite/Unfavorite actions for one or several selected songs, with star markers and mixed-selection handling that skips unchanged memberships.
- A built-in Favorites view with library insertion order, scoped/live metadata search and playback from its visible results, using the existing library Track objects.
- Independent favorite membership that survives manual-playlist edits/deletion and can be added to manual playlists without duplicating library tracks or deleting music files.
- Schema 5 migration adding `favorite_tracks` references while retaining existing paths, IDs/order, metadata caches, preferences and manual playlists from schemas 0–4.
- An immutable favorite set exposed through a read-only service property, canonical Track validation and one snapshot notification per changed bulk edit.
- Transactional favorite snapshots through the existing save/retry worker, including latest-empty retry, immediate-close flushing and temporary session choices after failed startup.
- Same-basename UTF-8 sidecar LRC loading on all playback paths, with a Lyrics tab and Reload lyrics for files added or edited during playback.
- An immutable `LyricLine` record and pure `LrcParser` supporting whole/fractional timestamps, repeated tags, simultaneous translations, timed blank entries and global signed millisecond offsets.
- A background `LyricsService` with one active read and one latest waiting request, bounded input, distinct empty/error states and guards against obsolete or post-close results.
- A virtualized `LyricsPane` with wrapped rows, clock-driven current-line highlighting, automatic following after line changes/resize and click/Enter seeking to adjusted times when duration permits.
- Lyric recovery guidance for missing/untimed files, invalid UTF-8, unreadable sidecars and exceeded file/line/cue limits; lyric failures leave audio playback available.
- Shutdown handling for the audio player, label bindings, background metadata reads, folder scans and artwork work, plus draining accepted writes and a final path/cache/preferences/playlists/favorites save on normal close.

### Changed

- Metadata scheduling is owned by `LibraryService`. Changing songs prioritizes a waiting read without cancelling the active read; now-playing labels observe only the selected track.
- Library rows and search use cached metadata rather than filename-only display/matching.
- Library membership/order, favorite membership, playlist names/membership/order, valid text metadata and volume/repeat/shuffle choices survive restart. Active queues, selected views/tracks, playback positions, shuffled orders and artwork caches remain session-only; restored tracks validate cached tags before reusing them.
- Reimporting or requesting playback of a loaded/failed Track now schedules a deduplicated metadata check while preserving Track identity. Unchanged files reuse tags; edited/repaired files receive fresh reads.
- Row activation captures the current filtered library or manually ordered playlist results. View changes, playlist reordering, membership edits and playlist deletion leave the active captured queue intact.
- The playlist selector keeps the same playlist ID across renamed/edited snapshots; deleting the active playlist returns the view to All songs. Each launch begins in All songs without autoplay.
- The window accommodates playlist controls and retains readable action buttons when a long playlist name is truncated in the dropdown.
- View choices now distinguish built-in All songs/Favorites from Playlist: name, preserving a pre-existing manual playlist named Favorites with its own data and order.
- Favorite edits preserve the captured playback queue; built-in Favorites disables manual rename/delete/reorder controls. Startup restores favorite stars without autoplay or default-membership writes.

- Library controls now live in a Library tab beside Lyrics; now-playing artwork, metadata and transport controls remain shared below the tabs. Lyrics are session/file based; SQLite stays at schema 5.
- Shutdown now detaches lyric listeners, stops the one-shot follow delay, cancels the active lyric read and drops waiting work.

### Verification

- Maven validation and JavaFX window launch were confirmed on Windows 11.
- The developer reported working playback/library metadata, folder importing, embedded artwork and SQLite library-path checkpoints. Detailed per-check Windows results have not been recorded.
- The current application was reported working after the metadata-cache handoff; individual cache/migration scenarios have not been recorded as Windows passes.
- The developer reported the playback-preferences checkpoint working. Detailed results for each Windows scenario have not been recorded.
- The developer accepted the manual-playlists checkpoint after the handoff. Individual Windows checklist results have not been recorded as passes.
- The developer accepted the Favorites checkpoint. Detailed per-check Windows results have not been recorded.
- Development-time headless artwork checks covered JPEG/PNG resizing, Unicode filenames, cache reuse/eviction, missing/damaged covers, invalid-file recovery, rapid selection changes and shutdown. These checks do not establish a full Windows playback regression result.
- SQLite checks covered real database creation/restart, path normalization, duplicates/order, Unicode paths, transaction rollback, missing paths, schema rejection and corrupt-file preservation. Headless coordination/UI checks covered restore/import gating, queued writes/retry, folder imports/cancellation, responsive controls during a database lock, normal-close saves, process exit and no automatic playback. These used JDK 17/JavaFX 21.0.1 with substitutes for some unchanged services; they do not establish a full Java 25/JavaFX 25.0.2 Windows build or audio regression result.
- The metadata-cache checkpoint passed 30 focused checks covering migration/rollback, invalid cache fields/version, parser-call avoidance after restart, size/time invalidation including submillisecond changes, live selection/reimport refresh, missing/repaired files, bounded reads during concurrent edits, save retry, newest-result shutdown behavior, cached fallbacks and an 80-track batch. The existing 12 repository, 21 library and 7 UI checks also passed in that harness; the same environment/substitute-class limits apply.
- The preferences checkpoint passed 35 model/repository, 16 settings/cache-coordination and 19 application checks. Coverage included schema 0/1/2 migration, retained caches, all repeat/shuffle combinations, mute/full/decimal volume, invalid values, atomic rollback, startup gating, delayed combined saves, first explicit playback volume, retry, immediate close and failed-startup session use. The same Java 17/JavaFX 21.0.1 harness and substitute-class limits apply.
- The playlists checkpoint passed 26 model/repository, 23 coordination, 29 application and 2 layout checks, plus all 70 existing preferences/cache checks (150 total). Coverage included schemas 0–3 migration, retained IDs/caches/settings, Unicode names, shared/empty membership, duplicate/no-op edits, transactional rollback, scoped/live search, actual dialogs and move controls, stable selection, captured-queue wiring, newest-state retry including delete-all, immediate-close saves, unavailable media and layout with a maximum-length name at both window sizes. The same harness limits apply; unchanged audio/queue substitutes do not verify real MP3 decoding or the actual shuffle algorithm.
- The Favorites checkpoint passed 32 repository/migration, 20 service and 36 application/layout checks (88 total). Coverage included schemas 0–4 upgrades, retained data and a legacy playlist named Favorites, immutable/bulk/no-op edits, atomic rollback, mixed selection, stars, scoped/live search, captured-queue wiring, existing playlist controls, latest-state/empty retry, immediate close, missing/repaired files, failed-startup protection and default/minimum layouts. The earlier 150-check playlist suite is a separate result and was not rerun here. The same Java 17/JavaFX 21.0.1 harness limits apply; audio, queue and tag-reader substitutes do not prove production decoding, shuffle or a complete Windows regression.
- The LRC checkpoint passed 27 parser/model, 24 sidecar-service and 38 application/layout checks (89), plus the existing 32 repository, 20 library-service and 36 Favorites/application checks (177 total). The focused checks covered timestamp/offset boundaries, repeated/simultaneous lines, Unicode/BOM, input limits, missing/repaired sidecars, same-path reload, stale/close guards, clock highlighting, adjusted click/Enter seeks, unknown-duration/audio-error guards, navigation, wrapped-row following and default/minimum layouts. Production changed/new sources compiled in the Java 17/JavaFX 21.0.1 harness with substitutes for unavailable unchanged components. Complete Java 25/JavaFX 25.0.2 Windows compilation and real MP3/LRC playback remain pending locally.
- No committed automated project test suite or memory/CPU profiling baseline has been recorded.
