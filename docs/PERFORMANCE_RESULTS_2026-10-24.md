# Pre-UI Windows performance results — 2026-10-24

**Source:** User-reported output of `tools/Measure-MiSPlay.ps1` on Windows 11 with Temurin 25.0.3, using IntelliJ IDEA's bundled Maven and the actual JavaFX process (`com.misplay.MiSPlayApp`, PID 9372). The supplied aggregate figures are copied below. **Raw CSV samples are stored in the user's local `baseline/` folder and were not provided with this checkpoint.** Do not treat the averages as independently reprocessed CSV measurements.

Measurement: 30 samples per scenario, two-second intervals, 60-second capture plus five-second settling, 12 logical CPU processors. CPU percentage is share of whole machine; working set and private commit are **not** Java heap occupancy and are not interchangeable. User reported about **1,950 tracks** before importing **89**, giving approximately **2,039** later. Artwork workload cycled through **20–30 songs**. Exact playback bitrate, window size, album-art file sizes and active import duration were not recorded.

| Scenario | Average CPU | Peak CPU | Average working set (MB) | Peak working set (MB) | Average private commit (MB) | Peak private commit (MB) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Idle | 0.02% | 0.13% | 488.93 | 489.07 | 667.00 | 667.04 |
| Playback | 0.38% | 0.84% | 351.16 | 358.34 | 454.12 | 461.58 |
| Browsing | 1.90% | 4.78% | 582.69 | 712.55 | 2,455.89 | 2,877.95 |
| Import (89 songs) | 0.58% | 10.82% | 500.87 | 522.53 | 510.07 | 522.47 |
| Artwork (20–30 tracks) | 2.24% | 4.39% | 682.94 | 868.14 | 2,794.16 | 2,903.65 |

## Follow-up heap diagnostics

Two `jcmd 9372 GC.heap_info` observations after browsing reported:

- G1 heap reserved 6,221,824 KiB, committed 2,891,776 KiB, used 170,252 KiB.
- After a further idle interval: G1 reserved 6,221,824 KiB, committed 2,891,776 KiB, used 329,868 KiB; Windows working set 1,180.23 MB, private commit 3,200.03 MB.
- After a **requested garbage collection** using `jcmd ... GC.run`: committed 192,512 KiB, used 32,817 KiB, working set 446.30 MB, private commit 507.31 MB.

The large drop **after explicit GC** demonstrates reclaimable allocations and heap decommit; it does **not** prove that allocations are efficient, that automatic GC occurs promptly, or that a persistent leak is impossible. Some scenarios were sequential within one JVM session and do not constitute strictly controlled A/B comparisons. No FPS, UI latency, automatic-GC rate or heap allocation profile was captured. No RAM/CPU performance target is claimed as met.

## Post-UI comparison protocol

Repeat the same scenarios on Windows with Java 25, approximately the same library and covers, matching window size, JDK, and power settings. Record current song count and any allocation bursts, UI jank, pauses or audio glitches; consider a second run and heap/JFR profiling if high private commit recurs. Preserve raw `baseline/*.csv` as the pre-UI reference. Do not infer leaks from peak commit alone or optimize the JVM by inserting routine explicit `GC.run` calls.
