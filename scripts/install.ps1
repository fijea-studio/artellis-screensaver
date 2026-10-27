# Artellis: install on an Android TV over the network with adb, let it see what's playing, and make it the
# screensaver. Builds from source unless you pass a downloaded APK. The TV's previous screensaver is
# remembered on the TV for -Off.
#
#   scripts\install.ps1 -TvHost 192.168.1.50                         build, install, switch on, start
#   scripts\install.ps1 -TvHost 192.168.1.50 -Apk artellis-1.0.0.apk install a release APK, no build
#   scripts\install.ps1 -TvHost 192.168.1.50 -NightFilter on         warm filter at night (or off)
#   scripts\install.ps1 -TvHost 192.168.1.50 -SettingsTile on        settings tile in the app list (Google TV)
#   scripts\install.ps1 -TvHost 192.168.1.50 -Off                    previous screensaver back
#   scripts\install.ps1 -TvHost 192.168.1.50 -Uninstall              -Off, then remove Artellis
#
# Needs adb (Android SDK platform-tools) and network debugging on the TV: Settings > Device Preferences >
# About > press Build 7 times, then Developer options > USB / network debugging. Accept the prompt on the TV.
param(
    [Parameter(Mandatory = $true)][string]$TvHost,
    [string]$Apk = '',
    [ValidateSet('', 'on', 'off')][string]$NightFilter = '',
    [ValidateSet('', 'on', 'off')][string]$SettingsTile = '',
    [switch]$Off,
    [switch]$Uninstall,
    [switch]$NoStart
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$pkg = 'io.github.fijeastudio.artellis'
$dream = "$pkg/.ArtellisDream"
$listener = "$pkg/$pkg.MediaListener"
$before = '/data/local/tmp/artellis-before'

$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb -and $env:LOCALAPPDATA) {
    $sdkAdb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
    if (Test-Path $sdkAdb) { $adb = $sdkAdb }
}
if (-not $adb) { throw 'adb not found: install Android SDK platform-tools and put it on PATH' }
$serial = if ($TvHost -match ':\d+$') { $TvHost } else { "${TvHost}:5555" }
& $adb connect $serial | Out-Host
# No double quotes in the commands: Windows PowerShell 5 drops them on the way to adb.
function Tv([string]$cmd) { & $adb -s $serial shell $cmd; if ($LASTEXITCODE) { throw "adb failed: $cmd" } }

# The saved screensaver back if its app is still there and enabled; otherwise the TV's own default.
# The screensaver from before is also kept on this computer, next to the scripts' folder: some TVs clear
# /data/local/tmp. The TV's copy wins when both exist.
$localRestore = Join-Path $root ('artellis-restore-' + ($serial -replace '[^A-Za-z0-9.-]', '_') + '.txt')
$savedC = ''; $savedE = ''
if (Test-Path $localRestore) {
    $lines = @(Get-Content $localRestore)
    if ($lines.Count -ge 1 -and $lines[0] -match '^[A-Za-z0-9._/]+$') { $savedC = $lines[0] }
    if ($lines.Count -ge 2 -and $lines[1] -match '^[A-Za-z0-9]+$') { $savedE = $lines[1] }
}
$restore = 'c=' + $savedC + '; e=' + $savedE + '; [ -f ' + $before + ' ] && { read c; read e; } < ' + $before + '; p=${c%%/*}; if [ -z $c ] || [ x$c = xnull ] || [ x$c = x' + $dream + ' ] || ! pm path $p >/dev/null 2>&1 || pm list packages -d 2>/dev/null | grep -qx package:$p; then settings delete secure screensaver_components >/dev/null; else settings put secure screensaver_components $c; fi; [ -z $e ] || [ x$e = xnull ] || settings put secure screensaver_enabled $e; rm -f ' + $before + '; echo screensaver: $(settings get secure screensaver_components)'

# An "Artellis settings" tile in the app list, for TVs without a screensaver menu in their settings (Google TV).
function Set-SettingsTile([string]$value) {
    $flag = if ($value -eq 'on') { 'true' } else { 'false' }
    Tv "am start -n $pkg/.SettingsActivity --ez settings_tile $flag >/dev/null" | Out-Null
    Write-Host "Settings tile: $value"
}

function Set-NightFilter([string]$value) {
    $flag = if ($value -eq 'on') { 'true' } else { 'false' }
    Tv "am start -n $pkg/.SettingsActivity --ez night_filter $flag >/dev/null" | Out-Null
    Write-Host "Night filter: $value"
}

if ($Off -or $Uninstall) {
    Tv $restore
    if (Test-Path $localRestore) { Remove-Item $localRestore }
    if ($Uninstall) { Tv "cmd notification disallow_listener $listener 2>/dev/null; pm uninstall $pkg" }
    exit 0
}

$installed = ((Tv "pm path $pkg 2>/dev/null; true") -join ' ') -match 'package:'
if (($NightFilter -or $SettingsTile) -and $installed -and -not $Apk) {
    if ($NightFilter) { Set-NightFilter $NightFilter }
    if ($SettingsTile) { Set-SettingsTile $SettingsTile }
    exit 0
}

if (-not $Apk) {
    Push-Location $root
    try {
        & .\gradlew.bat assembleRelease -q
        if ($LASTEXITCODE) { throw 'build failed (JDK 17 and Android SDK 34 needed; see README)' }
    } finally { Pop-Location }
    $buildDir = if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA 'artellis-build' } else { Join-Path $root 'app\build' }
    $Apk = Join-Path $buildDir 'outputs\apk\release\app-release.apk'
}
if (-not (Test-Path $Apk)) { throw "APK not found: $Apk" }
& $adb -s $serial install -r $Apk | Out-Host
if ($LASTEXITCODE) { throw 'install failed' }

# Song and artist on screen: Artellis may list media sessions (it reads no notifications).
Tv "cmd notification allow_listener $listener" | Out-Null
# Remember the current screensaver once (on the TV and here), then switch to Artellis.
$current = @(Tv 'settings get secure screensaver_components; settings get secure screensaver_enabled')
if (-not (Test-Path $localRestore) -and $current.Count -ge 2 -and $current[0].Trim() -ne $dream) {
    Set-Content -Path $localRestore -Value @($current[0].Trim(), $current[1].Trim())
}
Tv ('[ -f ' + $before + ' ] || { settings get secure screensaver_components; settings get secure screensaver_enabled; } > ' + $before + "; settings put secure screensaver_components $dream; settings put secure screensaver_enabled 1; echo screensaver: `$(settings get secure screensaver_components)")
if ($NightFilter) { Set-NightFilter $NightFilter }
if ($SettingsTile) { Set-SettingsTile $SettingsTile }
if (-not $NoStart) {
    # Android 12+ has `cmd dreams`; older builds start the screensaver through SystemUI's Somnambulator.
    Tv 'cmd dreams start-dreaming >/dev/null 2>&1 || am start -n com.android.systemui/.Somnambulator >/dev/null 2>&1; true' | Out-Null
}
Write-Host 'Installed. The first wall arrives within a minute or two.'
Write-Host 'Settings: TV Settings > Device Preferences > Screen saver > Artellis (Google TV: -SettingsTile on).'
