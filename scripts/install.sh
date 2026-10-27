#!/bin/sh
# Artellis: install on an Android TV over the network with adb, let it see what's playing, and make it the
# screensaver. Builds from source unless APK= names a downloaded one. The TV's previous screensaver is
# remembered on the TV for --off.
#
#   TV_HOST=192.168.1.50 scripts/install.sh                          build, install, switch on, start
#   TV_HOST=192.168.1.50 APK=artellis-1.0.0.apk scripts/install.sh   install a release APK, no build
#   TV_HOST=192.168.1.50 scripts/install.sh --night-filter on        warm filter at night (or off)
#   TV_HOST=192.168.1.50 scripts/install.sh --settings-tile on       settings tile in the app list (Google TV)
#   TV_HOST=192.168.1.50 scripts/install.sh --off                    previous screensaver back
#   TV_HOST=192.168.1.50 scripts/install.sh --uninstall              --off, then remove Artellis
#
# Needs adb (Android SDK platform-tools) and network debugging on the TV (see README).
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
pkg=io.github.fijeastudio.artellis
dream=$pkg/.ArtellisDream
listener=$pkg/$pkg.MediaListener
before=/data/local/tmp/artellis-before

[ -n "${TV_HOST:-}" ] || { echo "set TV_HOST to the TV's IP address" >&2; exit 1; }
command -v adb >/dev/null || { echo "adb not found: install Android SDK platform-tools" >&2; exit 1; }
case "$TV_HOST" in *:*) serial=$TV_HOST ;; *) serial=$TV_HOST:5555 ;; esac
adb connect "$serial"
tv() { adb -s "$serial" shell "$1"; }

# The screensaver from before is also kept on this computer: some TVs clear /data/local/tmp. The TV's copy
# wins when both exist.
local_restore=$root/artellis-restore-$(printf %s "$serial" | tr -c 'A-Za-z0-9.-' _).txt
saved_c=; saved_e=
if [ -f "$local_restore" ]; then
    saved_c=$(sed -n 1p "$local_restore" | tr -cd 'A-Za-z0-9._/')
    saved_e=$(sed -n 2p "$local_restore" | tr -cd 'A-Za-z0-9')
fi
restore='c='$saved_c'; e='$saved_e'; [ -f '$before' ] && { read c; read e; } < '$before'; p=${c%%/*}; if [ -z $c ] || [ x$c = xnull ] || [ x$c = x'$dream' ] || ! pm path $p >/dev/null 2>&1 || pm list packages -d 2>/dev/null | grep -qx package:$p; then settings delete secure screensaver_components >/dev/null; else settings put secure screensaver_components $c; fi; [ -z $e ] || [ x$e = xnull ] || settings put secure screensaver_enabled $e; rm -f '$before'; echo screensaver: $(settings get secure screensaver_components)'

# An "Artellis settings" tile in the app list, for TVs without a screensaver menu in their settings (Google TV).
settings_tile() {
    flag=false; [ "$1" = on ] && flag=true
    tv "am start -n $pkg/.SettingsActivity --ez settings_tile $flag >/dev/null"
    echo "Settings tile: $1"
}

night_filter() {
    flag=false; [ "$1" = on ] && flag=true
    tv "am start -n $pkg/.SettingsActivity --ez night_filter $flag >/dev/null"
    echo "Night filter: $1"
}

night=
tile=
case "${1:-}" in
    --off) tv "$restore"; rm -f "$local_restore"; exit 0 ;;
    --uninstall) tv "$restore"; rm -f "$local_restore"; tv "cmd notification disallow_listener $listener 2>/dev/null; pm uninstall $pkg"; exit 0 ;;
    --night-filter) night=${2:-on} ;;
    --settings-tile) tile=${2:-on} ;;
    "") ;;
    *) echo "unknown option: $1" >&2; exit 1 ;;
esac

if { [ -n "$night" ] || [ -n "$tile" ]; } && [ -z "${APK:-}" ] && tv "pm path $pkg 2>/dev/null; true" | grep -q package:; then
    [ -z "$night" ] || night_filter "$night"
    [ -z "$tile" ] || settings_tile "$tile"
    exit 0
fi

apk=${APK:-}
if [ -z "$apk" ]; then
    (cd "$root" && ./gradlew assembleRelease -q)
    if [ -n "${LOCALAPPDATA:-}" ]; then apk=$LOCALAPPDATA/artellis-build/outputs/apk/release/app-release.apk; else apk=$root/app/build/outputs/apk/release/app-release.apk; fi
fi
[ -f "$apk" ] || { echo "APK not found: $apk" >&2; exit 1; }
adb -s "$serial" install -r "$apk"

tv "cmd notification allow_listener $listener" >/dev/null
current=$(tv 'settings get secure screensaver_components; settings get secure screensaver_enabled' | tr -d '\r')
if [ ! -f "$local_restore" ] && [ "$(printf %s "$current" | sed -n 1p)" != "$dream" ]; then
    printf '%s\n' "$current" > "$local_restore"
fi
tv "[ -f $before ] || { settings get secure screensaver_components; settings get secure screensaver_enabled; } > $before; settings put secure screensaver_components $dream; settings put secure screensaver_enabled 1; echo screensaver: \$(settings get secure screensaver_components)"
[ -z "$night" ] || night_filter "$night"
[ -z "$tile" ] || settings_tile "$tile"
[ "${NO_START:-}" = 1 ] || tv 'cmd dreams start-dreaming >/dev/null 2>&1 || am start -n com.android.systemui/.Somnambulator >/dev/null 2>&1; true' >/dev/null
echo 'Installed. The first wall arrives within a minute or two.'
echo 'Settings: TV Settings > Device Preferences > Screen saver > Artellis (Google TV: --settings-tile on).'
