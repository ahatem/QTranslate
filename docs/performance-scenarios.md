# Runtime performance scenarios

This plan uses an extracted release candidate, an isolated writable copy of its data directory, and the same machine for each before/after comparison. Record the artifact SHA, OS, architecture, JDK, JVM arguments, CDS state, and screen scale. Never point a run at a user's live settings. Use `-XX:StartFlightRecording=settings=profile` during investigation, and keep recordings outside the repository. Report medians from at least five warm runs; record cold runs separately because the OS file cache is difficult to reset reproducibly.

| Scenario | Reproducible action | Evidence to collect |
|---|---|---|
| Cold startup | Launch a fresh process and isolated data copy after reboot, when practical | Process start, configuration, dependencies, theme/fonts, frame construction, first usable EDT idle, plugin completion; JFR class loading, file I/O and EDT stacks |
| Warm startup | Repeat the same launch five or more times | Median milestone times, process RSS and JFR allocations |
| Idle | Leave the main window settled for one minute | Heap, RSS, threads, background CPU, GC and thread parks |
| Input | Type or paste 100, 1,000 and 10,000 character Latin, Arabic and mixed script samples | EDT latency, paint/layout stacks, allocation, spell/translation cancellation; use deterministic local services |
| Output | Exercise Classic, Side By Side and Comparison with empty, normal, long, loading, success and failure states | EDT render/layout/paint events and allocation; use deterministic local services |
| Settings | Open Services & Presets, Plugins, Keyboard and Appearance, then reopen them | First and repeated open latency, EDT stacks and retained windows |
| Plugins | Launch with the same bundled plugin inventory | Discovery, manifest, classloading, initialization and publication times; exclude provider network latency |
| Theme and scale | Switch light/dark, available user theme and UI scale | EDT update time, repaint work and visual correctness |
| Documents | Process local TXT and DOCX samples | Parsing/translation phases, allocations and cancellation, with fake translation service |
| Shutdown | Close via the normal exit route | Bounds/settings persistence, store/plugin/client teardown, thread termination |

For a candidate change, compare unmodified baseline and changed artifacts using the same fixture and measurement command. Report run count, medians, variance, relative difference and correctness checks. Keep a production change only when the target improves beyond measurement noise without a material regression. Use `jfr print` or JMC to inspect CPU, allocations, class loading, monitor contention, waits, file/socket I/O, GC, compilation, exceptions, thread counts and EDT stacks. Use fixed workload scripts or existing test harnesses for interactive paths; report any scenario that could not be automated or reproduced instead of assigning a timing to it.
