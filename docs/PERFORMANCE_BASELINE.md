# MiSPlay Phase 0 — Windows verification and performance baseline

**Scope:** measurement only. No changes to playback or UI behavior. Use the real Windows 11 machine/JDK 25 where MiSPlay runs. Linux/static review is **not** equivalent to Windows MediaPlayer/native-dialog verification.

## What this baseline measures

- **WorkingSetMB:** resident working set of MiSPlay's process, including potentially shared pages (not the Java heap).
- **PrivateCommitMB:** private committed memory (not physically resident RAM and not Java heap-only).
- **CPUPercentOfMachine:** sampled CPU seconds divided by wall seconds and logical CPU count, expressed as 0–100% share of the entire machine. CPU activity may be lower during idle periods.
- **ThreadCount / HandleCount:** process resources for detecting unusual growth.

Do not compare this CPU figure directly to profilers that use per-core 100%-based CPU percentages. Do not confuse process memory with Java heap consumption. This read-only script does not collect frame rendering FPS or audio dropout counts; observe those manually.

## Verify build and environment

1. Use Windows PowerShell in the folder that contains `pom.xml`.
2. Record `java -version`, `javac -version`, and `mvn -version` (or use IntelliJ Maven's output if Maven is not on PATH). Set both Project SDK and Maven Runner to **JDK 25**.
3. Run `mvn -q -DskipTests package`, then `mvn javafx:run`; or execute Maven goals through IntelliJ. If a build fails, record the **first compilation/dependency error** rather than modifying source preemptively.
4. Check **M361–M369** in `docs/DEVELOPMENT.md` for cover refresh, followed by playback/queue/lyrics cases that match your music files. In particular verify seeking, repeat/shuffle, next-on-completion, Favorites, and manual queue actions.
5. Keep a backup of `%LOCALAPPDATA%\MiSPlay\library.db` before any experiment with imported data. The sampler never edits the database. To isolate a *fresh* test library instead of clearing your real one, start MiSPlay from a **new** PowerShell window using the optional section below.

## How to sample a running application

Open MiSPlay. With JDK 25 `jps -lv`, or Windows Task Manager → **Details** → PID / process command line, identify the application **Java process**, *not* IntelliJ or Maven. If multiple Java processes exist, inspect the Command Line column to confirm the process launches `com.misplay.MiSPlayApp`. Then run:

```powershell
& .\tools\Measure-MiSPlay.ps1 -TargetPid 12345 -Scenario Idle -DurationSeconds 60 -OutputDirectory .\baseline
```

Replace `12345` with the actual PID. Output is a timestamped CSV. If PowerShell blocks locally authored scripts, in **that PowerShell window only**, run `Set-ExecutionPolicy -Scope Process RemoteSigned` and retry. No administrator privileges should be necessary.

Keep the **same machine, display scaling, JDK, library contents, measurement duration, window size, and music samples** when comparing pre- and post-UI measurements. Prefer plugging in a laptop and keeping power mode unchanged. Use normal application operation for each scenario:

| Scenario | Setup and activity | Notes |
| --- | --- | --- |
| Idle | Launch, wait for library/metadata restoration to settle; no song playing | Ideally run with a fresh empty library **and** repeat with your populated one, labelled distinctly |
| Playback | Play an ordinary MP3, keep window visible; do not interact | Note MP3 length/bitrate and whether lyrics exist |
| Browsing | Populate library, scroll continuously for the 60-second capture | Record approximate song count and whether artwork is displayed |
| Import | Import a folder while recording; include files with varied embedded art/metadata | Record files scanned and success/failure counts |
| Artwork | Browse between tracks with covers and use Reload cover a few times | Record approximate embedded cover sizes |

For each scenario, rerun with the same PID and the matching `-Scenario` value. If the song finishes before the measurement ends, choose a longer one or note the transition. If a workload finishes partway through, state how long the active portion lasted. Record visible freezes, skips or unexpectedly slow scrolling. Capture a 5-second settling period before each measurement by default (customize `-WarmupSeconds`).

### Optional isolated empty library (no deletion)

Run these commands in a **separate PowerShell session before launching MiSPlay from that same session** (not in a pre-existing IntelliJ GUI session). This redirects the app's `LOCALAPPDATA` for that launch only:

```powershell
$env:LOCALAPPDATA = Join-Path $env:TEMP 'MiSPlay-PerfProfile'
New-Item -ItemType Directory -Force -Path $env:LOCALAPPDATA | Out-Null
mvn javafx:run
```

Only do this when the Maven command is available from that shell. Your normal user `%LOCALAPPDATA%\MiSPlay\library.db` remains untouched. **If you have previously used the same performance profile**, rename the profile directory to keep a genuinely empty test. Restart MiSPlay normally for your real library afterward.

## Baseline result worksheet

Copy the CSV outputs into a spreadsheet if needed. Record:

| Scenario | Tracks in library | AVG CPU % | PEAK CPU % | AVG working set MB | PEAK working set MB | PEAK private commit MB | User-visible issues |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Empty library idle | | | | | | | |
| Populated library idle | | | | | | | |
| Playback | | | | | | | |
| Browsing | | | | | | | |
| Import | | | | | | | |
| Artwork | | | | | | | |

Record machine CPU model, total RAM, JDK full version, display scale/window size, sample duration and whether music was on SSD/HDD. **No performance target is established before collecting real data.**

## Locked UI decisions for the next phase

- Remove ratings from all player modes, including compact.
- Favorite Album toggles favorite membership for all songs of the album; it does not duplicate the files.
- Build full UI first; compact/mini views afterward.
- Active queue and playback position remain session-only.
- Right sidebar is a permanent Now Playing region: **artwork, track metadata, then a Lyrics/Queue switch**. The lyrics and queue sections swap in the same available space. Timed lyrics remain synchronized, centered/wrapped, and scrollable; switching tabs does not replace the playing track or active queue.

## Phase 0 exit criteria

- Windows Maven compile/launch independently confirmed by the user.
- Windows audio, saved library, queue and lyrics regressions checked.
- CSVs and workload notes captured across baseline scenarios (or missing workloads explicitly recorded).
- Findings compared with the Figma and code architecture before modifying the UI.

## Recorded 2026-10-24 baseline

The user's five 60-second aggregate results and heap diagnostics are preserved in [PERFORMANCE_RESULTS_2026-10-24.md](PERFORMANCE_RESULTS_2026-10-24.md). Raw CSV samples remain on the Windows machine; the aggregates are not a substitute for the underlying per-sample files. Repeat comparable workloads after the UI shell change.
