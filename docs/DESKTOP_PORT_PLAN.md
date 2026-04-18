# IT Connect — Windows Desktop Port Plan

Goal: standalone Windows `.exe` client of the IT Connect system with a
drag-and-drop Windows-Control canvas, a shared PocketBase backend (same users /
permissions / devices / plans / schedules as the Android app), reusing the
Android app's Kotlin logic where possible, looking and behaving like a native
desktop application (Skia-rendered Compose, not a WebView).

**Deviation from original PDF plan (recorded 2026-04-18):**
- Source set is `jvmMain` (wizard-generated), not `desktopMain` as the PDF
  prescribes. All path references below follow the real tree
  (`composeApp/src/jvmMain/kotlin/com/itconnect/desktop/…`).

## 0. Phase Tracker — single source of truth

Legend: ⬜ Not started · 🟡 In progress · 🔴 Blocked · ✅ Done

| #  | Phase                          | Status | Owner | Started    | Completed | Artifact / Commit | Notes |
|----|--------------------------------|--------|-------|------------|-----------|-------------------|-------|
| 3  | Project structure              | ✅     | claude | 2026-04-18 | 2026-04-18 | (initial commit) | jvmMain package tree under com/itconnect/desktop/ (12 packages) |
| 4  | Add dependencies               | ✅     | claude | 2026-04-18 | 2026-04-18 | (initial commit) | OkHttp, Gson, sqlite-jdbc, JNA, slf4j — see §16 for Room→JDBC deviation |
| 5  | Port data layer                | ✅     | claude | 2026-04-18 | 2026-04-18 | (initial commit) | Models + PcControlDatabase JDBC wrapper + 4 DAOs + Repository. DB boots, WAL works, migrations run |
| 6  | Port network layer             | ✅     | claude | 2026-04-18 | 2026-04-18 | (session 2 commit) | PcControlSettings + PrivateNetworkInterceptor + WakeOnLan + PcLanScanner + PcControlApiClient/Browse/Input + LiveStreamGate + PcThumbnailFetcher. Android→JVM swaps: Build.MODEL → hostname; android.util.Log → SLF4J; android.util.Base64 → java.util.Base64; SystemClock.elapsedRealtime → System.nanoTime/1e6; WifiManager DHCP broadcast → NetworkInterface enumeration |
| 7  | Port UI screens                | ⬜     | —     | —          | —         | —                 | Compose screens; haptic removed; bitmap decoder → Skia |
| 8  | Drag-and-drop canvas           | ⬜     | —     | —          | —         | —                 | left palette / right canvas; execute + schedule zones |
| 9  | Database future-proofing       | ⬜     | —     | —          | —         | —                 | sync-schema script + schemaVersion guard |
| 10 | Scheduler                      | ✅     | claude | 2026-04-18 | 2026-04-18 | (session 3 commit) | PcScheduler singleton; 60 s daemon tick; dispatch body mirrors Android PcScheduleWorker (WOL / SHUTDOWN / SLEEP / LOCK / EXECUTE_PLAN). Wired in main.kt; logs "scheduler starting — 60 s tick" on boot |
| 11 | Desktop adapters               | ⬜     | —     | —          | —         | —                 | tray, Windows Hello / PIN, autostart registry |
| 12 | PocketBase sync                | ⬜     | —     | —          | —         | —                 | shared users/permissions/devices/plans/schedules; realtime |
| 13 | Package `.exe`                 | 🟡     | claude | 2026-04-18 | —         | (initial commit) | Gradle config wired (TargetFormat.Exe + windows{} block); `./gradlew packageExe` available. Not yet run; no icon.ico |
| 14 | Commit + iterate               | 🟡     | claude | 2026-04-18 | —         | (initial commit) | git init + initial commit done locally. GitHub repo creation deferred — `gh` CLI not installed |

## 3. Project structure

Under `composeApp/src/jvmMain/kotlin/com/itconnect/desktop/`:

```
com/itconnect/desktop/
├── app/              # main.kt, tray, autostart, Windows Hello
├── network/          # OkHttp clients
├── data/             # Room entities + DAOs
├── screens/          # Compose screens
│   ├── devices/
│   ├── plans/
│   ├── touchpad/
│   ├── keyboard/
│   ├── filebrowser/
│   └── windowscontrol/  # drag-and-drop canvas
├── viewmodel/        # business logic
├── scheduler/        # ScheduledExecutorService-based scheduler
├── pocketbase/       # PocketBase sync layer
└── dragdrop/         # drag-and-drop infrastructure
```

## 4–14

See original PDF at `C:\Users\LBS\Music\docs\DESKTOP_PORT_PLAN.pdf` for full
body of each phase. This markdown tracker is the authoritative status; the PDF
remains the spec for what each phase means.

## 15. Agent maintenance protocol

Every agent picking up work MUST update Section 0 before ending its turn.

1. Flip Status to 🟡 when you start, fill `Started`.
2. Flip Status to ✅ when done, fill `Completed` + commit SHA.
3. Flip Status to 🔴 if blocked; append a paragraph in Section 16.
4. Append a dated entry to Section 18 before your turn ends.

