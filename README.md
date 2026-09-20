# TV Volume

> Disclaimer: heavily vibe-coded with Muse Spark 1.3. Review before trusting.

Per-app volume for Samsung TVs, driven from an Android TV box (e.g. Onn 4K).
When you switch apps — or the box sleeps — the app sets the TV to that
app's volume over UPnP. No root, no extra dependencies.

## How it works

1. **Trigger** — a foreground service listens for box sleep (`SCREEN_OFF` /
   `SHUTDOWN`); an accessibility watcher fires on app switches. Boot/update
   restarts the service.
2. **Which volume** — the foreground app is looked up in a per-app map stored on
   the box. Unknown apps get the fallback (8).
3. **Talking to the TV** — resolve the TV's RenderingControl URL (cached →
   manual IP → SSDP discovery), read the current level (`GetVolume`), and
   silent-set the target (`SetVolume`). Samsung gates writes to known
   devices, so first run needs the TV to list/allow the box.

## Setup

1. Sideload the debug APK, open the app once.
2. Grant Usage Access (redirects automatically) and the battery exemption.
3. Enable the watcher: Settings → System → Accessibility → TV Volume.
4. Enter the Samsung's LAN IP, Save.
5. On the Samsung: allow the `TV Volume` device if prompted/listed.
6. Map apps: open something, then in the app tap Use foreground app,
   set its volume, Add. Sliders adjust later.

## Layout

- `TvVolumeService` — foreground service, sleep receiver, discovery warmup
- `AppWatcherService` — accessibility watcher, last-app tracking
- `TvVolumeClient` — app lookup, SSDP discovery, SOAP read/set, prefs map
- `BootReceiver` — restart on boot / update
- `MainActivity` — mapping editor UI (built in code, no resources)

## Notes

- Samsung exposes volume on `http://<tv>:9197/dmr` (RenderingControl).
  Reads are unauthenticated; writes need the box listed as known.
- Give the TV a DHCP reservation — cached URLs and the manual IP assume
  a stable address.
- `adb logcat -s TvVolume` shows detection (`a11y picked=…`) and every
  set result.
