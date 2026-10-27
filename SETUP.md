# Setting up Artellis

A step-by-step guide for anyone, and for an AI assistant helping someone (assistants: read
[AGENTS.md](AGENTS.md) first). It takes about 15 minutes. Do the steps in order; each one ends with how to
know it worked.

## 1. Check your TV

Artellis runs on **Android TV** and **Google TV**, **Android 8.0 or newer**. **Fire TV** runs Amazon's version
of Android: it is untested but may work. **Samsung (Tizen), LG (webOS) and Roku TVs can't run Artellis.**

Find the version:

| TV | Where |
|---|---|
| Android TV (Sony, Philips, TCL, Xiaomi, Sharp, many others) | Settings > Device Preferences > About > Version |
| Google TV (Chromecast with Google TV, newer Sony, TCL, Hisense) | Settings > System > About > Android TV OS |
| Fire TV | Settings > My Fire TV > About |

**It worked if** the TV says Android 8, 9, 10, 11, 12, 13 or newer (on Fire TV, any recent Fire OS).

## 2. Turn on Developer options

| TV | How |
|---|---|
| Android TV | Settings > Device Preferences > About, select **Build** seven times |
| Google TV | Settings > System > About, select **Android TV OS build** seven times |
| Fire TV | Settings > My Fire TV > About, select the **device name** seven times |

**It worked if** the TV says "You are now a developer" and a **Developer options** menu appears (Android TV:
Device Preferences; Google TV: System; Fire TV: My Fire TV).

## 3. Turn on debugging

Open **Developer options** and switch on:

- **USB debugging** (Fire TV: **ADB debugging**).
- **Network debugging** or **ADB over network**, if the TV lists it.

Some newer Google TVs show **Wireless debugging** instead, with a pairing code: open it, choose **Pair
device with pairing code**, and keep that screen open for step 6.

**It worked if** the switches stay on.

## 4. Find the TV's address

| TV | Where |
|---|---|
| Android TV, Google TV | Settings > Network & Internet > your network > **IP address** |
| Fire TV | Settings > My Fire TV > About > **Network** |

It looks like `192.168.1.50`. Keep it private: don't post it online.

## 5. Install adb on your computer

adb is Google's small tool for talking to Android devices. The computer must be on the **same Wi-Fi or
network** as the TV.

| Computer | Install |
|---|---|
| Windows | `winget install Google.PlatformTools` in PowerShell, or download "SDK Platform-Tools for Windows" from [developer.android.com/tools/releases/platform-tools](https://developer.android.com/tools/releases/platform-tools) and unzip it |
| macOS | `brew install android-platform-tools` (with [Homebrew](https://brew.sh)), or the same download |
| Linux | `sudo apt install adb` (Debian, Ubuntu), or the same download |

**It worked if** `adb version` prints a version number. (A fresh install may need a new terminal window.)

## 6. Connect to the TV

```sh
adb connect 192.168.1.50
```

Use your TV's address. **On the TV a prompt appears: "Allow USB debugging?"** Tick **Always allow from this
computer** and choose **Allow**.

Wireless debugging with a pairing code (step 3): first `adb pair 192.168.1.50:<port>` with the port and code
shown on the TV, then `adb connect 192.168.1.50:<port>` with the port shown under "IP address & port". Use
that address and port as the TV address in step 7.

**It worked if** `adb devices` lists the TV as `device` (not `unauthorized` or `offline`).

## 7. Install Artellis

Get Artellis in one of two ways:

- **Easiest:** download `artellis-<version>.apk` from the [Releases](../../releases) page, and this folder.
- **From source:** this folder, plus JDK 17 and the Android SDK (platform 34). The script builds the app.
  Skip this if you downloaded the APK. Otherwise install them first:

  | Computer | Install |
  |---|---|
  | Windows | `winget install EclipseAdoptium.Temurin.17.JDK` and `winget install Google.AndroidStudio` |
  | macOS | `brew install --cask temurin@17 android-studio` |
  | Linux | `sudo apt install openjdk-17-jdk`, and Android Studio from [developer.android.com/studio](https://developer.android.com/studio) |

  Open Android Studio once and let its setup wizard download the SDK. Then in **Settings > Languages &
  Frameworks > Android SDK**, tick **Android 14.0 (API 34)** and apply. No Python or anything else is needed.

  **It worked if** `java -version` prints 17 (open a new terminal window first), and the Android SDK
  settings page shows API 34 installed.

Then, in this folder:

**Windows** (PowerShell):
```powershell
powershell -ExecutionPolicy Bypass -File scripts\install.ps1 -TvHost 192.168.1.50 -Apk artellis-1.0.0.apk
```
**macOS, Linux:**
```sh
TV_HOST=192.168.1.50 APK=artellis-1.0.0.apk sh scripts/install.sh
```
From source, leave out `-Apk ...` / `APK=...`.

**It worked if** the script ends with "Installed", and the TV shows "Hanging today's paintings…" and then,
within a minute or two, the first wall of paintings. The TV needs internet for the first paintings.

**Google TV and Fire TV:** to reach the settings page from the TV later, also run the script once with
`-SettingsTile on` (`--settings-tile on`). An "Artellis settings" tile appears in the app list.

## 8. Turn debugging off again

**Important.** Left on, anyone on your network could connect to the TV.

Open **Developer options** on the TV and switch **USB debugging** (and network or wireless debugging) **off**.
If the TV offers **Revoke USB debugging authorizations**, choose it too.

You can switch it back on any time you want to change something from the computer.

## Using Artellis

- **The screensaver starts** after the TV has been idle for its usual time (Settings > Device Preferences >
  Screen saver > When to start). Any button on the remote wakes the TV.
- **Settings:** TV Settings > Device Preferences > Screen saver > Artellis (Google TV, Fire TV: the
  "Artellis settings" tile). The one setting is **warm light at night**, on by default from 9 PM to 7 AM.
- **Undo everything:** with debugging on, `-Off` brings back the previous screensaver, `-Uninstall` also
  removes Artellis. See "What install changes" in the [README](README.md).

## When something goes wrong

| What you see | What to do |
|---|---|
| `failed to connect` / `cannot connect` | TV awake and on the same network as the computer? Debugging on (step 3)? Address right (step 4)? Try `adb connect` again. |
| `unauthorized` | Accept the prompt on the TV (step 6). If it never appears: `adb kill-server`, then connect again. |
| `adb` is not recognized | adb isn't installed or not found: step 5, then open a new terminal window. |
| `SDK location not found` (building from source) | Make a file `local.properties` in this folder with one line, `sdk.dir=` and the SDK path shown in Android Studio's Android SDK settings (Windows: write it with forward slashes, e.g. `C:/Users/you/AppData/Local/Android/Sdk`). |
| `permission denied` running `gradlew` or `install.sh` (macOS, Linux) | `chmod +x gradlew scripts/install.sh`, then run the command again. |
| `INSTALL_FAILED_OLDER_SDK` | The TV is older than Android 8.0: Artellis can't run on it. |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | Free some space on the TV (uninstall an app you don't use) and try again. |
| PowerShell says scripts are disabled | Use the full command in step 7, which starts with `powershell -ExecutionPolicy Bypass`. |
| "No paintings yet" stays on screen | The TV needs internet. Check its connection; paintings arrive the next time the screensaver runs. |
| The screensaver doesn't appear | Wait for the TV's idle time, or check Settings > Screen saver shows Artellis. Run the install script again. |
| Anything else | Run `-Off` to go back to your previous screensaver, and open an [issue](../../issues) with your TV's brand, model and Android version. |