## 16. Blockers (active) / Resolved deviations

### Phase 4 — Room requires KSP; no KSP release for Kotlin 2.3.20

**Flagged:** 2026-04-18 by claude
**Attempting:** add Room 2.7.1 + KSP per PDF plan §4.
**Blocker:** the Compose-MP wizard pinned Kotlin 2.3.20; KSP's latest
published artifact (per Maven Central index) is `2.2.0-2.0.2` — no 2.3.x-series
KSP is available. Room cannot compile without KSP on JVM KMP targets.
**Resolution:** **deviation approved** — drop Room entirely, switch to
`org.xerial:sqlite-jdbc:3.46.1.3`. The on-disk `.db` file format is
unchanged (SQLite-is-SQLite), so the Android app's plans/devices/schedules
continue to open on desktop. Trade-off: DAOs are hand-written JDBC wrappers
instead of Room-generated. Migrations run via `user_version` pragma + raw
ALTER TABLE statements that mirror the Android `MIGRATION_3_4` … `7_8`
objects verbatim.
**Revisit:** when KSP ships a 2.3.x-compatible release, re-evaluate whether
the JDBC layer is worth replacing with Room. Non-urgent.

## 17. Deferred / follow-up

- [nice-to-have] Code-sign the `.exe` (suppresses SmartScreen warning).
- [nice-to-have] Auto-updater via PocketBase `latest_version` field.
- [nice-to-have] Windows-11-styled Compose theme (Mica, WinUI buttons).
- [nice-to-have] `SaveAgentDialog` LAN-scan flow should expose
  cert-fingerprint field.

## 18. Session log

### 2026-04-18 — claude (session 3)
- Touched phases: 10 ✅
- Current state: `PcScheduler` object with single daemon thread ticks
  every 60 s through `PcControlRepository.dueSchedulesNow()`, dispatching
  WOL / system-commands / EXECUTE_PLAN per the Android worker's body.
  Wired into `main.kt` right after DB init. Verified via a brief run —
  scheduler start line appears in logs; DB reopens at user_version=8 on
  subsequent boot (migrations idempotent).
- Loose ends:
  - `Dispatchers` / `.launch` imports removed from main.kt could not be
    removed yet — still needed for the `seedIfEmpty` launch path.
  - Scheduler does not persist "last tick" across restarts; each launch
    evaluates `dueSchedulesNow` immediately (intentional — catch-up logic
    lives in the repository).

### 2026-04-18 — claude (session 2)
- Touched phases: 6 ✅
- Current state: network layer fully ported. 7 new files under
  `com/itconnect/desktop/network/` (PcControlSettings, PrivateNetworkInterceptor,
  WakeOnLan, PcLanScanner, PcControlApiClient, PcThumbnailFetcher). Compiles
  clean; not yet integration-tested against a live agent.
- Loose ends:
  - Added `org.json:json:20240303` to deps (Android bundles it via SDK;
    JVM needs explicit dep — `fetchScreenSnapshot`, `fetchScreenInfo`, and
    `PcLanScanner` all rely on `org.json.JSONObject`).
  - Phase 7 (UI screens) is the next natural step but pulls in Hilt /
    AuthRepository — needs a planning pass before code lands.

### 2026-04-18 — claude (port kickoff, session 1)
- Touched phases: 3 ✅, 4 ✅, 5 ✅, 13 🟡, 14 🟡
- Current state:
  - Skeleton compiles under jvmMain with OkHttp / Gson / sqlite-jdbc / JNA / slf4j deps.
  - Data layer fully ported: `PcControlModels.kt` (entities as plain data classes),
    `PcControlDatabase.kt` (JDBC wrapper + migrations v3→v8 + 4 DAOs + Repository).
  - App boots via `./gradlew :composeApp:run`: opens
    `%APPDATA%/ITConnect/pc_control.db`, applies schema, renders placeholder
    navigation rail with real plans list (seeded via `seedIfEmpty`).
  - `.exe` packaging config wired (TargetFormat.Exe, Windows installer
    block with fixed `upgradeUuid`). Not yet packaged.
  - git initialized; first commit landed (see commit log).
- Loose ends:
  - **Room→JDBC deviation** logged in §16. Revisit when KSP 2.3.x ships.
  - **GitHub repo not yet created.** `gh` CLI missing on the box. Next agent:
    either install `gh` (`winget install GitHub.cli`) or create
    `IT-Connect-Desktop` via web UI and `git remote add origin` + push.
  - **Phase 6** (network layer) not started. Next natural step.
  - **Phases 7, 8, 10, 11, 12** (UI screens, drag-drop, scheduler, desktop
    adapters, PocketBase sync) untouched — each is substantial and needs
    its own session. See full PDF for specs; heaviest lift is Phase 12
    (requires Hilt removal in the Android pieces being ported).
  - Icon file (`composeApp/src/jvmMain/resources/icon.ico`) still needed
    before `packageExe` produces a branded installer.
