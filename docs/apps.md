# Android TV App Package Names & Launch Commands

## Launching Apps via ADB

```bash
# Method 1: Activity Manager (preferred)
adb shell am start -n <package>/<activity>

# Method 2: Monkey launcher (simpler, less control)
adb shell monkey -p <package> -c android.intent.category.LAUNCHER 1

# Get correct launcher activity for any app
adb shell "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER <package>"

# Return to home screen
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
```

---

## Common Streaming Apps

| App | Package | Launch Command |
|-----|---------|----------------|
| Netflix | com.netflix.ninja | `am start -n com.netflix.ninja/.MainActivity` |
| YouTube | com.google.android.youtube.tv | `am start -n com.google.android.youtube.tv/...ShellActivity` |
| Amazon Prime | com.amazon.amazonvideo.livingroom | `monkey -p com.amazon.amazonvideo.livingroom -c android.intent.category.LAUNCHER 1` |
| Disney+ | com.disney.disneyplus | `monkey -p com.disney.disneyplus -c android.intent.category.LAUNCHER 1` |
| HBO Max | com.hbo.hbonow | `monkey -p com.hbo.hbonow -c android.intent.category.LAUNCHER 1` |
| Spotify | com.spotify.tv.android | `monkey -p com.spotify.tv.android -c android.intent.category.LAUNCHER 1` |
| Plex | com.plexapp.android | `monkey -p com.plexapp.android -c android.intent.category.LAUNCHER 1` |
| Kodi | org.xbmc.kodi | `am start -n org.xbmc.kodi/.Splash` |
| VLC | org.videolan.vlc | `am start -n org.videolan.vlc/.StartActivity` |
| Twitch | tv.twitch.android.app | `monkey -p tv.twitch.android.app -c android.intent.category.LAUNCHER 1` |

Full YouTube launch command:
```bash
adb shell am start -n com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity
```

---

## TCL System Apps

| App | Package |
|-----|---------|
| TCL Home / Launcher | com.tcl.initsetup (varies by firmware) |
| TCL Smart Manager | com.tcl.smartmanager |
| Settings | com.android.settings |

---

## System Apps

| App | Package | Launch |
|-----|---------|--------|
| Settings | com.android.settings | `am start -n com.android.settings/.Settings` |
| Google Play Store | com.android.vending | `am start -n com.android.vending/.AssetBrowserActivity` |

---

## Deep Links

```bash
# YouTube video by ID
adb shell am start -a android.intent.action.VIEW -d "vnd.youtube:dQw4w9WgXcQ"

# Netflix title by ID
adb shell am start -a android.intent.action.VIEW -n com.netflix.ninja/.MainActivity -d "netflix://title/80057281"

# Generic URL (opens in browser or appropriate app)
adb shell am start -a android.intent.action.VIEW -d "https://example.com"
```

---

## Listing Installed Packages

```bash
# All packages
adb shell pm list packages

# Third-party only (not system apps)
adb shell pm list packages -3

# Search for a specific app
adb shell pm list packages | grep netflix

# Get detailed info about a package
adb shell dumpsys package com.netflix.ninja | head -30
```

---

## Get Currently Running App

```bash
# Current foreground app (package/activity)
adb shell "dumpsys window windows | grep mCurrentFocus"

# Simpler on newer Android
adb shell "dumpsys activity activities | grep mResumedActivity"
```

---

## Kill / Force Stop an App

```bash
adb shell am force-stop com.netflix.ninja
```

---

## Python Helper

```python
APPS = {
    'netflix': 'com.netflix.ninja/.MainActivity',
    'youtube': 'com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity',
    'settings': 'com.android.settings/.Settings',
    'home': None,  # special case
}

def launch(device, app_name):
    if app_name == 'home':
        device.shell('am start -a android.intent.action.MAIN -c android.intent.category.HOME')
        return
    activity = APPS.get(app_name)
    if not activity:
        raise ValueError(f"Unknown app: {app_name}")
    device.shell(f'am start -n {activity}')
```
