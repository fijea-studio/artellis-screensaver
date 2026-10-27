# CLAUDE.md

Artellis is an Android TV screensaver (DreamService) that hangs public-domain paintings from the Art Institute
of Chicago and The Met as themed walls beside a large clock.

**Helping someone install Artellis on their TV?** Follow [AGENTS.md](AGENTS.md) and [SETUP.md](SETUP.md), one
step at a time. The rest of this file is for changing the code.

## Map

| File | What it does |
|---|---|
| `ArtellisDream` | The DreamService; hands everything to `Scene`. Non-interactive: any key wakes the TV. |
| `Scene` | All views, built in code: clock panel (left quarter), walls, labels, wall gradient, night filter, song line. |
| `Feed` | Fetches and stores walls: themes, both museums, scaling, storage caps, the "seen" list. |
| `Hang` | Pure layout math: which 1-3 paintings make a wall and where they hang. Unit-tested. |
| `Palette` | Pure: wall colours by hour; warm-light window, strength, colour matrix and dimming. Unit-tested. |
| `Fit` | Pure: target size and sample size for saving a picture. Unit-tested. |
| `Theme` | The place-and-period themes and how each museum is asked for them. |
| `NowPlaying`, `MediaListener` | Song and artist from media sessions (needs the notification-listener grant). |
| `SettingsActivity` | About page and the one setting (warm light at night: on by default, start/end in 30-min steps), opened from the TV's screensaver menu. Over adb: `--ez night_filter true/false`, `--ez settings_tile true/false` (the hidden `SettingsTile` alias, for Google TV). |
| `scripts/install.ps1`, `install.sh` | Twins: build or take an APK, install over adb, grant, set as screensaver, save the previous one on the TV and in `artellis-restore-<tv>.txt`, `-Off`. |
| `SETUP.md`, `AGENTS.md` | The install guide for people and AI assistants, and the rules for assistants. Keep them in step with the scripts. |

## Commands

```sh
./gradlew testReleaseUnitTest assembleRelease     # tests + release APK
adb shell logcat -d -s Artellis                    # what the screensaver did
```

**Releases:** bump `versionCode` and `versionName` in `app/build.gradle`, commit, then push a tag `v<versionName>`.
`.github/workflows/release.yml` builds the signed APK and attaches it to a draft Release to review and publish.

On Windows the build directory is `%LOCALAPPDATA%\artellis-build` (OneDrive locks files in `app/build`).

## Rules

- **Network:** only the four museum hosts in the README. No analytics, telemetry, ads, crash reporting or
  other hosts. Never send device identifiers, location or account data.
- **Runs only while dreaming,** plus one exception: the settings page fetches a single wall for its preview
  while it is open and nothing is saved. No background services, boot receivers, alarms or WorkManager.
- **Storage:** keep the caps in `Feed` (about 60 paintings, 40 MB, no downloads under 500 MB free). Save
  pictures scaled for the screen, never the full scans.
- **No launcher tile by default;** no donation prompts on the TV (those live in the README and FUNDING.yml).
- **Text over art stays quiet:** small labels, no ellipsis on titles (they wrap), museum date wording kept
  ("c. 1665").
- **minSdk 26** stays. No new dependencies without a reason.
- **Install changes only three things** (one app, the notification-listener grant, the screensaver setting); keep the README's "What install changes" true.
- **Scripts:** keep `install.ps1` and `install.sh` doing the same things. Commands sent to the TV contain no
  double quotes (Windows PowerShell 5 drops them on the way to adb).
- **Keep the pure classes pure** (no Android imports in `Hang`, `Palette`, `Fit`) and add a test with any
  change to them.
- **Never commit** APKs, keystores, `keystore.properties`, `local.properties`, IP addresses, Wi-Fi names or
  other personal details. Screenshots in `docs/screenshots/` must not show accounts or network names.
- Verify visual changes with a screenshot from a real TV: `adb exec-out screencap -p > shot.png`.
